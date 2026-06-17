# SMASH 


---

SMASH is a VS Code plugin that detects architectural smells in source code every time you press Ctrl+S. It analyses each class individually using a locally running large language model and shows the results directly inside the editor. Over time, it learns from developer feedback to improve its analysis on the same file.

It supports **Java, Python, C#, C++, TypeScript, and JavaScript**.

---

## What is an Architectural Smell?

An architectural smell is not a bug , it does not break anything today. It is a structural warning sign that the code will become hard to maintain, test, or extend in the future. For example:

- **God Class** — one class is doing too many things at once
- **Hub-Like Dependency** — one class is connected to everything else, becoming a single point of failure
- **Deep Hierarchy** — inheritance is so deeply nested that understanding any behaviour requires tracing through many levels
- **Cyclic Dependency** — two components depend on each other in a circle

SMASH currently detects seven types: God Class, Hub-Like Dependency, Cyclic Dependency, Unstable Dependency, Cyclic Hierarchy, Deep Hierarchy, and Wide Hierarchy.

---

## How It Works

When you press Ctrl+S on a supported file, this is what happens:

1. The VS Code extension reads the file and extracts all class names using language-specific regular expressions
2. For each class, it extracts just that class's code using brace matching , so the LLM only ever sees one class at a time
3. Each class is sent individually to a Python Flask backend running on localhost:5000
4. The backend passes it through a 7-step pipeline: validation → metadata logging → prompt building → model config → LLM call → result formatting → CSV logging
5. The LLM analyses the class and returns a JSON list of smells
6. The extension shows a notification with a summary, and a detailed panel where you can accept or reject each smell and leave a comment
7. Feedback is saved to a context file for that project and used to enrich the next analysis

---

## Project Structure

```
smash-project-v2/
├── smash-backend/
│   ├── app.py                         # Flask server + pipeline wiring
│   ├── requirements.txt               # Python dependencies
│   ├── prompts/
│   │   └── prompt.txt                 # LLM analysis prompt template
│   ├── components/
│   │   ├── detection_coordinator.py   # Step 1: validate and clean input
│   │   ├── source_code_retriever.py   # Step 2: log language metadata
│   │   ├── context_retriever.py       # Step 3: build enriched prompt
│   │   ├── model_retriever.py         # Step 4: LLM configuration
│   │   ├── smell_detection_engine.py  # Step 5: call LLM, parse response
│   │   ├── results_publisher.py       # Step 6: format final response
│   │   ├── run_logger.py              # Step 7: save timestamped CSV
│   │   └── feedback_collector.py      # Separate: handle feedback submissions
│   ├── data/
│   │   ├── __init__.py
│   │   └── detection_request.py       # Data object that travels the pipeline
│   └── logs/                          # Auto-created, holds CSV run logs
├── smash-extension/
│   ├── package.json                   # Extension manifest and settings
│   ├── tsconfig.json
│   └── src/
│       └── extension.ts               # All VS Code extension logic
└── test-java-files/
    ├── akhq_7201.java
    ├── akhq_7223.java
    ├── AndroidDvbDriver_14713_sev7.java
    ├── AndroidDvbDriver_14738_sev6.java
    ├── AndroidDvbDriver_14775_sev7.java
    └── *_context.txt                  # One per test file — ADR + developer feedback
```

---

## Prerequisites

### Python 3.9 or higher
```bash
python --version
# Must show 3.9 or higher
# Download: https://www.python.org/downloads/
```

### Node.js 18 or higher
```bash
node --version
# Must show v18 or higher
# Download: https://nodejs.org
```

### Ollama
Ollama runs the LLM locally on your machine. Download from https://ollama.com/download and open it from the Start menu.

```bash
# Pull the model (do this once):
ollama pull qwen2.5-coder:3b     # lightweight, for local CPU use
# or for better quality (needs GPU):
ollama pull llama3.1:8b

# Verify it downloaded:
ollama list
```

### Python dependencies
```bash
cd smash-project-v2/smash-backend
pip install -r requirements.txt
# Installs: flask, flask-cors, requests, psutil
```

### Node.js dependencies
```bash
cd smash-project-v2/smash-extension
npm install
```

---

## The Pipeline — What Each File Does

Every Ctrl+S press triggers this sequence:

