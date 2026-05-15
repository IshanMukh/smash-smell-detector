"""
SMASH Backend
─────────────
Receives Java code from the VS Code extension,
builds the analysis prompt (Context Manager),
calls Ollama (llama3.1:8b) for LLM inference,
parses the JSON response, and returns smell results.
"""

import json
import re #formatting json messages
import requests
from flask import Flask, request, jsonify
from flask_cors import CORS
# By default, browsers and applications block requests that come from a different origin — meaning a different port or address. Our VS Code extension runs separately from our Flask server. Without CORS enabled, Flask would reject every request the extension sends.
# CORS(app) which you'll see in the next few lines simply tells Flask:

# "Allow requests coming from other applications, don't block them."
app = Flask(__name__)
CORS(app)  # allow VS Code extension (localhost) to call this

# ── Configuration ──────────────────────────────────────────────────────────────
OLLAMA_URL   = "http://localhost:11434/api/generate"
# the /api/generate part is the specific route inside Ollama that accepts prompts and generates responses.
# So when our Flask code wants to ask Llama to analyse some Java code, it sends an HTTP POST request to exactly this address.
OLLAMA_MODEL = "llama3.1:8b"
REQUEST_TIMEOUT = 300 # seconds – LLM can be slow on first load wait maximum 180 seconds (3 minutes), then give up and show an error.

# ── Prompt template (Context Manager) ─────────────────────────────────────────
PROMPT_TEMPLATE = """You are analyzing Java class for software architectural smell indicators.
Class name:
{class_name}
Java code:
{code}
Task:
Analyze the class for architecture smell indicators.
You may identify any architecture smell that is reasonably visible from the given class.
Do not restrict yourself to only one smell type.
Possible smell categories may include, but are not limited to:
- Cyclic Dependency
- God Component / God Class
- Hub-Like Dependency
- Unstable Dependency
- Cyclic Hierarchy
- Deep Hierarchy
- Wide Hierarchy
Smell guidance:
1. Cyclic Dependency
A Cyclic Dependency smell appears when two or more components depend on each other in a cycle. At class level, direct proof of a cycle is often limited because only one class is shown at a time. Report it only if the code gives strong visible signs that the class participates in circular relationships with other components.
2. God Component / God Class
A God Component smell appears when a component becomes excessively large and concentrates too much logic. It often has low cohesion, growing complexity, and too many responsibilities. At class level, visible signs may include very large code size, many methods, many fields, many different responsibilities, and strong centralization of behavior.
3. Hub-Like Dependency
A Hub-Like Dependency smell appears when a component is highly connected to many other components through many incoming and outgoing dependencies. It often centralizes coordination or logic, can become a unique point of failure, and can increase ripple effects across the system. At class level, visible signs may include many dependencies, many coordinated collaborators, and overly central orchestration.
4. Unstable Dependency
An Unstable Dependency smell appears when a component depends on components that are less stable than itself, making it vulnerable to ripple effects and frequent changes. Since full stability analysis normally needs a system dependency graph, report this smell only if there is visible evidence that the class depends heavily on volatile, implementation-heavy, or unstable-looking collaborators.
5. Cyclic Hierarchy
A Cyclic Hierarchy smell appears when a supertype depends on one of its own subtypes. This can harm reusability, testability, extensibility, and reliability. Report it only if inheritance and dependency relations in the given code strongly suggest that a parent type knows or relies on its child type.
6. Deep Hierarchy
A Deep Hierarchy smell appears when inheritance is excessively deep. This makes behavior harder to predict, increases complexity, complicates change, and can make testing more difficult. At class level, visible signs may include many inheritance levels, repeated overriding, and reliance on behavior inherited from a long chain of supertypes.
7. Wide Hierarchy
A Wide Hierarchy smell appears when a hierarchy is too broad and seems to be missing useful intermediate abstractions. This can reduce understandability, extensibility, reusability, and changeability. At class level, visible signs may include many sibling-like variations with weak abstraction structure or signs that the class belongs to an overly broad inheritance family without proper intermediate layers.
General interpretation rule:
You are given only one Java class at a time, not the full system dependency graph, not the full package structure, and not the full inheritance graph.
So report only smells that can be reasonably inferred from visible class-level evidence.
Do not invent system-level evidence that is not visible in the code.
Evidence guidance:
- Prefer smells that are directly supported by the visible code
- Be cautious with graph-based smells such as Cyclic Dependency, Hub-Like Dependency, and Unstable Dependency
- Be cautious with hierarchy-based smells such as Cyclic Hierarchy, Deep Hierarchy, and Wide Hierarchy unless inheritance evidence is clearly visible
- If evidence is weak, unclear, or not visible, return []
If no smell is reasonably visible, return exactly:
[]
Return ONLY a valid JSON array.
Do not write markdown.
Do not write explanation outside JSON.
Do not use code fences.
Each item in the JSON array must have exactly these fields:
- smell_category
- smell_severity
- smell_location
- smell_description
- smell_suggestion
Rules:
- smell_category must be the most suitable smell name based on the visible evidence
- smell_severity must be an integer from 1 to 10
- smell_location must mention the class name
- smell_description must be short and specific
- smell_suggestion must be a concrete refactoring suggestion
- only report smells that have reasonable evidence in the class
- if multiple smells are clearly visible, return multiple JSON objects"""


