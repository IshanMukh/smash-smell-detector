"""
========================================================
  COMPONENT: Detection Coordinator
  C4 Diagram Reference: DetectionCoordinator [Component: python]
========================================================

What is this component?
------------------------
The Detection Coordinator is the FIRST component that runs
inside the SMASH system after the Trigger Receiver receives
a request from the VS Code extension.

Its job from the C4 diagram:
"Validates and Normalises trigger payloads (repo, id of trigger event)"

In plain English:
-----------------
When the VS Code extension sends us a request, the raw data
comes in as a JSON dictionary. The Detection Coordinator:

  1. VALIDATES  — checks that all required fields are present
                  and not empty. If something is wrong, it
                  raises an error immediately before any
                  expensive LLM processing happens.

  2. NORMALISES — cleans up the data. For example:
                  - strips extra whitespace from strings
                  - provides default values for missing fields
                  - ensures class_name is never empty
                  - ensures language is always a non-empty string

  3. CREATES    — builds a DetectionRequest object from the
                  cleaned data, ready to pass to the next
                  component in the pipeline.

Why is validation important?
-----------------------------
Without validation, bad data could travel all the way to the
LLM and cause confusing errors. Catching problems early at
the entry point gives clearer error messages and saves time.

Position in pipeline:
---------------------
VS Code Extension
    → Trigger Receiver (/analyze route in app.py)
    → Detection Coordinator  ← YOU ARE HERE
    → SourceCodeRetriever
    → ContextRetriever
    → ModelRetriever
    → SmellDetectionEngine
    → ResultsPublisher
"""

# ── Imports ───────────────────────────────────────────────────────────────────
# We import the DetectionRequest data class from our data folder.
# This is the object we will create and return after validation.
from data.detection_request import DetectionRequest


# ── MODULE: Detection Coordinator ─────────────────────────────────────────────
class DetectionCoordinator:
    """
    Validates and normalises incoming trigger payloads.
    Creates a DetectionRequest object for the pipeline.

    C4 Reference: DetectionCoordinator [Component: python]
    """

    def validate_and_build(self, raw_data: dict) -> DetectionRequest:
        """
        Takes the raw JSON dictionary sent by the VS Code extension,
        validates it, cleans it up, and returns a DetectionRequest.

        Parameters:
        -----------
        raw_data : dict
            The raw JSON body received from the VS Code extension.
            Expected format:
            {
                "class_name": "Rtl2832Frontend",
                "code"      : "public class Rtl2832Frontend { ... }",
                "language"  : "Java"
            }

        Returns:
        --------
        DetectionRequest
            A clean, validated data object ready for the pipeline.

        Raises:
        -------
        ValueError
            If required fields are missing or empty.
        """

        # ── Step 1: Check that we received something ──────────────────────────
        # raw_data could be None if the extension sent an empty body.
        # We check for this before trying to access any fields.
        if not raw_data:
            raise ValueError(
                "Empty request body received. "
                "The VS Code extension must send JSON with 'code' field."
            )

        # ── Step 2: Check that 'code' field exists ────────────────────────────
        # 'code' is the most critical field — without it there is nothing to analyse.
        # We check for its presence explicitly so the error message is clear.
        if "code" not in raw_data:
            raise ValueError(
                "Missing 'code' field in request. "
                "The source code must be included in the request."
            )

        # ── Step 3: Extract and normalise the code ────────────────────────────
        # .strip() removes any leading/trailing whitespace or newlines
        # that might have been accidentally included.
        code = raw_data["code"].strip()

        # ── Step 4: Check that code is not empty after stripping ──────────────
        if not code:
            raise ValueError(
                "Source code is empty. "
                "Please ensure the file has content before saving."
            )

        # ── Step 5: Extract and normalise the class name ──────────────────────
        # .get() is used instead of [] so we can provide a default value
        # "UnknownClass" if class_name was not sent by the extension.
        # The 'or "UnknownClass"' handles the case where class_name is
        # an empty string after stripping.
        class_name = raw_data.get("class_name", "UnknownClass").strip() or "UnknownClass"

        # ── Step 6: Extract and normalise the language ────────────────────────
        # NEW FIELD — this is what makes SMASH language-independent.
        #
        # The VS Code extension detects the language automatically using
        # VS Code's built-in language detection (doc.languageId) and maps
        # it to a human-readable name (e.g. "java" → "Java").
        #
        # If the extension does not send a language (e.g. older version),
        # we default to "Unknown" so the pipeline still works gracefully.
        #
        # .get("language", "Unknown") → use "Unknown" if field is missing
        # .strip()                    → remove extra whitespace
        # or "Unknown"                → use "Unknown" if empty string after strip
        language = raw_data.get("language", "Unknown").strip() or "Unknown"

        # ── Step 7: Extract and normalise the file path ──────────────────────
        # The file path is sent by the VS Code extension so the backend knows
        # which context.txt to read for this specific file.
        # Example: "C:/Users/.../test-java-files/akhq_7201.java"
        #          → used to find akhq_7201_context.txt in the same folder
        file_path = raw_data.get("file_path", "unknown").strip() or "unknown"

        # ── Step 8: Log what was received ─────────────────────────────────────

        # ── Step 9: Build and return the DetectionRequest ─────────────────────
        # We now have clean, validated data. We wrap it into a DetectionRequest
        # object and return it. The other fields (prompt, model_name, etc.)
        # will be filled in by the subsequent components in the pipeline.
        return DetectionRequest(
            code=code,
            class_name=class_name,
            language=language,
            file_path=file_path
        )
