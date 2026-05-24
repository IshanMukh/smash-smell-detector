# SMASH — Software Architecture Smell Hunter
### Helsinki University Internship Project

A VS Code extension that detects **class-level architectural smells** in source code
using **Qwen 2.5** (via Ollama) every time you press **Ctrl+S**.

Supports: **Java, Python, C#, C++, TypeScript, JavaScript**

---

## What Changed in v2.0 (Language Independence)

Previously SMASH only worked on Java files. Three targeted changes were made
to support all languages — the modular structure is fully preserved:

| File | What Changed |
|---|---|
| `extension.ts` | Added `SUPPORTED_LANGUAGES` list, `detectLanguage()`, language-aware `extractClassNames()`, sends `language` to backend |
| `detection_request.py` | Added `language` field to the data object |
| `detection_coordinator.py` | Reads and normalises the `language` field from the request |
| `source_code_retriever.py` | Language-aware metadata extraction (Java/Python/C#/C++/generic) |
| `context_retriever.py` | Prompt now uses `{language}` placeholder instead of hardcoded "Java" |

**To add a new language in the future:**
1. Add its VS Code `languageId` to `SUPPORTED_LANGUAGES` in `extension.ts`
2. Add a `case` in `detectLanguage()` with its display name
3. Add a `case` in `extractClassNames()` with its class regex
4. Add an `elif` in `source_code_retriever.py` for metadata extraction
5. Nothing else needs to change.

---

## Project Structure

```
smash-project-v2/
├── smash-backend/                    ← Python Flask backend
│   ├── app.py                        ← Main entry point + Flask routes (Trigger Receiver)
│   ├── requirements.txt              ← Python dependencies
│   ├── components/                   ← One file per C4 component
│   │   ├── detection_coordinator.py  ← Validates and normalises trigger payload
│   │   ├── source_code_retriever.py  ← Retrieves source code artifacts (language-aware)
│   │   ├── context_retriever.py      ← Builds the LLM prompt (language-aware)
│   │   ├── model_retriever.py        ← Gets LLM model configuration
│   │   ├── smell_detection_engine.py ← Calls Ollama, runs smell detection
│   │   ├── results_publisher.py      ← Formats and returns results
│   │   └── feedback_collector.py     ← Stores developer feedback
│   └── data/
│       └── detection_request.py      ← DetectionRequest data class (has language field)
├── smash-extension/                  ← VS Code extension (TypeScript)
│   └── src/
│       └── extension.ts              ← Extension logic (DetectionClient)
├── test-java-files/                  ← 5 Java test files
├── start_backend.bat                 ← Start the backend (Windows)
└── build_extension.bat               ← Build the extension (Windows)
```

---

## C4 Diagram → Code Mapping

| C4 Box | File | Class/Function |
|---|---|---|
| Practitioner | — | You — the developer |
| DetectionClient | `extension.ts` | Entire file |
| Trigger Receiver | `app.py` | `/analyze` route |
| Detection Coordinator | `components/detection_coordinator.py` | `DetectionCoordinator` |
| Source Code Retriever | `components/source_code_retriever.py` | `SourceCodeRetriever` |
| Context Manager / Prompts | `components/context_retriever.py` | `ContextRetriever` |
| Model Management Layer | `components/model_retriever.py` | `ModelRetriever` |
| Smell Detection Engine | `components/smell_detection_engine.py` | `SmellDetectionEngine` |
| Results Publisher | `components/results_publisher.py` | `ResultsPublisher` |
| Feedback Collector | `components/feedback_collector.py` | `FeedbackCollector` |
| DetectionRequest | `data/detection_request.py` | `DetectionRequest` |

---

## How to Run

### Prerequisites
- Python 3.9+
- Node.js 18+
- Ollama installed with `qwen2.5:latest` pulled

### Step 1 — Start Ollama
Open Ollama from the Windows Start menu.

### Step 2 — Start the backend
Double click `start_backend.bat`

### Step 3 — Build and install the extension
Run `build_extension.bat`, then install the `.vsix` in VS Code.

### Step 4 — Test
Open any `.java`, `.py`, `.cs`, `.cpp`, `.ts`, or `.js` file and press `Ctrl+S`.