# ── Helpers ────────────────────────────────────────────────────────────────────

def build_prompt(class_name: str, code: str) -> str:
    """
    Context Manager: Fills in the prompt template with real Java class data.
    
    Args:
        class_name (str): The name of the Java class (e.g., 'Rtl2832Frontend').
        code (str): The full Java source code as text.
        
    Returns:
        str: The fully formatted instruction string ready to be sent to the LLM.
    """
    return PROMPT_TEMPLATE.format(class_name=class_name, code=code)
#Remember those {class_name} and {code} placeholders in the prompt template? This line fills them in..format() is a built-in Python string method. It finds every {placeholder} in the string and replaces it with the real value.

def call_ollama(prompt: str) -> str:#This function is responsible for the second HTTP conversation — Flask talking to Ollama.
    """
    Model Management Layer: Calls the local Ollama REST API (non-streaming) 
    and returns the raw model text.
    
    Args:
        prompt (str): The complete, formatted prompt containing the instructions and Java code.
        
    Returns:
        str: The raw text response generated by the Llama 3.1 model.
        
    Raises:
        requests.exceptions.ConnectionError: If Ollama is not running.
        requests.exceptions.Timeout: If the model takes longer than the 300-second REQUEST_TIMEOUT.
    """
    payload = {#his is a Python dictionary that we will send to Ollama as JSON.
        "model": OLLAMA_MODEL,
        "prompt": prompt,#sends the fully filled prompt
        "stream": False,#by default Ollama sends the response word by word as it generates. We don't want that — we want to wait and get the complete response all at once. False turns streaming off.
        "options": {
            "temperature": 0.1,   # low temperature → more deterministic JSON
            "num_predict": 2048 #max no of tokens llm can generate for response
        }
    }
    response = requests.post(OLLAMA_URL, json=payload, timeout=REQUEST_TIMEOUT)#requests.post(OLLAMA_URL) — sends a POST request to http://localhost:11434/api/generate ,json=payload — automatically converts our Python dictionary into JSON and sends it ,timeout=REQUEST_TIMEOUT — if Ollama takes more than 180 seconds, stop waiting and raise an error
    response.raise_for_status()#After getting a response, this checks if Ollama returned an error status code like 404 or 500. If it did, this line automatically raises a Python exception so we know something went wrong. If everything is fine it does nothing.
    return response.json().get("response", "") #response.json() — converts Ollama's JSON response into a Python dictionary..get("response", "") — gets the value of the "response" key — that is the actual LLM output text. The "" is a default value — if for some reason the key doesn't exist, return an empty string instead of crashing.


def extract_json_array(text: str) -> list:#This function cleans all of that up and extracts just the pure JSON array.
    """
    Parse the LLM response into a Python list.
    Handles cases where the model wraps JSON in markdown fences.
    """
    # Strip markdown fences if present
    text = re.sub(r"```(?:json)?", "", text).strip()
    text = text.strip("`").strip()

    # Try to find a JSON array in the text
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if match:
        return json.loads(match.group())

    # If the model returned an empty array token
    if text.strip() == "[]":
        return []#no smell detected

    raise ValueError(f"Could not extract JSON array from model output:\n{text[:500]}")


