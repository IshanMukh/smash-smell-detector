"""
========================================================
  COMPONENT: Smell Detection Engine
  C4 Diagram Reference: SmellDetectionEngine [Component: python]
========================================================

What is this component?
------------------------
The Smell Detection Engine is the CORE of the entire
SMASH system. It is where the actual smell detection happens.

Its job from the C4 diagram:
"Runs the smell detection using code, architecture context,
prompts and model configuration"

In plain English:
-----------------
By the time this component runs, the DetectionRequest already has:
  - code          : the source code of one class (from SourceCodeRetriever)
  - class_name    : the name of that class (from DetectionCoordinator)
  - prompt        : the filled analysis prompt (from ContextRetriever)
  - model_name    : the LLM model to use (from ModelRetriever)

This component:
  1. Takes all of that from the DetectionRequest
  2. Sends the prompt to Ollama (the LLM) via HTTP POST
  3. Waits for Ollama to run the model and return a response
  4. Parses the raw LLM text into a clean Python list of smells
  5. Stores the results back into the DetectionRequest

This component handles two of the three HTTP conversations
in the entire project — specifically the second one:
  Flask Backend → Ollama (port 11434)

Position in pipeline:
---------------------
ModelRetriever
    → SmellDetectionEngine  ← YOU ARE HERE
    → ResultsPublisher
"""

# ── Imports ───────────────────────────────────────────────────────────────────
# requests : Python library for making HTTP calls. Used to POST to Ollama.
# json     : Built-in Python library. Used to parse LLM's JSON response.
# re       : Regular expressions. Used to clean up LLM's text response.
import requests
import json
import re
from data.detection_request import DetectionRequest


