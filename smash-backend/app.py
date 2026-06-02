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
Every time the developer presses Ctrl+S on a supported file:

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
  RunLogger             → saves run info to a timestamped CSV
      ↓
  VS Code Extension shows notification to developer

C4 Components used in this file:
---------------------------------
  - Trigger Receiver    : the /analyze route
  - Feedback Collector  : the /feedback route
  - All components are imported from components/ folder
"""

# ── IMPORTS ───────────────────────────────────────────────────────────────────
import os
import time

from flask import Flask, request, jsonify
from flask_cors import CORS

from components.detection_coordinator  import DetectionCoordinator
from components.source_code_retriever  import SourceCodeRetriever
from components.context_retriever      import ContextRetriever
from components.model_retriever        import ModelRetriever
from components.smell_detection_engine import SmellDetectionEngine
from components.results_publisher      import ResultsPublisher
from components.feedback_collector     import FeedbackCollector
from components.run_logger             import RunLogger


# ── CREATE FLASK APP ──────────────────────────────────────────────────────────
app = Flask(__name__)
CORS(app)


# ── INSTANTIATE ALL COMPONENTS ────────────────────────────────────────────────
# One instance of each component created at startup and reused per request.

detection_coordinator  = DetectionCoordinator()
source_code_retriever  = SourceCodeRetriever()
context_retriever      = ContextRetriever()
model_retriever        = ModelRetriever()
smell_detection_engine = SmellDetectionEngine()
results_publisher      = ResultsPublisher()
feedback_collector     = FeedbackCollector()
run_logger             = RunLogger()


# ════════════════════════════════════════════════════════
#  ROUTE 1: Health Check
# ════════════════════════════════════════════════════════

@app.route("/health", methods=["GET"])
def health():
    """
    Simple health check endpoint.
    Open http://localhost:5000/health in a browser to confirm
    the server is alive.
    """
    return jsonify({
        "status": "ok",
        "system": "SMASH — Software Architecture Smell Hunter"
    })


# ════════════════════════════════════════════════════════
#  ROUTE 2: Analyze
#  C4: Trigger Receiver [Component: python]
# ════════════════════════════════════════════════════════

@app.route("/analyze", methods=["POST"])
def analyze():
    """
    Main smell detection endpoint — the Trigger Receiver.

    Called by the VS Code extension every time the developer
    presses Ctrl+S on a supported source file.

    Request body (JSON):
    --------------------
    {
        "class_name" : "Rtl2832Frontend",
        "code"       : "public class Rtl2832Frontend { ... }",
        "language"   : "Java",
        "file_path"  : "C:/path/to/Rtl2832Frontend.java"
    }

    Response (JSON):
    ----------------
    {
        "smells"    : [ { smell fields... }, ... ],
        "summary"   : "2 smell(s) detected in ...",
        "class_name": "Rtl2832Frontend",
        "count"     : 2
    }
    """

    print("\n" + "="*60)
    print("[TriggerReceiver] Trigger received from VS Code extension")
    print("="*60)

    # ── Start the run timer ───────────────────────────────────────────────────
    # We measure from the moment the request arrives to when results are sent.
    run_start = time.time()

    raw_data = request.get_json(force=True)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 1: Detection Coordinator
    # ════════════════════════════════════════════════════════
    try:
        print("\n[Pipeline] Step 1: Detection Coordinator")
        detection_request = detection_coordinator.validate_and_build(raw_data)
    except ValueError as e:
        print(f"[DetectionCoordinator] Validation failed: {e}")
        return jsonify({"error": str(e)}), 400

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 2: Source Code Retriever
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 2: Source Code Retriever")
    detection_request = source_code_retriever.retrieve(detection_request)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 3: Context Retriever
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 3: Context Retriever")
    detection_request = context_retriever.retrieve_and_build_prompt(detection_request)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 4: Model Retriever
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 4: Model Retriever")
    detection_request, model_config = model_retriever.retrieve_model_config(detection_request)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 5: Smell Detection Engine
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 5: Smell Detection Engine")
    try:
        detection_request = smell_detection_engine.run_detection(
            detection_request,
            model_config
        )
    except Exception as e:
        import requests as req_lib
        if isinstance(e, req_lib.exceptions.ConnectionError):
            return jsonify({
                "error": (
                    "Cannot connect to Ollama. "
                    "Make sure Ollama is running — open it from the Start menu."
                )
            }), 503
        elif isinstance(e, req_lib.exceptions.Timeout):
            return jsonify({
                "error": "Ollama request timed out. The model may still be loading. Try again."
            }), 504
        else:
            return jsonify({"error": f"Smell detection failed: {str(e)}"}), 500

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 6: Results Publisher
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 6: Results Publisher")
    result = results_publisher.publish(detection_request)

    # ════════════════════════════════════════════════════════
    #  PIPELINE STEP 7: Run Logger
    #  Saves run metadata and results to a timestamped CSV
    # ════════════════════════════════════════════════════════
    print("\n[Pipeline] Step 7: Run Logger")
    run_duration = time.time() - run_start
    project_name = os.path.basename(detection_request.file_path or "unknown")

    run_logger.log_run(
        project_name      = project_name,
        language          = detection_request.language,
        model_name        = detection_request.model_name or "unknown",
        smells            = result.get("smells", []),
        run_duration_secs = run_duration,
        analysed_classes  = [detection_request.class_name]
    )

    print("\n" + "="*60)
    print(f"[Pipeline] Complete. Sending results to VS Code extension.")
    print("="*60 + "\n")

    return jsonify(result)


# ════════════════════════════════════════════════════════
#  ROUTE 3: Feedback — POST
#  C4: FeedbackCollector [Container: python]
# ════════════════════════════════════════════════════════

@app.route("/feedback", methods=["POST"])
def feedback():
    """
    Receives developer feedback on smell detection results.
    Appends it to the context.txt for that source file.

    Request body (JSON):
    --------------------
    {
        "file_path"      : "C:/path/to/akhq_7201.java",
        "class_name"     : "RecordRepository",
        "smell_category" : "God Class",
        "verdict"        : "accept",
        "comment"        : "Agreed, this class does too many things"
    }
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


# ════════════════════════════════════════════════════════
#  ROUTE 4: Feedback — GET
# ════════════════════════════════════════════════════════

@app.route("/feedback", methods=["GET"])
def get_feedback():
    """
    Returns all stored feedback entries.
    Used by the Architecture Expert to review and validate.
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
    print("=" * 60)
    print("  SMASH Backend — Software Architecture Smell Hunter")
    print("  Starting on http://localhost:5000")
    print("=" * 60)
    print("  Components loaded:")
    print("    checkmark Detection Coordinator")
    print("    checkmark Source Code Retriever")
    print("    checkmark Context Retriever")
    print("    checkmark Model Retriever")
    print("    checkmark Smell Detection Engine")
    print("    checkmark Results Publisher")
    print("    checkmark Feedback Collector")
    print("    checkmark Run Logger")
    print("=" * 60)

    app.run(host="0.0.0.0", port=5000, debug=False)