"""
========================================================
  COMPONENT: Context Retriever
  C4 Diagram Reference: ContextRetriever [Component: python]
========================================================

What does this component do?
-----------------------------
This component is responsible for building the final instruction
that gets sent to the LLM. It does this by combining two sources:

  1. prompt.txt  — the base analysis prompt containing smell
                   definitions, detection strategy, and JSON
                   output instructions. Always used.

  2. context.txt — a file specific to the source file being
                   analysed. Contains the Architecture Decision
                   Record (ADR) for that project and any developer
                   feedback from previous analysis runs.
                   Only included if feedback exists.

First run (no feedback yet):
  final instruction = prompt.txt filled with {language}, {class_name}, {code}

Subsequent runs (feedback exists in context.txt):
  final instruction = prompt.txt + context.txt filled with placeholders

Position in pipeline:
---------------------
SourceCodeRetriever
    → ContextRetriever  ← YOU ARE HERE
    → ModelRetriever
"""

import os
from data.detection_request import DetectionRequest

# ── Path configuration ────────────────────────────────────────────────────────
# BACKEND_DIR resolves to the smash-backend/ folder regardless of where
# the script is called from. PROMPT_PATH points to prompts/prompt.txt inside it.
# Using absolute paths avoids issues when the backend is started from a
# different working directory.
BACKEND_DIR   = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROMPT_PATH   = os.path.join(BACKEND_DIR, "prompts", "prompt.txt")


class ContextRetriever:

    def retrieve_and_build_prompt(self, request: DetectionRequest) -> DetectionRequest:
        """
        Reads prompt.txt and the file-specific context.txt,
        combines them, fills placeholders, and stores the
        final instruction in request.prompt.
        """

        print(f"[ContextRetriever] Building prompt for: {request.class_name} ({request.language})")

        # ── Step 1: Read prompt.txt ───────────────────────────────────────────
        # This is the base prompt that is always used.
        # It contains the smell definitions and JSON output instructions.
        if not os.path.exists(PROMPT_PATH):
            raise FileNotFoundError(
                f"prompt.txt not found at {PROMPT_PATH}. "
                f"Please create smash-backend/prompts/prompt.txt"
            )

        with open(PROMPT_PATH, "r", encoding="utf-8") as f:
            base_prompt = f.read().strip()

        # ── Step 2: Read context.txt for this specific file ───────────────────
        # context.txt lives next to the source file being analysed.
        # It contains the ADR and any developer feedback from previous runs.
        # If it does not exist or is empty, we skip it.
        
        # context_section starts empty. It will only be filled if:
        #   (a) request.file_path is a valid path (not "unknown")
        #   (b) a context.txt file exists next to the source file
        #   (c) that context.txt contains at least one feedback entry
        #       detected by the presence of the word "verdict:"
        context_section = ""

        if request.file_path and request.file_path != "unknown":
            # Build the context.txt path from the source file path
            # e.g. akhq_7201.java → akhq_7201_context.txt
            source_dir  = os.path.dirname(request.file_path)
            source_name = os.path.splitext(os.path.basename(request.file_path))[0]
            context_path = os.path.join(source_dir, f"{source_name}_context.txt")

            if os.path.exists(context_path):
                with open(context_path, "r", encoding="utf-8") as f:
                    context_content = f.read().strip()

                # Only include context if there is actual feedback
                # (not just the ADR header and empty feedback section)
                if "verdict:" in context_content:
                    context_section = f"""
\nAdditional architecture context and past feedback for this file:
{context_content}

Use the above context and feedback to improve your analysis.
If a smell was previously accepted, it is likely real — report it if still visible.
If a smell was previously discarded, be more cautious about reporting it again unless evidence is very strong.
"""
                    print(f"[ContextRetriever] Found feedback in context.txt — enriching prompt")
                else:
                    print(f"[ContextRetriever] context.txt found but no feedback yet — using base prompt only")
            else:
                print(f"[ContextRetriever] No context.txt found for this file — using base prompt only")

        # ── Step 3: Combine prompt + context ─────────────────────────────────
        # The context section is inserted just before the code block
        # so the LLM reads the architectural knowledge before seeing the code.
        combined_prompt = base_prompt + context_section

        # ── Step 4: Fill placeholders ─────────────────────────────────────────
        filled_prompt = combined_prompt.format(
            language=request.language,
            class_name=request.class_name,
            code=request.code
        )

        # ── Step 5: Store and return ──────────────────────────────────────────
        request.prompt = filled_prompt
        print(f"[ContextRetriever] Prompt built. Length: {len(filled_prompt)} characters")

        return request