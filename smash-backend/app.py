"""
========================================================
  SMASH BACKEND — Main Entry Point
  C4 Diagram Reference: SMASH [Software System]
========================================================

What is this file?
------------------
This is the main entry point of the SMASH backend.
It creates the Flask server and defines the API routes
(Trigger Receiver) that the VS Code extension calls.

This file is intentionally THIN — it does NOT contain
any business logic. Its only job is to:
  1. Create the Flask app
  2. Define the routes (endpoints)
  3. Wire together all the components in the right order
  4. Start the server

All the actual logic lives in the components/ folder.
Each component maps directly to a box in the C4 diagram.

How the pipeline works:
-----------------------
Every time the developer presses Ctrl+S on a Java file:

  VS Code Extension (DetectionClient)
      ↓  HTTP POST /analyze
  Trigger Receiver ← this file catches it
      ↓
  DetectionCoordinator  → validates and normalises the request
      ↓
  SourceCodeRetriever   → retrieves and logs source code info
      ↓
  ContextRetriever      → builds the LLM prompt
      ↓
  ModelRetriever        → gets the LLM model configuration
      ↓
  SmellDetectionEngine  → calls Ollama, gets smell results
      ↓
  ResultsPublisher      → formats and returns results
      ↓
  VS Code Extension shows notification to developer

C4 Components used in this file:
---------------------------------
  - Trigger Receiver    : the /analyze route
  - Feedback Collector  : the /feedback route
  - All components are imported from components/ folder
"""

# ── IMPORTS: Flask tools ──────────────────────────────────────────────────────
# Flask   : creates the web application
# request : reads incoming JSON data from the extension
# jsonify : converts Python dict to JSON response
from flask import Flask, request, jsonify

# ── IMPORTS: CORS ─────────────────────────────────────────────────────────────
# CORS allows the VS Code extension (running on a different port)
# to send requests to our Flask server without being blocked.
from flask_cors import CORS

# ── IMPORTS: All pipeline components ──────────────────────────────────────────
# Each import brings in one component from the components/ folder.
# Each component maps to one box in the C4 diagram.
from components.detection_coordinator  import DetectionCoordinator   # validates input
from components.source_code_retriever  import SourceCodeRetriever    # retrieves code
from components.context_retriever      import ContextRetriever       # builds prompt
from components.model_retriever        import ModelRetriever         # gets model config
from components.smell_detection_engine import SmellDetectionEngine   # runs LLM
from components.results_publisher      import ResultsPublisher       # formats output
from components.feedback_collector     import FeedbackCollector      # stores feedback


# ── CREATE FLASK APP ──────────────────────────────────────────────────────────
# Flask(__name__) creates the application.
# __name__ tells Flask the name of the current file.
app = Flask(__name__)

# Enable CORS so the VS Code extension can call this server
CORS(app)


# ── INSTANTIATE ALL COMPONENTS ────────────────────────────────────────────────
# We create one instance of each component when the server starts.
# These instances are reused for every request — we do not create
# new instances on every request (that would be wasteful).
#
# Think of these as the "workers" standing ready to process requests.

# C4: DetectionCoordinator [Component: python]
detection_coordinator = DetectionCoordinator()

# C4: SourceCodeRetriever [Component: python]
source_code_retriever = SourceCodeRetriever()

# C4: ContextRetriever [Component: python]
context_retriever = ContextRetriever()

# C4: ModelRetriever [Component: python]
model_retriever = ModelRetriever()

# C4: SmellDetectionEngine [Component: python]
smell_detection_engine = SmellDetectionEngine()

# C4: ResultsPublisher [Container: python]
results_publisher = ResultsPublisher()

# C4: FeedbackCollector [Container: python]
feedback_collector = FeedbackCollector()


# ════════════════════════════════════════════════════════
#  ROUTE 1: Health Check
#  C4: Not explicitly in diagram — utility endpoint
# ════════════════════════════════════════════════════════

@app.route("/health", methods=["GET"])
def health():
    """
    Simple health check endpoint.

    Purpose:
    --------
    Lets us verify the backend is running without triggering
    any analysis. Open http://localhost:5000/health in a browser
    to confirm the server is alive.

    Returns:
    --------
    JSON: { "status": "ok", "system": "SMASH" }
    """
    return jsonify({
        "status": "ok",
        "system": "SMASH — Software Architecture Smell Hunter"
    })


# ════════════════════════════════════════════════════════
#  ROUTE 2: Analyze
#  C4: Trigger Receiver [Component: python]
#  "Receives trigger request on code change or pull request"
# ════════════════════════════════════════════════════════

