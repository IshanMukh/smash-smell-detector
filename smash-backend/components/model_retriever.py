"""
========================================================
  COMPONENT: Model Retriever
  C4 Diagram Reference: ModelRetriever [Component: python]
========================================================

What is this component?
------------------------
The Model Retriever is responsible for fetching the
LLM model configuration for the analysis.

Its job from the C4 diagram:
"Calls Model Management Layer to obtain chosen LLM
model configuration"

In plain English:
-----------------
In the full production version of SMASH, this component
would connect to a Model Management Layer that has:
  - A Model Registry (database of available models)
  - A Model Repository (where models are stored)
  - A Model Selection component (picks the best model)

It would query these to find out which model to use
for the current analysis request.

In our current version:
-----------------------
We use a fixed model configuration defined as constants
in this file. The ModelRetriever reads these constants
and stores them in the DetectionRequest.

This component is designed to be EASILY EXPANDABLE —
in the future, you could add dynamic model selection
logic here (e.g. choose different models for different
file sizes) without changing any other component.

Position in pipeline:
---------------------
ContextRetriever
    → ModelRetriever  ← YOU ARE HERE
    → SmellDetectionEngine
    → ResultsPublisher
"""

# ── Imports ───────────────────────────────────────────────────────────────────
from data.detection_request import DetectionRequest


# ── MODEL CONFIGURATION ───────────────────────────────────────────────────────
# These constants represent the Model Management Layer from the C4 diagram.
# In the C4 diagram this maps to:
#   Model Management Layer [Software System]
#     → Model selection [Component: python]
#     → Model registry  [Storage: e.g. Gitlab]
#
# OLLAMA_BASE_URL : The address where Ollama is running on your machine.
#                  Port 11434 is Ollama's default port.
#                  /api/generate is the route that accepts prompts.
#
# DEFAULT_MODEL   : The name of the LLM model to use.
#                  Must match exactly what you pulled with: ollama pull llama3.1:8b
#
# TEMPERATURE     : Controls how creative/random the LLM response is.
#                  0.0 = completely deterministic (same output every time)
#                  1.0 = very random and creative
#                  0.1 = mostly deterministic — good for structured JSON output
#
# MAX_TOKENS      : Maximum number of words/tokens the LLM can generate.
#                  2048 is enough for even large smell reports.
#
# REQUEST_TIMEOUT : Set to None — Ollama is allowed to take as long as it
#                  needs. This is intentional because inference speed depends
#                  on hardware. None means "wait forever" in Python's requests.
# NOTE: OLLAMA_BASE_URL currently points to a Google Colab ngrok tunnel.

# This URL changes every time the Colab session reconnects.
# For local mode, change this to: "http://localhost:11434/api/generate"
# For permanent deployment, this would point to an AWS EC2 or similar host.
OLLAMA_BASE_URL  = "https://skincare-monogamy-drapery.ngrok-free.dev/api/generate"
DEFAULT_MODEL    = "llama3.1:8b"
TEMPERATURE      = 0.1
MAX_TOKENS       = 2048
REQUEST_TIMEOUT  = None


# ── MODULE: Model Retriever ───────────────────────────────────────────────────
class ModelRetriever:
    """
    Fetches LLM model configuration from the Model Management Layer.
    Stores the model name and settings into the DetectionRequest.

    C4 Reference: ModelRetriever [Component: python]
    """

    def retrieve_model_config(self, request: DetectionRequest) -> DetectionRequest:
        """
        Gets the model configuration and stores it in the DetectionRequest.

        Parameters:
        -----------
        request : DetectionRequest
            The data object flowing through the pipeline.

        Returns:
        --------
        DetectionRequest
            Same object with 'model_name' field now populated.
        """

        # ── Step 1: Log what we are doing ─────────────────────────────────────
        print(f"[ModelRetriever] Fetching model configuration from Model Management Layer")

        # ── Step 2: Get the model configuration ───────────────────────────────
        # In a full system this would query a model registry.
        # Right now we read from our constants defined above.
        model_config = {
            "model_name"    : DEFAULT_MODEL,
            "ollama_url"    : OLLAMA_BASE_URL,
            "temperature"   : TEMPERATURE,
            "max_tokens"    : MAX_TOKENS,
            "timeout"       : REQUEST_TIMEOUT
        }

        # ── Step 3: Store model name in DetectionRequest ──────────────────────
        # Only the model_name is stored in the DetectionRequest itself.
        # The full config is passed to SmellDetectionEngine separately.
        request.model_name = model_config["model_name"]

        # ── Step 4: Log the selected model ────────────────────────────────────
        print(f"[ModelRetriever] Model selected: {request.model_name}")
        print(f"  Ollama URL  : {model_config['ollama_url']}")
        print(f"  Temperature : {model_config['temperature']}")
        print(f"  Max tokens  : {model_config['max_tokens']}")
        print(f"  Timeout     : {model_config['timeout']} seconds")

        # ── Step 5: Return both the updated request and the full config ────────
        # We return a tuple (request, model_config) because SmellDetectionEngine
        # needs both the DetectionRequest AND the full model config to call Ollama.
        return request, model_config