| Step | File | What happens |
|------|------|--------------|
| 1 | `extension.ts` | Language detected, class names extracted, each class extracted individually by brace matching, one HTTP POST per class to `/analyze` |
| 2 | `app.py` | Flask receives request, starts run timer, calls all pipeline components in order |
| 3 | `detection_coordinator.py` | Validates all fields present and clean, creates DetectionRequest object |
| 4 | `source_code_retriever.py` | Extracts language-specific metadata, logs to terminal (Java: package name, Python: function count, etc.) |
| 5 | `context_retriever.py` | Reads `prompt.txt` + file-specific `context.txt`, combines them if feedback exists, fills placeholders |
| 6 | `model_retriever.py` | Returns model name, Ollama URL, temperature (0.1), max tokens (2048) |
| 7 | `smell_detection_engine.py` | POSTs prompt to Ollama, waits for response, parses JSON from raw text with multiple fallback strategies |
| 8 | `results_publisher.py` | Formats smells into final JSON response with summary string |
| 9 | `run_logger.py` | Creates timestamped CSV in `logs/` with smell details and machine info |
| 10 | `extension.ts` | Shows notification, opens See Details panel with per-smell Accept/Discard feedback UI |

---

## The DetectionRequest Object

Every pipeline component receives and returns the same `DetectionRequest` object. It starts with a few fields and gets more added at each step:

| Field | Added by | Description |
|-------|----------|-------------|
| `code` | DetectionCoordinator | Source code of the single class being analysed |
| `class_name` | DetectionCoordinator | Name of the class |
| `language` | DetectionCoordinator | Human-readable language name e.g. Java, Python |
| `file_path` | DetectionCoordinator | Absolute path of the source file, used to locate `context.txt` |
| `prompt` | ContextRetriever | Final filled prompt combining `prompt.txt` and `context.txt` |
| `model_name` | ModelRetriever | LLM model name e.g. `qwen2.5-coder:3b` |
| `raw_llm_response` | SmellDetectionEngine | Raw text returned by the LLM |
| `smells` | SmellDetectionEngine | Parsed list of smell dictionaries |

---

## The Feedback Loop

This is what makes SMASH different from a regular static analyser. It learns from the developer over time.

**First run:**
`context_retriever.py` reads `prompt.txt` and the file's `context.txt`. The context file at this point only has the Architecture Decision Record (ADR) for that project. No feedback yet, so only the base prompt is used.

**Developer submits feedback:**
In the See Details panel, the developer selects Accept or Discard per smell, types a mandatory comment, and clicks Submit. This is POSTed to `/feedback`. `feedback_collector.py` appends a new line to the file's `context.txt`:

```
[2026-06-02 14:35:42] | class: RecordRepository | smell: God Class | verdict: accept | comment: This class really does too many things
```

**Next run:**
`context_retriever.py` reads `context.txt` again, finds the `verdict:` keyword, and appends the full context and feedback to the prompt. The LLM is now told: if a smell was previously accepted, be more confident. If discarded, be more cautious.

**The loop:** See Details panel → `submitFeedback()` → POST `/feedback` → `feedback_collector.collect()` → written to `context.txt` → next Ctrl+S → `context_retriever` reads it → enriched prompt → smarter analysis

---

## ADR Files (Architecture Decision Records)

Each Java test file has a corresponding `context.txt` sitting next to it in `test-java-files/`. These contain an ADR documenting the key design decisions in that project and why certain architectural smells exist. They serve as the initial knowledge base before any feedback is collected.

| File | Project |
|------|---------|
| `akhq_7201_context.txt` | AKHQ Kafka HQ — Record and Topic Management |
| `akhq_7223_context.txt` | AKHQ Kafka HQ — Cluster and Consumer Group Layer |
| `AndroidDvbDriver_14713_context.txt` | AndroidDvbDriver — DVB-T USB Driver |
| `AndroidDvbDriver_14738_context.txt` | AndroidDvbDriver — CX USB DVB Device Driver |
| `AndroidDvbDriver_14775_context.txt` | AndroidDvbDriver — RTL2832 Frontend Driver |

---

## Run Logger — CSV Files

Every completed run creates a new CSV in `smash-backend/logs/` named with the timestamp down to the second (e.g. `2026-06-02_14-35-42.csv`). One row per smell. Columns:

`timestamp`, `project_name`, `class_name`, `language`, `smell_category`, `smell_severity`, `smell_location`, `model_name`, `run_duration_secs`, `machine_os`, `machine_cpu`, `machine_ram_gb`, `machine_hostname`

---

## Running the Project — Local Mode

**Step 1 — Start Ollama**
Open Ollama from the Windows Start menu. Check the system tray to confirm it is running.

**Step 2 — Start the backend**
```bash
cd smash-project-v2/smash-backend
python app.py
```
Open http://localhost:5000/health in a browser to confirm it is running.

**Step 3 — Build and install the extension**
```bash
cd smash-project-v2/smash-extension
npm run compile
npx vsce package
```
In VS Code: Ctrl+Shift+P → Extensions: Install from VSIX → select the .vsix file → reload window.

**Step 4 — Test**
Open any file from `test-java-files/` and press Ctrl+S. Watch the backend terminal for all pipeline steps.

**Updating after code changes:**
Every time `extension.ts` or `package.json` changes, rebuild with `npm run compile`, repackage with `npx vsce package`, reinstall the .vsix, and reload VS Code.

