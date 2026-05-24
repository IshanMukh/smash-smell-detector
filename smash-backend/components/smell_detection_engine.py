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
  - code          : the Java source code (from SourceCodeRetriever)
  - class_name    : the Java class name (from DetectionCoordinator)
  - prompt        : the filled analysis prompt (from ContextRetriever)
  - model_name    : the LLM model to use (from ModelRetriever)

This component:
  1. Takes all of that from the DetectionRequest
  2. Sends the prompt to Ollama (the LLM) via HTTP POST
  3. Waits for Ollama to run Llama and return a response
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
        print(f"[SmellDetectionEngine] Sending request to Ollama...")
        response = requests.post(
            model_config["ollama_url"],
            json=payload,
            timeout=model_config["timeout"]
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

        # ── Step 8: Store the parsed smells in the DetectionRequest ───────────
        request.smells = smells
        print(f"[SmellDetectionEngine] Detection complete")
        print(f"  Smells detected: {len(smells)}")

        # ── Step 9: Return the updated DetectionRequest ───────────────────────
        return request

    def _parse_llm_response(self, text: str) -> list:
        """
        Parses the raw LLM text response into a Python list.

        Why do we need this?
        --------------------
        LLMs are not always perfectly behaved. Even when told
        to return only JSON, Llama sometimes wraps its response
        in markdown code fences like:
            ```json
            [{...}]
            ```
        or adds extra text before/after the JSON array.

        This method cleans all of that up and extracts just
        the pure JSON array.

        Parameters:
        -----------
        text : str
            Raw text response from the LLM.

        Returns:
        --------
        list
            Parsed list of smell dictionaries.
            Returns empty list [] if no smells were found.

        Raises:
        -------
        ValueError
            If the LLM response cannot be parsed as JSON.
        """

        # ── Clean up markdown fences ──────────────────────────────────────────
        # re.sub() finds and replaces text matching a pattern.
        # r"```(?:json)?" matches: ```json or just ```
        # We replace them with "" (nothing) — effectively deleting them.
        # .strip() removes leading/trailing whitespace after deletion.
        text = re.sub(r"```(?:json)?", "", text).strip()

        # Remove any remaining individual backtick characters
        text = text.strip("`").strip()

        # ── Search for a JSON array in the cleaned text ───────────────────────
        # r"\[.*\]" is a pattern that matches anything starting with [
        # and ending with ] — which is exactly what a JSON array looks like.
        # re.DOTALL means the .* can match across multiple lines.
        match = re.search(r"\[.*\]", text, re.DOTALL)
        if match:
            # json.loads() converts the JSON text into a Python list
            return json.loads(match.group())

        # ── Handle empty result ───────────────────────────────────────────────
        # If the LLM found no smells and returned just []
        # return an empty Python list. This is the clean "no smells" case.
        if text.strip() == "[]":
            return []

        # ── If nothing worked, raise an error ─────────────────────────────────
        # Include the first 500 characters of the response so we can
        # see what the LLM actually said and debug the problem.
        raise ValueError(
            f"Could not extract JSON array from LLM output.\n"
            f"First 500 characters of response:\n{text[:500]}"
        )