@app.route("/analyze", methods=["POST"])
def analyze():
    """
    Main smell detection endpoint — the Trigger Receiver.

    This route is called by the VS Code extension every time
    the developer presses Ctrl+S on a Java file.

    Request body (JSON):
    --------------------
    {
        "class_name": "Rtl2832Frontend",
        "code": "public class Rtl2832Frontend { ... }"
    }

    Response (JSON):
    ----------------
    {
        "smells"    : [ { smell fields... }, ... ],
        "summary"   : "2 smell(s) detected in Rtl2832Frontend: ...",
        "class_name": "Rtl2832Frontend",
        "count"     : 2
    }

    C4 Reference: Trigger Receiver [Component: python]
    """

    # ── Log that a trigger was received ───────────────────────────────────────
    print("\n" + "="*60)
    print("[TriggerReceiver] Trigger received from VS Code extension")
    print("="*60)

    # ── Read the raw JSON from the request ────────────────────────────────────
    # force=True means: even if Content-Type header is missing, try to parse as JSON
    raw_data = request.get_json(force=True)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 1: Detection Coordinator
    #  Validates and normalises the trigger payload
    # ════════════════════════════════════════════════════════
    try:
        print("\n[Pipeline] Step 1: Detection Coordinator")
        detection_request = detection_coordinator.validate_and_build(raw_data)
    except ValueError as e:
        # If validation fails, return a 400 Bad Request error immediately
        # No point running the rest of the pipeline with bad data
        print(f"[DetectionCoordinator] Validation failed: {e}")
        return jsonify({"error": str(e)}), 400

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 2: Source Code Retriever
    #  Retrieves and logs source code artifacts
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 2: Source Code Retriever")
    detection_request = source_code_retriever.retrieve(detection_request)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 3: Context Retriever
    #  Fetches architecture context and builds the prompt
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 3: Context Retriever")
    detection_request = context_retriever.retrieve_and_build_prompt(detection_request)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 4: Model Retriever
    #  Gets the LLM model configuration
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 4: Model Retriever")
    detection_request, model_config = model_retriever.retrieve_model_config(detection_request)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 5: Smell Detection Engine
    #  Calls the LLM and gets smell results
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 5: Smell Detection Engine")
    try:
        detection_request = smell_detection_engine.run_detection(
            detection_request,
            model_config
        )
    except Exception as e:
        # If LLM call fails for any reason — Ollama offline, timeout, etc.
        # We catch it here and return a clear error message to the extension.
        import requests as req_lib
        if isinstance(e, req_lib.exceptions.ConnectionError):
            # Ollama is not running
            return jsonify({
                "error": (
                    "Cannot connect to Ollama. "
                    "Make sure Ollama is running — open it from the Start menu."
                )
            }), 503
        elif isinstance(e, req_lib.exceptions.Timeout):
            # Ollama took too long
            return jsonify({
                "error": "Ollama request timed out. The model may still be loading. Try again."
            }), 504
        else:
            # Any other error
            return jsonify({"error": f"Smell detection failed: {str(e)}"}), 500

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 6: Results Publisher
    #  Formats and returns results to VS Code extension
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 6: Results Publisher")
    result = results_publisher.publish(detection_request)

    print("\n" + "="*60)
    print(f"[Pipeline] Complete. Sending results to VS Code extension.")
    print("="*60 + "\n")

    # Return the formatted result as JSON to the VS Code extension
    return jsonify(result)


# ════════════════════════════════════════════════════════
#  ROUTE 3: Feedback
#  C4: FeedbackCollector [Container: python]
#  "Receives developer feedback from Detection Trigger"
# ════════════════════════════════════════════════════════

@app.route("/feedback", methods=["POST"])
def feedback():
    """
    Feedback collection endpoint.

    Receives developer feedback on smell detection results
    and stores it in the ExpertKnowledgeBase for future
    prompt improvement and expert review.

    Request body (JSON):
    --------------------
    {
        "class_name"     : "Rtl2832Frontend",
        "smell_category" : "God Class",
        "is_correct"     : true,
        "comment"        : "Agreed, this class is too large"
    }

    C4 Reference: FeedbackCollector [Container: python]
    """

    print("[TriggerReceiver] Feedback received from VS Code extension")
    raw_data = request.get_json(force=True)

    try:
        result = feedback_collector.collect(raw_data)
        return jsonify(result)
    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        return jsonify({"error": f"Failed to store feedback: {str(e)}"}), 500


@app.route("/feedback", methods=["GET"])
def get_feedback():
    """
    Returns all stored feedback entries.
    Used by the Architecture Expert to review and validate.

    C4 Reference: Architecture Expert [Practitioner]
    """
    all_feedback = feedback_collector.get_all_feedback()
    return jsonify({
        "feedback": all_feedback,
        "total"   : len(all_feedback)
    })


# ════════════════════════════════════════════════════════
#  ENTRY POINT — Start the Flask server
# ════════════════════════════════════════════════════════

if __name__ == "__main__":
    # Print startup banner so we know the server is running
    print("=" * 60)
    print("  SMASH Backend — Software Architecture Smell Hunter")
    print("  Starting on http://localhost:5000")
    print("=" * 60)
    print("  Components loaded:")
    print("    ✓ Detection Coordinator")
    print("    ✓ Source Code Retriever")
    print("    ✓ Context Retriever")
    print("    ✓ Model Retriever")
    print("    ✓ Smell Detection Engine")
    print("    ✓ Results Publisher")
    print("    ✓ Feedback Collector")
    print("=" * 60)

    # app.run() starts the Flask development server
    # host="0.0.0.0" : listen on all network addresses (not just localhost)
    # port=5000       : run on port 5000
    # debug=False     : do not auto-restart on code changes
    app.run(host="0.0.0.0", port=5000, debug=False)
