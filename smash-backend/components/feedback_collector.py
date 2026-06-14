"""
========================================================
  COMPONENT: Feedback Collector
  C4 Diagram Reference: FeedbackCollector [Container: python]
========================================================

What is this component?
------------------------
The Feedback Collector receives developer feedback on the
smell detection results and stores it in the context.txt
file for that specific source file.

Its job from the C4 diagram:
"Receives developer feedback from Detection Trigger"
"sends feedback for knowledge-base update"

In plain English:
-----------------
After the developer sees the smell detection results in VS Code,
they can provide feedback per smell:
  - Accept  — "this smell is correctly detected"
  - Discard — "this is a false positive"
  - Comment — mandatory explanation of their decision

This feedback is appended to the context.txt file for that
specific source file. The next time the same file is analysed,
the ContextRetriever reads this feedback and enriches the LLM
prompt with it, improving detection quality over time.

Position in pipeline:
---------------------
This component is SEPARATE from the main analysis pipeline.
It runs when the developer submits feedback in the VS Code panel.

VS Code Extension → POST /feedback → FeedbackCollector
                                       → appends to context.txt
                                       → used by ContextRetriever next run
"""

# ── Imports ───────────────────────────────────────────────────────────────────
import os                        # for file path operations
from datetime import datetime    # for timestamping each feedback entry


# ── MODULE: Feedback Collector ────────────────────────────────────────────────
class FeedbackCollector:
    """
    Collects developer feedback on smell detection results
    and appends it to the context.txt for that source file.

    C4 Reference: FeedbackCollector [Container: python]
    """

    def collect(self, feedback_data: dict) -> dict:
        """
        Validates feedback and appends it to the context.txt
        file for the specific source file that was analysed.

        Parameters:
        -----------
        feedback_data : dict
            Expected fields:
              file_path      — absolute path of the source file
              class_name     — the class where the smell was detected
              smell_category — the smell type (e.g. God Class)
              verdict        — "accept" or "discard"
              comment        — mandatory developer comment

        Returns:
        --------
        dict
            Confirmation with status, file path, and the entry written.

        Raises:
        -------
        ValueError
            If any required field is missing, empty, or verdict is invalid.
        """

        # ── Step 1: Validate all required fields ──────────────────────────────
        # Every field is required — the extension enforces comment before submit
        # but we double-check here on the backend as well.
        required = ["class_name", "smell_category", "verdict", "comment", "file_path"]
        for field in required:
            if field not in feedback_data or not str(feedback_data[field]).strip():
                raise ValueError(f"Missing or empty required field: '{field}'")

        # ── Step 2: Validate verdict value ────────────────────────────────────
        verdict = feedback_data["verdict"].lower()
        if verdict not in ("accept", "discard"):
            raise ValueError("verdict must be either 'accept' or 'discard'")

        # ── Step 3: Build the feedback entry string ───────────────────────────
        # This is the line that gets appended to context.txt.
        # Format matches what ContextRetriever looks for ("verdict:" keyword).
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        entry = (
            f"\n[{timestamp}] | "
            f"class: {feedback_data['class_name']} | "
            f"smell: {feedback_data['smell_category']} | "
            f"verdict: {verdict} | "
            f"comment: {feedback_data['comment'].strip()}"
        )

        # ── Step 4: Find the context.txt path for this source file ────────────
        # context.txt lives in the same folder as the source file.
        # Example: akhq_7201.java → akhq_7201_context.txt
        file_path    = feedback_data["file_path"]
        source_dir   = os.path.dirname(file_path)
        source_name  = os.path.splitext(os.path.basename(file_path))[0]
        context_path = os.path.join(source_dir, f"{source_name}_context.txt")

        # ── Step 5: Append the entry to context.txt ───────────────────────────
        # "a" mode appends without overwriting existing content.
        # If context.txt does not exist yet, Python creates it automatically.
        with open(context_path, "a", encoding="utf-8") as f:
            f.write(entry)

        print(f"[FeedbackCollector] Feedback appended to: {context_path}")

        return {
            "status" : "saved",
            "file"   : context_path,
            "entry"  : entry.strip()
        }
    # get_all_feedback() is kept here because app.py has a GET /feedback route
    # that calls it. Removing the method would break that route.
    # In the future this could be updated to aggregate feedback from all
    # context.txt files across the project if needed.
    
    def get_all_feedback(self) -> list:
        """
        Previously read from feedback_store.json.
        Feedback is now stored per-file in context.txt files
        alongside each source file being analysed.

        Returns an empty list — feedback is no longer centralised.
        To read feedback for a specific file, read its context.txt directly.
        """
        return []