---

## Running the Project — Google Colab GPU Mode

This mode uses Google Colab's free T4 GPU for much faster inference. The setup must be repeated every time the Colab session reconnects because the ngrok URL changes each time.

**One-time setup:** Sign up at https://ngrok.com, go to https://dashboard.ngrok.com/authtokens, copy your authtoken. It is permanent — save it somewhere safe.

**Run these Colab cells every session:**

```bash
# Cell 1 — Install zstd
!apt-get install -y zstd

# Cell 2 — Install Ollama
!curl -fsSL https://ollama.com/install.sh | sh

# Cell 3 — Start Ollama
!OLLAMA_HOST=0.0.0.0 ollama serve > /dev/null 2>&1 &
!sleep 5
!echo "Ollama started"

# Cell 4 — Pull the model (downloads ~5GB, wait for completion)
!ollama pull llama3.1:8b
```

```python
# Cell 5 — Expose via ngrok
from pyngrok import ngrok
ngrok.set_auth_token("YOUR_PERMANENT_AUTHTOKEN_HERE")
public_url = ngrok.connect(11434, bind_tls=True)
print("Your Ollama URL:", public_url)
```

```javascript
// Cell 6 — Keep Colab alive
%%javascript
function ClickConnect(){
    document.querySelector("colab-connect-button").click()
}
setInterval(ClickConnect, 60000)
```

**Then on your laptop**, update `smash-backend/components/model_retriever.py`:
```python
OLLAMA_BASE_URL = "https://YOUR-NGROK-URL.ngrok-free.app/api/generate"
DEFAULT_MODEL   = "llama3.1:8b"
```
Restart the backend. No extension rebuild needed.

---

## Adding a New Language

Only 4 places need to change. Example: adding Kotlin.

**1 — `SUPPORTED_LANGUAGES` in `extension.ts`**
```typescript
const SUPPORTED_LANGUAGES: string[] = [
  'java', 'python', 'csharp', 'cpp', 'typescript', 'javascript',
  'kotlin',  // add this
];
```

**2 — `detectLanguage()` in `extension.ts`**
```typescript
case 'kotlin': return 'Kotlin';
```

**3 — `extractClassNames()` in `extension.ts`**
```typescript
case 'kotlin':
  classRegex = /(?:data\s+|abstract\s+|sealed\s+)?class\s+(\w+)/g;
  break;
```

**4 — `source_code_retriever.py`**
```python
elif language_lower == 'kotlin':
    metadata = self._extract_kotlin_metadata(request.code)

def _extract_kotlin_metadata(self, code: str) -> dict:
    class_count = len(re.findall(r'\bclass\s+\w+', code))
    fun_count = len(re.findall(r'^\s*fun\s+\w+', code, re.MULTILINE))
    return {'Classes found': class_count, 'Functions': fun_count}
```

Rebuild the extension, reinstall, reload VS Code.

---

## Common Errors and Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| Nothing happens on Ctrl+S | Extension not activating | Check `activationEvents` in `package.json` lists all supported language IDs. Rebuild, reinstall, reload. |
| Cannot connect to Ollama | Ollama not running | Open Ollama from Start menu, check system tray. |
| 403 Forbidden from ngrok URL | ngrok browser warning | Add header `ngrok-skip-browser-warning: true` to `requests.post()` in `smell_detection_engine.py`. |
| Model not found in terminal | Model name mismatch | Run `ollama list` and verify exact name matches `DEFAULT_MODEL` in `model_retriever.py`. |
| LLM returns plain text not JSON | Model ignoring format instruction | `_parse_llm_response()` handles this gracefully with fallback strategies and returns `[]`. |
| SMASH Error in VS Code | Backend error | Check backend terminal — full error is always printed there. |
| Colab disconnected | Free Colab timeout | Re-run all Colab cells, get new ngrok URL, update `model_retriever.py`, restart backend. |

---

## Version History

**v1.0 — Java Only**
Original version. Only Java files supported. Entire file sent as one request. Java hardcoded in the prompt. No feedback, no CSV logging.

**v2.0 — Language Independent**
Added `SUPPORTED_LANGUAGES`, `detectLanguage()`, language-aware `extractClassNames()`. Added `language` field to pipeline. Prompt updated with `{language}` placeholder. Source code retriever made language-aware.

**v3.0 — Per-Class Analysis + Feedback Loop + Run Logger**
Each class now extracted and analysed individually using brace matching. `file_path` added to pipeline. Prompt moved to `prompt.txt`. `context_retriever.py` reads `context.txt` and enriches prompt with feedback. Per-smell Accept/Discard UI added. `feedback_collector.py` writes to `context.txt`. `run_logger.py` creates timestamped CSVs. Google Colab + ngrok setup for GPU inference.

---