# ── Routes ─────────────────────────────────────────────────────────────────────

@app.route("/health", methods=["GET"]) #This creates a URL endpoint at http://localhost:5000/health that accepts GET requests.
def health():
    """Simple health-check endpoint."""
    return jsonify({"status": "ok", "model": OLLAMA_MODEL})

#------------------trigger receiver--------------------
@app.route("/analyze", methods=["POST"])#This is the main route — the one the VS Code extension calls every time you press Ctrl+S. It accepts POST requests because the extension is sending data (your Java code) not just asking for something
def analyze():
    """
    Trigger Receiver + Smell Analysis endpoint.

    Expects JSON body:
        { "class_name": "MyClass", "code": "<java source>" }

    Returns JSON:
        { "smells": [ { smell fields … }, … ] }
    """
    data = request.get_json(force=True)#request.get_json() reads the JSON the extension sent and turns it into a Python dictionary.force=True means: even if the request doesn't explicitly say it's JSON, try to parse it as JSON anyway. This makes it more robust.


    if not data or "code" not in data:#checks that - did the extension actually send us something? And does it contain the "code" field we need?
        return jsonify({"error": "Missing 'code' field in request body"}), 400

    class_name = data.get("class_name", "UnknownClass").strip() or "UnknownClass" #data.get("class_name", "UnknownClass")Gets the class name from the dictionary. The "UnknownClass" is a default value — if for some reason the extension didn't send a class name, use "UnknownClass" instead of crashing.,.strip()-Removes any accidental whitespace or newlines from the start and end of the string.,or "UnknownClass"-If after stripping the class name is an empty string "", use "UnknownClass" instead. The or in Python returns the right side if the left side is empty/falsy.
    code       = data["code"].strip()

    if not code:
        return jsonify({"error": "Empty Java code provided"}), 400

    print(f"[SMASH] Received analysis request for class: {class_name}")#This prints a message in the backend terminal window every time a request comes in. When you press Ctrl+S in VS Code, you will see this appear in the start_backend.bat window. Very useful to confirm the backend is receiving requests.

    # 1. Context Manager – build the prompt
    prompt = build_prompt(class_name, code)

    # 2. Model Management Layer – call LLM
    try:
        raw_response = call_ollama(prompt)
        print(f"[SMASH] Raw LLM response:\n{raw_response[:300]}…")
    except requests.exceptions.ConnectionError:
        return jsonify({
            "error": (
                "Cannot connect to Ollama. "
                "Make sure Ollama is running: open a terminal and run 'ollama serve'"
            )
        }), 503
    except requests.exceptions.Timeout:
        return jsonify({"error": "Ollama request timed out. Try again – model may still be loading."}), 504
    except Exception as e:
        return jsonify({"error": f"Ollama error: {str(e)}"}), 500

    # 3. Results and Feedback – parse response
    try:
        smells = extract_json_array(raw_response)
    except (ValueError, json.JSONDecodeError) as e:
        print(f"[SMASH] JSON parse error: {e}")
        # Return raw text so the caller is not left empty-handed
        return jsonify({
            "error": f"Failed to parse LLM JSON output: {str(e)}",
            "raw": raw_response[:1000]
        }), 500

    print(f"[SMASH] Detected {len(smells)} smell(s) in {class_name}")
    return jsonify({"smells": smells})


# ── Entry point ────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print("=" * 60)
    print("  SMASH Backend starting on http://localhost:5000")
    print(f"  Using model : {OLLAMA_MODEL}")
    print(f"  Ollama URL  : {OLLAMA_URL}")
    print("=" * 60)
    app.run(host="0.0.0.0", port=5000, debug=False)
#host="0.0.0.0" — listen on all network addresses on your machine, not just localhost. This is why the backend terminal showed both 127.0.0.1:5000 and 192.168.31.76:5000 when you started it.
#port=5000 — run on port 5000. This matches the backendUrl in the VS Code extension settings.
# debug=False — don't run in debug mode. Debug mode auto-restarts the server when you change the code, which is useful during development but we keep it off for stability.