# ── MODULE: Smell Detection Engine ────────────────────────────────────────────
class SmellDetectionEngine:
    """
    Runs smell detection by calling the LLM via Ollama.
    Parses the raw LLM response into structured smell results.

    C4 Reference: SmellDetectionEngine [Component: python]
    """

    def run_detection(self, request: DetectionRequest, model_config: dict) -> DetectionRequest:
        """
        Sends the analysis prompt to Ollama, gets the response,
        parses it, and stores results in the DetectionRequest.

        Parameters:
        -----------
        request      : DetectionRequest
            Must have 'prompt' and 'model_name' populated.
        model_config : dict
            Full model configuration from ModelRetriever.
            Contains: ollama_url, temperature, max_tokens, timeout.

        Returns:
        --------
        DetectionRequest
            Same object with 'raw_llm_response' and 'smells' populated.

        Raises:
        -------
        requests.exceptions.ConnectionError
            If Ollama is not running.
        requests.exceptions.Timeout
            If Ollama takes longer than the configured timeout.
        """

        # ── Step 1: Log that we are starting detection ────────────────────────
        print(f"[SmellDetectionEngine] Starting smell detection")
        print(f"  Class  : {request.class_name}")
        print(f"  Model  : {request.model_name}")

        # ── Step 2: Build the Ollama request payload ──────────────────────────
        # This is the JSON body we send to Ollama's /api/generate endpoint.
        # Each field is explained:
        #
        # "model"   : which LLM to use — must match what was pulled with ollama pull
        # "prompt"  : the full filled-in analysis instruction for the LLM
        # "stream"  : False means we want the complete response at once,
        #             not word by word as it generates
        # "options" : extra LLM settings
        #   "temperature" : how random/creative the response is (0.1 = mostly consistent)
        #   "num_predict" : max tokens (words) to generate in the response
        payload = {
            "model"  : request.model_name,
            "prompt" : request.prompt,
            "stream" : False,
            "options": {
                "temperature": model_config["temperature"],
                "num_predict": model_config["max_tokens"]
            }
        }

        # ── Step 3: Send the HTTP POST request to Ollama ──────────────────────
        # requests.post() sends the payload to Ollama's generate endpoint.
        # timeout= means: if Ollama does not respond within this many seconds,
        # raise a Timeout exception automatically.
        
        # The ngrok-skip-browser-warning header is required when routing through
        # a ngrok tunnel (Google Colab mode). Without it, ngrok intercepts the
        # request with a browser warning page and returns a 403 error.
        # This header is harmless when running Ollama locally without ngrok.
        print(f"[SmellDetectionEngine] Sending request to Ollama...")
        response = requests.post(
            model_config["ollama_url"],
            json=payload,
            timeout=model_config["timeout"],
            headers={"ngrok-skip-browser-warning": "true"}
        )

        # ── Step 4: Check for HTTP errors ─────────────────────────────────────
        # raise_for_status() checks if Ollama returned an error code (like 500).
        # If it did, this line raises an exception automatically.
        # If everything is fine, it does nothing.
        response.raise_for_status()

        # ── Step 5: Extract the raw text from Ollama's response ───────────────
        # Ollama returns a JSON object like:
        # { "response": "[{smell results...}]", "done": true }
        # We extract only the "response" value — the actual LLM output text.
        # .get("response", "") means: if "response" key is missing, use ""
        raw_text = response.json().get("response", "")

        # ── Step 6: Store the raw response in the DetectionRequest ────────────
        request.raw_llm_response = raw_text
        print(f"[SmellDetectionEngine] Received LLM response")
        print(f"  Preview: {raw_text[:200]}...")

        # ── Step 7: Parse the raw text into a Python list ─────────────────────
        # The LLM returns text, not a Python list.
        # We call _parse_llm_response() to extract the JSON array from the text
        # and convert it into a Python list of smell dictionaries.
        smells = self._parse_llm_response(raw_text)
        smells = self._filter_false_positives(smells, request.code)
        # ── Step 8: Store the parsed smells in the DetectionRequest ───────────
        request.smells = smells
        print(f"[SmellDetectionEngine] Detection complete")
        print(f"  Smells detected: {len(smells)}")

        # ── Step 9: Return the updated DetectionRequest ───────────────────────
        return request

    def _parse_llm_response(self, raw_response: str) -> list:
        """
        Tries multiple strategies to extract a valid JSON array from
        the LLM's raw text response.

        Why do we need multiple strategies?
        ------------------------------------
        The LLM is instructed to return only a JSON array, but smaller
        models sometimes wrap their output in markdown code fences
        (```json ... ```) or add explanatory text before or after the JSON.
        This method handles all of those cases gracefully.

        Strategy 1 — Remove markdown fences and direct parse
        Strategy 2 — Search for a JSON array anywhere in the text
        Strategy 3 — Graceful fallback: return [] and log a warning

        Returns [] if no valid JSON array can be extracted.
        Never raises an exception — always returns a list.
        """
        if not raw_response or not raw_response.strip():
            return []

        # Strategy 1: Remove markdown code fences
        cleaned = re.sub(r'```json\s*', '', raw_response)
        cleaned = re.sub(r'```\s*', '', cleaned)
        cleaned = cleaned.strip()

        # Strategy 2: Direct parse if response is already clean JSON
        try:
            result = json.loads(cleaned)
            if isinstance(result, list):
                return result
        except json.JSONDecodeError:
            pass

        # Strategy 3: Find JSON array anywhere in the text using regex
        array_match = re.search(r'\[.*?\]', cleaned, re.DOTALL)
        if array_match:
            try:
                result = json.loads(array_match.group())
                if isinstance(result, list):
                    return result
            except json.JSONDecodeError:
                pass

        # Strategy 4: Graceful fallback
        print(f"[SmellDetectionEngine] WARNING: LLM did not return JSON.")
        print(f"[SmellDetectionEngine] Raw response preview: {raw_response[:300]}")
        return []
    
    def _filter_false_positives(self, smells: list, code: str) -> list:
        """
        Post-processing filter that removes likely false positives.

        Why do we need this?
        ---------------------
        Smaller LLMs tend to flag simple data classes, DTOs, and value objects
        as God Class or Hub-Like Dependency even when explicitly told not to.
        This filter catches those cases by counting methods and dependencies
        directly from the class code using regex, without relying on the LLM.

        Rules applied:
        - God Class: removed if the class has fewer than 5 public methods
        - Hub-Like Dependency: removed if fewer than 6 real external dependencies
        - Severity 0: removed always (LLM sometimes returns 0 severity smells)
        - Empty description: removed always
        """

        if not smells:
            return []

        filtered = []

        # Count public methods in the class code using regex
        # This matches method declarations like: public void myMethod(
        # or public String myMethod( etc.
        method_count = len(re.findall(
            r'\b(?:public|protected)\s+(?:static\s+)?(?:final\s+)?'
            r'(?:[\w<>\[\]]+)\s+(\w+)\s*\(',
            code
        ))

        # Count meaningful external dependencies
        # We look for import statements and field type declarations
        # and exclude standard Java/language utility types
        EXCLUDED_TYPES = {
            'string', 'list', 'map', 'set', 'optional', 'boolean', 'integer',
            'long', 'int', 'void', 'object', 'logger', 'log', 'override',
            'string[]', 'byte[]', 'collection', 'arraylist', 'hashmap',
            'iterator', 'enum', 'class', 'exception', 'error'
        }

        # Extract unique import names (last part of import path)
        imports = re.findall(r'import\s+[\w.]+\.(\w+);', code)
        # Count field-level injected dependencies in addition to imports
        injected_fields = re.findall(
            r'@(?:Inject|Autowired|Named|Value)\s+(?:private|protected|public)?\s+'
            r'(?:final\s+)?(\w+)',
            code
        )
        real_dependencies = {
            imp for imp in imports + injected_fields
            if imp.lower() not in EXCLUDED_TYPES and len(imp) > 2
        }
        dependency_count = len(real_dependencies)

        print(f"[SmellDetectionEngine] Filter: {method_count} methods, "
            f"{dependency_count} real dependencies")

        for smell in smells:
            category  = smell.get("smell_category", "").lower()
            severity  = smell.get("smell_severity", 0)
            desc      = smell.get("smell_description", "").strip()

            # Rule 1: Remove zero severity smells — the LLM is saying itself there is no smell
            if severity == 0:
                print(f"[SmellDetectionEngine] Filtered out (severity 0): {smell.get('smell_category')}")
                continue

            # Rule 2: Remove empty description smells
            if not desc:
                print(f"[SmellDetectionEngine] Filtered out (empty description): {smell.get('smell_category')}")
                continue
            # Rule 3: God Class requires 5+ public methods
            # the description must mention at least 2 distinct responsibility types
            if "god" in category:
                if method_count < 5:
                    print(f"[SmellDetectionEngine] Filtered out God Class "
                        f"(only {method_count} methods found)")
                    continue
                # Check that description mentions multiple distinct responsibility keywords
                responsibility_keywords = ["data access", "business logic", "serialization", 
                                        "coordination", "deserialization", "pagination",
                                        "consuming", "producing", "searching", "filtering"]
                matched = [kw for kw in responsibility_keywords if kw in desc.lower()]
                if len(matched) < 2:
                    print(f"[SmellDetectionEngine] Filtered out God Class "
                        f"(only {len(matched)} responsibility types named in description)")
                    continue

            # Rule 4: Hub-Like Dependency requires 6+ real external dependencies
            if "hub" in category:
                if dependency_count < 6:
                    print(f"[SmellDetectionEngine] Filtered out Hub-Like "
                        f"(only {dependency_count} real dependencies found)")
                    continue

            # Rule 5: Unstable Dependency requires at least 3 real dependencies
            # A class with 1-2 dependencies cannot meaningfully show instability
            if "unstable" in category:
                if dependency_count < 3:
                    print(f"[SmellDetectionEngine] Filtered out Unstable Dependency "
                        f"(only {dependency_count} real dependencies found)")
                    continue

            # Rule 6: Cyclic Dependency — only keep if description explicitly
            # names both directions of the cycle. Generic descriptions are removed.
            if "cyclic" in category and "hierarchy" not in category:
                if "cycle" not in desc.lower() and "circular" not in desc.lower() and "each other" not in desc.lower():
                    print(f"[SmellDetectionEngine] Filtered out Cyclic Dependency "
                        f"(no cycle evidence in description)")
                    continue

            # Rule 7: Cyclic Hierarchy — only keep if description mentions
            # a supertype depending on a subtype explicitly
            if "cyclic hierarchy" in category:
                if "subtype" not in desc.lower() and "child" not in desc.lower() and "subclass" not in desc.lower():
                    print(f"[SmellDetectionEngine] Filtered out Cyclic Hierarchy "
                        f"(no subtype reference in description)")
                    continue

            # Rule 8: Deep Hierarchy requires at least 2 inheritance levels visible
            # We count "extends" and "implements" keywords in the class declaration
            if "deep" in category:
                inheritance_count = len(re.findall(r'\bextends\b|\bimplements\b', code))
                if inheritance_count < 2:
                    print(f"[SmellDetectionEngine] Filtered out Deep Hierarchy "
                        f"(only {inheritance_count} inheritance keywords found)")
                    continue

            # Rule 9: Wide Hierarchy — only keep if description names
            # at least 2 sibling classes explicitly
            if "wide" in category:
                # Count capitalized class names mentioned in the description
                # as a proxy for whether siblings were actually named
                named_classes = re.findall(r'\b[A-Z][a-zA-Z]+\b', desc)
                if len(named_classes) < 2:
                    print(f"[SmellDetectionEngine] Filtered out Wide Hierarchy "
                        f"(no sibling classes named in description)")
                    continue

            filtered.append(smell)

        print(f"[SmellDetectionEngine] Filter: {len(smells)} smells → "
            f"{len(filtered)} after filtering")
        return filtered
        