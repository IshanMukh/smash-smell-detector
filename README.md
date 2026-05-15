# SMASH – Software Architecture Smell Hunter

A VS Code extension that detects **class-level architectural smells** in Java code using **Llama 3.1 8B** (via Ollama) every time you press **Ctrl+S** on a `.java` file.

---

## Project Structure

```
smash-project/
├── smash-extension/          ← VS Code extension (TypeScript)
│   ├── src/
│   │   └── extension.ts      ← Main extension logic
│   ├── package.json
│   └── tsconfig.json
├── smash-backend/            ← Python Flask backend (LLM bridge)
│   ├── app.py                ← Flask server + Ollama caller
│   └── requirements.txt
├── test-java-files/          ← 5 sample Java files for testing
│   ├── OrderManagementSystem.java   (God Class)
│   ├── ApplicationController.java  (Hub-Like Dependency)
│   ├── ElectricLuxurySedan.java    (Deep Hierarchy)
│   ├── OrderManager.java           (Cyclic Dependency)
│   └── HRReportGenerator.java      (Wide Hierarchy)
├── start_backend.bat         ← One-click backend launcher (Windows)
└── build_extension.bat       ← One-click extension builder (Windows)
```

---

## Prerequisites – Install These First

### 1. Python 3.9 or higher
Download from https://www.python.org/downloads/  
During install, **tick "Add Python to PATH"**.

Verify:
```
python --version
```

### 2. Node.js 18 or higher
Download from https://nodejs.org/  
Choose the LTS version.

Verify:
```
node --version
npm --version
```

### 3. Ollama (runs Llama locally on your machine)

**Step-by-step for Windows:**

1. Go to https://ollama.com/download
2. Click **Download for Windows** and run the installer.
3. After install, open a **new Command Prompt** and verify:
   ```
   ollama --version
   ```
4. Pull the Llama 3.1 8B model (this downloads ~4.7 GB, one time only):
   ```
   ollama pull llama3.1:8b
   ```
   Wait for the download to finish. You will see a progress bar.

5. Start the Ollama server (keep this terminal open):
   ```
   ollama serve
   ```
   You should see: `Listening on 127.0.0.1:11434`

> **Note:** Ollama must be running BEFORE you start the SMASH backend.  
> You can also just open the Ollama desktop app from the system tray after install — it auto-starts the server.

### 4. VS Code
Download from https://code.visualstudio.com/

---

## Setup & Running – Step by Step

### Step 1 – Start Ollama

Open a terminal and run:
```
ollama serve
```
Leave this terminal open. (Or just open the Ollama app from Start menu.)

---

### Step 2 – Start the SMASH Backend

**Option A (easy):** Double-click `start_backend.bat` in the project root.

**Option B (manual):**
```cmd
cd smash-project\smash-backend
pip install -r requirements.txt
python app.py
```

You should see:
```
============================================================
  SMASH Backend starting on http://localhost:5000
  Using model : llama3.1:8b
  Ollama URL  : http://localhost:11434/api/generate
============================================================
```

Leave this terminal open too.

---

### Step 3 – Build the VS Code Extension

**Option A (easy):** Double-click `build_extension.bat`.

**Option B (manual):**
```cmd
cd smash-project\smash-extension
npm install
npm run compile
npx vsce package --no-dependencies
```

This creates a file like `smash-smell-detector-1.0.0.vsix` inside the `smash-extension` folder.

---

### Step 4 – Install the Extension in VS Code

1. Open **VS Code**
2. Press `Ctrl+Shift+P` to open the command palette
3. Type: `Extensions: Install from VSIX`
4. Browse to `smash-extension/smash-smell-detector-1.0.0.vsix`
5. Click **Install**
6. You'll see: *"SMASH Smell Detector installed successfully"*

---

### Step 5 – Test It!

1. Open any `.java` file from the `test-java-files/` folder in VS Code
2. Press **Ctrl+S**
3. Watch the bottom status bar: `$(sync) SMASH: Analysing ClassName…`
4. After ~10–30 seconds a **popup notification** appears:

   - ✅ No smells: `SMASH: No architectural smells detected in ClassName.`
   - ⚠️ Smells found: `SMASH [ClassName]: 2 smell(s) found → God Component / God Class (severity 8/10) | Hub-Like Dependency (severity 7/10)`

5. Click **"See Details"** on the notification to open a formatted panel with full descriptions and refactoring suggestions.

---

## How It Works (Architecture)

```
Developer presses Ctrl+S on .java file in VS Code
        │
        ▼
[Extension – Detection Client]
  Reads file content & class name
        │  HTTP POST /analyze
        ▼
[Python Flask Backend – Trigger Receiver]
        │
        ▼
[Context Manager]
  Fills in the smell analysis prompt template
        │
        ▼
[Ollama – Llama 3.1 8B]
  Analyses the Java code
  Returns JSON array of smells
        │
        ▼
[Results Parser]
  Extracts JSON, validates fields
        │  JSON response
        ▼
[Extension – Notification]
  Shows popup with smell names and severities
  (Optional: "See Details" webview panel)
```

---

## Configuration

In VS Code, go to **File → Preferences → Settings** and search for `SMASH`.

| Setting | Default | Description |
|---|---|---|
| `smash.backendUrl` | `http://localhost:5000` | URL of the backend server |
| `smash.enableOnSave` | `true` | Run analysis on every Ctrl+S |

You can also **disable auto-analysis** and trigger it manually:  
`Ctrl+Shift+P` → `SMASH: Analyze Current Java File for Architecture Smells`

---

## Detected Smell Categories

| Category | What it means |
|---|---|
| God Component / God Class | Class has too many responsibilities |
| Hub-Like Dependency | Class connects to way too many other components |
| Cyclic Dependency | Two classes depend on each other in a cycle |
| Unstable Dependency | Class depends on volatile/unstable collaborators |
| Deep Hierarchy | Inheritance chain is excessively long |
| Wide Hierarchy | Too many direct subclasses, missing intermediate layers |
| Cyclic Hierarchy | Parent class depends on its own subtype |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `SMASH Error: Cannot connect to Ollama` | Run `ollama serve` in a terminal |
| `SMASH Error: Cannot connect to backend` | Run `python app.py` in `smash-backend/` |
| Notification never appears | Check backend terminal for errors |
| Analysis is very slow | Normal on first call – Llama loads into memory. Subsequent calls are faster. |
| `.vsix` build fails | Make sure Node 18+ is installed and `npm install` succeeded |

---

## System Requirements

| Component | Minimum |
|---|---|
| RAM | 8 GB (16 GB recommended for smooth LLM inference) |
| Disk | 5 GB free (for the Llama model) |
| OS | Windows 10/11 |
| GPU | Optional – Ollama auto-detects and uses GPU if available (much faster) |
