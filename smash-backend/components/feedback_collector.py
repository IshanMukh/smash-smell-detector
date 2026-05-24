"""
========================================================
  COMPONENT: Feedback Collector
  C4 Diagram Reference: FeedbackCollector [Container: python]
========================================================

What is this component?
------------------------
The Feedback Collector receives developer feedback on the
smell detection results and stores it for future use.

Its job from the C4 diagram:
"Receives developer feedback from Detection Trigger"
"sends feedback for knowledge-base update"
"sends reviewed expert knowledge"

In plain English:
-----------------
After the developer sees the smell detection results in VS Code,
they may want to provide feedback:
  - "This smell is correct" (approve)
  - "This is a false positive" (reject)
  - "The severity should be higher" (adjust)

This feedback is valuable because:
  1. It helps improve the prompt over time (ExpertKnowledgeBase)
  2. It provides data for evaluating detection quality
  3. An Architecture Expert can review it and validate

In our current version:
-----------------------
The feedback is collected via a /feedback endpoint and
saved to a local JSON file (feedback_store.json).
In a full system this would connect to the ExpertKnowledgeBase
storage shown in the C4 diagram.

Position in pipeline:
---------------------
This component is SEPARATE from the main analysis pipeline.
It runs when the developer clicks a feedback button
(future feature in VS Code extension).

VS Code Extension → POST /feedback → FeedbackCollector
                                       → saves to file
                                       → Architecture Expert reviews
"""

# ── Imports ───────────────────────────────────────────────────────────────────
import json      # for reading and writing JSON files
import os        # for checking if the feedback file exists
from datetime import datetime  # for timestamping each feedback entry


# ── EXPERT KNOWLEDGE BASE ─────────────────────────────────────────────────────
# In the C4 diagram: ExpertKnowledgeBase [Storage: storage]
# "stores the reusable architectural knowledge that has been reviewed by an expert"
#
# In our implementation this is a simple JSON file.
# Each entry in the file is one piece of developer feedback.
FEEDBACK_STORE_PATH = "feedback_store.json"


# ── MODULE: Feedback Collector ────────────────────────────────────────────────
class FeedbackCollector:
    """
    Collects and stores developer feedback on smell detection results.
    Feeds into the ExpertKnowledgeBase for future prompt improvement.

    C4 Reference: FeedbackCollector [Container: python]
    Also uses:    ExpertKnowledgeBase [Storage: storage]
    """

    def collect(self, feedback_data: dict) -> dict:
        """
        Receives feedback from the developer and stores it.

        Parameters:
        -----------
        feedback_data : dict
            Expected format:
            {
                "class_name"     : "Rtl2832Frontend",
                "smell_category" : "God Class",
                "is_correct"     : true,
                "comment"        : "Agreed, this class is too large"
            }

        Returns:
        --------
        dict
            Confirmation that feedback was stored.
        """

        # ── Step 1: Validate the feedback data ────────────────────────────────
        if not feedback_data:
            raise ValueError("Empty feedback data received.")

        required_fields = ["class_name", "smell_category", "is_correct"]
        for field in required_fields:
            if field not in feedback_data:
                raise ValueError(f"Missing required field in feedback: '{field}'")

        # ── Step 2: Add a timestamp to the feedback ───────────────────────────
        # We record when the feedback was given so we can track it over time.
        feedback_entry = {
            "timestamp"      : datetime.now().isoformat(),
            "class_name"     : feedback_data["class_name"],
            "smell_category" : feedback_data["smell_category"],
            "is_correct"     : feedback_data["is_correct"],
            "comment"        : feedback_data.get("comment", ""),
            "reviewed"       : False  # Architecture Expert has not reviewed yet
        }

        # ── Step 3: Load existing feedback from the store ─────────────────────
        # If the feedback file exists, read it.
        # If it does not exist yet, start with an empty list.
        if os.path.exists(FEEDBACK_STORE_PATH):
            with open(FEEDBACK_STORE_PATH, "r") as f:
                all_feedback = json.load(f)
        else:
            all_feedback = []

        # ── Step 4: Append the new feedback entry ─────────────────────────────
        all_feedback.append(feedback_entry)

        # ── Step 5: Save back to the file ─────────────────────────────────────
        # indent=2 makes the JSON file human-readable (nicely formatted)
        with open(FEEDBACK_STORE_PATH, "w") as f:
            json.dump(all_feedback, f, indent=2)

        # ── Step 6: Log what was stored ───────────────────────────────────────
        print(f"[FeedbackCollector] Feedback stored for {feedback_entry['class_name']}")
        print(f"  Smell    : {feedback_entry['smell_category']}")
        print(f"  Correct  : {feedback_entry['is_correct']}")
        print(f"  Total feedback entries: {len(all_feedback)}")

        # ── Step 7: Return confirmation ───────────────────────────────────────
        return {
            "message"  : "Feedback stored successfully",
            "timestamp": feedback_entry["timestamp"],
            "total"    : len(all_feedback)
        }

    def get_all_feedback(self) -> list:
        """
        Returns all stored feedback entries.
        Used by the Architecture Expert to review and validate.

        Returns:
        --------
        list
            All feedback entries from the store.
            Empty list if no feedback has been collected yet.
        """

        if not os.path.exists(FEEDBACK_STORE_PATH):
            return []

        with open(FEEDBACK_STORE_PATH, "r") as f:
            return json.load(f)
