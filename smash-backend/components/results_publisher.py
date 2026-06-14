"""
========================================================
  COMPONENT: Results Publisher
  C4 Diagram Reference: ResultsPublisher [Container: python]
========================================================

What is this component?
------------------------
The Results Publisher is the LAST component in the pipeline.
It formats the smell results and sends them back to the
VS Code extension (the DetectionClient).

Its job from the C4 diagram:
"Format results and Sends back to Detection Trigger so
editor/CI can show warnings and suggestions to developers"

In plain English:
-----------------
By the time this component runs, the DetectionRequest has
the full list of detected smells in request.smells.

This component:
  1. Takes the smells from the DetectionRequest
  2. Formats them into a clean JSON response
  3. Adds a human-readable summary
  4. Returns the formatted response to the /analyze route
     which then sends it back to the VS Code extension

The VS Code extension then reads this response and shows
the notification popup to the developer.

Position in pipeline:
---------------------
SmellDetectionEngine
    → ResultsPublisher  ← YOU ARE HERE
    → Back to /analyze route in app.py
    → Back to VS Code extension
    → Notification shown to developer
"""

# ── Imports ───────────────────────────────────────────────────────────────────
from data.detection_request import DetectionRequest


# ── MODULE: Results Publisher ─────────────────────────────────────────────────
class ResultsPublisher:
    """
    Formats and publishes smell detection results back to the editor.

    C4 Reference: ResultsPublisher [Container: python]
    """

    def publish(self, request: DetectionRequest) -> dict:
        """
        Formats the DetectionRequest results into a clean
        JSON-ready dictionary to send back to VS Code.

        Parameters:
        -----------
        request : DetectionRequest
            Must have 'smells' and 'class_name' populated
            by SmellDetectionEngine.

        Returns:
        --------
        dict
            A dictionary containing:
            - smells     : list of smell dictionaries
            - summary    : human readable summary string
            - class_name : the analysed class name
            - count      : number of smells found
        """

        # ── Step 1: Log what we are publishing ────────────────────────────────
        print(f"[ResultsPublisher] Formatting and publishing results")
        print(f"  Class : {request.class_name}")
        print(f"  Smells: {len(request.smells)}")

        # ── Step 2: Build a human readable summary ────────────────────────────
        # This summary is logged in the terminal and included in the response.
        # It gives a quick overview of what was found without reading all details.
        summary = self._build_summary(request.class_name, request.smells)
        print(f"[ResultsPublisher] {summary}")

        # ── Step 3: Build and return the formatted response dictionary ─────────
        # This dictionary will be converted to JSON by Flask's jsonify()
        # in the /analyze route and sent back to the VS Code extension.
        #
        # The VS Code extension reads:
        #   response["smells"] → to show the notification popup
        #
        # The other fields (summary, class_name, count) are bonus information
        # that could be used in future versions of the extension.
        return {
            "smells"    : request.smells,      # The main result — list of smell dicts
            "summary"   : summary,             # Human readable summary string
            "class_name": request.class_name,  # Which class was analysed
            "count"     : len(request.smells)  # How many smells were found
        }
        
    # _build_summary is a private helper method (underscore prefix convention).
    # It is only called by publish() above and should not be called directly
    # from outside this class. Keeping it private makes the public interface
    # of ResultsPublisher clean — only publish() is meant to be used externally.   
    def _build_summary(self, class_name: str, smells: list) -> str:
        """
        Builds a one-line human readable summary of the results.

        Parameters:
        -----------
        class_name : str
            The name of the analysed Java class.
        smells     : list
            The list of detected smell dictionaries.

        Returns:
        --------
        str
            A summary string. Examples:
            "No smells detected in Rtl2832Frontend"
            "2 smell(s) detected in Rtl2832Frontend: God Class (7/10) | Hub-Like Dependency (6/10)"
        """

        # ── No smells case ────────────────────────────────────────────────────
        if not smells:
            return f"No architectural smells detected in {class_name}"

        # ── Smells found case ─────────────────────────────────────────────────
        # Build a list of "SmellName (severity/10)" strings for each smell.
        # Then join them with " | " separator.
        smell_descriptions = [
            f"{s.get('smell_category', 'Unknown')} ({s.get('smell_severity', '?')}/10)"
            for s in smells
        ]

        return (
            f"{len(smells)} smell(s) detected in {class_name}: "
            f"{' | '.join(smell_descriptions)}"
        )
