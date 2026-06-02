"""
========================================================
  DATA OBJECT: DetectionRequest
  C4 Diagram Reference: DetectionRequest [Data: python]
========================================================

What is this file?
------------------
This file defines the DetectionRequest data class.
In the C4 diagram, DetectionRequest is the structured
data object that travels through the entire SMASH pipeline.

Think of it as a "package" or "envelope" that carries
all the information needed to perform one smell analysis:
  - The source code
  - The class name
  - The programming language  ← NEW (was always assumed Java before)
  - The architecture context (prompts)
  - The model configuration (which LLM to use)

Why do we need this?
--------------------
In the old code (single app.py), data was passed around
as loose variables between functions. That is messy and
hard to explain.

By wrapping everything into one DetectionRequest object,
each component in the pipeline only needs to receive and
pass this one object. Clean, clear, and traceable.

Flow in the pipeline:
---------------------
DetectionClient (VS Code)
    → sends raw JSON
    → Detection Coordinator creates a DetectionRequest
    → DetectionRequest travels through all components
    → ResultsPublisher reads it and sends back results
"""

# ── What is a dataclass? ──────────────────────────────────────────────────────
# A dataclass is a special Python class designed purely to hold data.
# Instead of writing __init__, __repr__ etc. manually, the @dataclass
# decorator generates all of that automatically for us.
# It is the cleanest way to define a data container in Python.
from dataclasses import dataclass, field
from typing import Optional


# ── DetectionRequest ──────────────────────────────────────────────────────────
# This is the main data object used across all components.
# Every field maps directly to what the C4 diagram shows inside
# the DetectionRequest box: "Code snapshot, architecture context,
# prompt, model configuration"
@dataclass
class DetectionRequest:
    """
    Structured data object that carries all information
    needed for one complete smell detection run.

    Created by: Detection Coordinator
    Used by:    SourceCodeRetriever, ContextRetriever,
                ModelRetriever, SmellDetectionEngine,
                ResultsPublisher
    """

    # ── Code snapshot ─────────────────────────────────────────────────────────
    # The full source code sent by the VS Code extension.
    # Example: "public class Rtl2832Frontend { ... }"
    code: str

    # ── Class name ────────────────────────────────────────────────────────────
    # The primary class name extracted from the code.
    # Example: "Rtl2832Frontend"
    class_name: str

    # ── Programming language ──────────────────────────────────────────────────
    # The programming language of the source file.
    # Sent by the VS Code extension using VS Code's built-in language detection.
    # Example: "Java", "Python", "C#", "C++", "TypeScript", "JavaScript"
    #
    # WHY THIS FIELD EXISTS:
    # The old version hardcoded "Java" everywhere — in the prompt, in the
    # source code metadata extraction, and in the extension's file filter.
    # Now that we support multiple languages, we carry the language through
    # the pipeline so every component can use it where needed.
    #
    # DEFAULT: "Unknown" — if the extension does not send this field,
    # the DetectionCoordinator will default to "Unknown" and the LLM
    # will still attempt analysis based on the code content alone.
    language: str = "Unknown"

    # ── File path ─────────────────────────────────────────────────────────────────
    # The absolute path of the file being analysed.
    # Used by ContextRetriever to find the correct context.txt for this file.
    # Example: "C:/Users/KIIT0001/Desktop/smash-project-v2/test-java-files/akhq_7201.java"
    file_path: str = "unknown"
    # ── Architecture context ──────────────────────────────────────────────────
    # The filled-in prompt that will be sent to the LLM.
    # Populated by: ContextRetriever
    # Starts empty — gets filled in as the pipeline progresses.
    prompt: Optional[str] = None

    # ── Model configuration ───────────────────────────────────────────────────
    # The name of the LLM model to use for this analysis.
    # Populated by: ModelRetriever
    # Example: "qwen2.5:latest"
    model_name: Optional[str] = None

    # ── Raw LLM response ──────────────────────────────────────────────────────
    # The raw text response returned by Ollama after analysis.
    # Populated by: SmellDetectionEngine
    raw_llm_response: Optional[str] = None

    # ── Parsed smell results ──────────────────────────────────────────────────
    # The final parsed list of smell dictionaries.
    # Populated by: SmellDetectionEngine after parsing raw_llm_response
    # Example: [{"smell_category": "God Class", "smell_severity": 7, ...}]
    smells: list = field(default_factory=list)
