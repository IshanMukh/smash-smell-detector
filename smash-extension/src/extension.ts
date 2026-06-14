import * as vscode from 'vscode';
import * as http from 'http';
 
 
 
// SmellResult represents one detected architectural smell.
// This type must match the JSON fields returned by the backend.
interface SmellResult {
  smell_category: string;
  smell_severity: number;
  smell_location: string;
  smell_description: string;
  smell_suggestion: string;
}
 
 
// ─────────────────────────────────────────────
//  SUPPORTED LANGUAGES
// ─────────────────────────────────────────────
//
// This is the master list of languages SMASH supports.
// Each entry is a VS Code languageId — the identifier VS Code uses
// internally to know what language a file is written in.
//
// VS Code sets doc.languageId automatically based on the file extension:
//   .java  → "java"
//   .py    → "python"
//   .cs    → "csharp"
//   .cpp   → "cpp"
//   .ts    → "typescript"
//   .js    → "javascript"
//
// HOW TO ADD A NEW LANGUAGE:
//   1. Add its VS Code languageId to this list
//   2. Add a case for it in detectLanguage()
//   3. Add a case for it in extractClassNames()
//   That is all — no other file needs to change.
const SUPPORTED_LANGUAGES: string[] = [
  'java',
  'python',
  'csharp',
  'cpp',
  'typescript',
  'javascript',
];
 
 
// ─────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────
 
/**
 * Maps a VS Code languageId to a human-readable language name.
 *
 * Why do we need this?
 * --------------------
 * VS Code uses short internal IDs like "csharp" or "cpp".
 * The LLM prompt needs proper names like "C#" or "C++" so the LLM
 * understands the language correctly when reading the code.
 *
 * Parameters:
 *   languageId — VS Code's internal language identifier (doc.languageId)
 *
 * Returns:
 *   A human-readable language name for use in the LLM prompt.
 *   Falls back to the raw languageId if no mapping exists.
 */
function detectLanguage(languageId: string): string {
  switch (languageId) {
    case 'java':        return 'Java';
    case 'python':      return 'Python';
    case 'csharp':      return 'C#';
    case 'cpp':         return 'C++';
    case 'typescript':  return 'TypeScript';
    case 'javascript':  return 'JavaScript';
    default:            return languageId;
  }
}
 
 
/**
 * Extracts all class names from source code using language-specific regex.
 *
 * Different languages declare classes differently, so this function
 * picks the right regex pattern based on the detected language.
 *
 * Parameters:
 *   code       — the full source code of the file
 *   languageId — VS Code's internal language identifier
 *
 * Returns:
 *   Array of detected class names.
 *   Falls back to ['UnknownClass'] if no class declaration is found.
 */


function extractClassNames(code: string, languageId: string): string[] {
  let classRegex: RegExp;
 
  switch (languageId) {
    case 'java':
      classRegex = /(?:public\s+)?(?:abstract\s+)?(?:final\s+)?class\s+(\w+)/g;
      break;
    case 'python':
      classRegex = /^class\s+(\w+)(?:\s*[\(:])/gm;
      break;
    case 'csharp':
      classRegex = /(?:public\s+|private\s+|internal\s+)?(?:abstract\s+|sealed\s+)?class\s+(\w+)/g;
      break;
    case 'cpp':
      classRegex = /\bclass\s+(\w+)/g;
      break;
    case 'typescript':
    case 'javascript':
      classRegex = /(?:export\s+)?(?:abstract\s+)?class\s+(\w+)/g;
      break;
    default:
      classRegex = /\bclass\s+(\w+)/g;
      break;
  }
 
  const names: string[] = [];
  let match: RegExpExecArray | null;
  while ((match = classRegex.exec(code)) !== null) {
    names.push(match[1]);
  }
 
  return names.length > 0 ? names : ['UnknownClass'];
}
 
 
/**
 * Extracts the source code of a single class from a file containing
 * multiple classes. Uses brace matching to find the exact start and
 * end of the class body.
 *
 * Parameters:
 *   code      — the full source code of the file
 *   className — the name of the class to extract
 *
 * Returns:
 *   The source code of just that class, or the full code as fallback.
 */
function extractClassCode(code: string, className: string): string {
  const classStart = code.search(new RegExp(`class\\s+${className}[\\s\\{<]`));
  if (classStart === -1) { return code; }
 
  let braceCount = 0;
  let started = false;
  let i = classStart;
 
  while (i < code.length) {
    if (code[i] === '{') {
      braceCount++;
      started = true;
    } else if (code[i] === '}') {
      braceCount--;
      if (started && braceCount === 0) {
        return code.substring(classStart, i + 1);
      }
    }
    i++;
  }
 
  return code;
}
 
 
/**
 * POST the source code of one class to the SMASH backend.
 *
 * Payload sent to backend:
 *   class_name — the name of the class being analysed
 *   code       — the source code of just that class
 *   language   — human-readable language name (e.g. "Java")
 *   file_path  — absolute path of the source file on disk
 *                used by the backend to find the correct context.txt
 *
 * Parameters:
 *   backendUrl — URL of the Flask backend (default: http://localhost:5000)
 *   className  — name of the class being analysed
 *   code       — source code of just that class
 *   language   — human-readable language name
 *   filePath   — absolute path of the source file
 */
function analyzeWithBackend(
  backendUrl: string,
  className: string,
  code: string,
  language: string,
  filePath: string
): Promise<SmellResult[]> {
  return new Promise((resolve, reject) => {
 
    // file_path is now included so the backend can read the correct context.txt
    const payload = JSON.stringify({
      class_name: className,
      code,
      language,
      file_path: filePath
    });
 
    const url = new URL('/analyze', backendUrl);
 
    const options: http.RequestOptions = {
      hostname: url.hostname,
      port: parseInt(url.port || '5000', 10),
      path: url.pathname,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
      },
    };
 
    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        try {
          const parsed = JSON.parse(data);
          if (parsed.error) {
            reject(new Error(parsed.error));
          } else {
            resolve(parsed.smells as SmellResult[]);
          }
        } catch {
          reject(new Error('Invalid JSON response from backend'));
        }
      });
    });
 
    req.on('error', (err) => reject(err));
    req.write(payload);
    req.end();
  });
}
 
 
/**
 * Analyses every class in the file individually by:
 *   1. Extracting each class's code separately using extractClassCode()
 *   2. Sending each class to the backend as its own request with file_path
 *   3. Combining all results into one list
 *
 * This ensures the LLM only sees one class at a time so the
 * smell_location field will always be correct.
 *
 * Parameters:
 *   backendUrl — URL of the Flask backend
 *   classNames — all class names found in the file
 *   fullCode   — the complete source code of the file
 *   language   — human-readable language name
 *   filePath   — absolute path of the source file (sent to backend)
 */
async function analyzeAllClasses(
  backendUrl: string,
  classNames: string[],
  fullCode: string,
  language: string,
  filePath: string
): Promise<SmellResult[]> {
  const allSmells: SmellResult[] = [];
  // We loop through every class found in the file.
  // Each class gets its own backend request with only its own code.
  // This ensures the LLM never sees two classes at once, which was
  // causing it to assign wrong class names to the smell_location field.
  for (const className of classNames) {
    // Extract just this class's code from the full file
    const classCode = extractClassCode(fullCode, className);
 
    // Send this one class to the backend along with the file path
    const smells = await analyzeWithBackend(backendUrl, className, classCode, language, filePath);
    allSmells.push(...smells);
  }
 
  return allSmells;
}
 
 
/**
 * Builds a human-readable one-line summary of the smell results.
 * Shows smell_location per smell so the developer sees which class had which smell.
 * Shown in the VS Code notification bar.
 */
function buildSummary(smells: SmellResult[], fileName: string): string {
  if (smells.length === 0) {
    return `✅ SMASH: No architectural smells detected in ${fileName}.`;
  }
  const categories = smells.map(
    (s) => `${s.smell_location}: ${s.smell_category} (${s.smell_severity}/10)`
  );
  return `⚠️ SMASH [${fileName}]: ${smells.length} smell(s) found → ${categories.join(' | ')}`;
}
 
 
/**
 * Shows a VS Code notification with the analysis results.
 * For files with smells, offers a "See Details" button that opens
 * a formatted webview panel alongside the editor.
 *
 * Parameters:
 *   smells    — list of detected smells
 *   fileName  — short file name shown in the notification and panel title
 *   filePath  — absolute file path sent to the feedback endpoint
 */
async function showSmellNotification(
  smells: SmellResult[],
  fileName: string,
  filePath: string
): Promise<void> {
  const summary = buildSummary(smells, fileName);
 
  if (smells.length === 0) {
    vscode.window.showInformationMessage(summary);
    return;
  }
 
  const choice = await vscode.window.showWarningMessage(summary, 'See Details', 'Dismiss');
 
  if (choice === 'See Details') {
    const panel = vscode.window.createWebviewPanel(
      'smashDetails',
      `SMASH Results – ${fileName}`,
      vscode.ViewColumn.Beside,
      { enableScripts: true }  // scripts must be enabled for the feedback buttons to work
    );
    panel.webview.html = buildDetailHtml(smells, fileName, filePath);
  }
}
 
 
/**
 * Generates a styled HTML page for the detail webview panel.
 *
 * Each smell card shows:
 *   - Category, severity, location, description, suggestion
 *   - Accept / Discard buttons
 *   - Mandatory comment textarea
 *   - Submit Feedback button
 *
 * On submit, feedback is POSTed to http://localhost:5000/feedback.
 * The backend appends it to the file's context.txt.
 *
 * Parameters:
 *   smells   — list of smell objects to display
 *   fileName — short file name shown in the panel heading
 *   filePath — absolute file path included in the feedback payload
 */
function buildDetailHtml(smells: SmellResult[], fileName: string, filePath: string): string {
  const rows = smells.map((s, index) => `
    <div class="card" id="card-${index}">
      <div class="header">
        <span class="category">${s.smell_category}</span>
        <span class="severity sev-${severityClass(s.smell_severity)}">Severity ${s.smell_severity}/10</span>
      </div>
      <p><strong>Location:</strong> ${s.smell_location}</p>
      <p><strong>Description:</strong> ${s.smell_description}</p>
      <p><strong>Suggestion:</strong> ${s.smell_suggestion}</p>
 
      <div class="feedback-box" id="feedback-${index}">
        <p class="feedback-label">Was this smell correctly detected?</p>
        <div class="btn-row">
          <button class="btn-accept"  id="accept-${index}"  onclick="selectVerdict(${index}, 'accept')">✅ Accept</button>
          <button class="btn-discard" id="discard-${index}" onclick="selectVerdict(${index}, 'discard')">❌ Discard</button>
        </div>
        <textarea
          id="comment-${index}"
          placeholder="Comment is required before submitting..."
          rows="3">
        </textarea>
        <button class="btn-submit" onclick="submitFeedback(${index})">Submit Feedback</button>
        <p class="feedback-status" id="status-${index}"></p>
      </div>
    </div>`
  ).join('\n');
 
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<style>
  body         { font-family: var(--vscode-font-family); padding: 16px; color: var(--vscode-editor-foreground); background: var(--vscode-editor-background); }
  h1           { font-size: 1.2em; margin-bottom: 12px; }
  .card        { border: 1px solid var(--vscode-panel-border); border-radius: 6px; padding: 12px; margin-bottom: 12px; }
  .header      { display: flex; justify-content: space-between; margin-bottom: 8px; }
  .category    { font-weight: bold; font-size: 1.05em; }
  .severity    { padding: 2px 8px; border-radius: 4px; font-size: 0.85em; }
  .sev-low     { background: #2e7d32; color: #fff; }
  .sev-mid     { background: #f57f17; color: #fff; }
  .sev-high    { background: #c62828; color: #fff; }
  p            { margin: 4px 0; font-size: 0.92em; }
  .feedback-box    { margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--vscode-panel-border); }
  .feedback-label  { font-weight: bold; margin-bottom: 6px; }
  .btn-row         { display: flex; gap: 8px; margin-bottom: 8px; }
  .btn-accept, .btn-discard, .btn-submit {
    padding: 4px 12px; border: none; border-radius: 4px;
    cursor: pointer; font-size: 0.85em;
  }
  .btn-accept      { background: #2e7d32; color: #fff; }
  .btn-discard     { background: #c62828; color: #fff; }
  .btn-submit      { background: #1565c0; color: #fff; margin-top: 6px; }
  .btn-accept.selected, .btn-discard.selected { outline: 3px solid #fff; }
  textarea         {
    width: 100%; box-sizing: border-box;
    background: var(--vscode-input-background);
    color: var(--vscode-input-foreground);
    border: 1px solid var(--vscode-panel-border);
    border-radius: 4px; padding: 6px;
    font-size: 0.85em; resize: vertical;
  }
  .feedback-status { font-size: 0.82em; margin-top: 4px; min-height: 16px; }
  .done            { opacity: 0.5; pointer-events: none; }
</style>
</head>
<body>
<h1>🔍 SMASH – Architectural Smell Report for <code>${fileName}</code></h1>
${rows}
<script>
  // verdicts tracks which verdict (accept/discard) was chosen per smell index
  const verdicts = {};
 
  // filePath and smells are embedded at render time so the JS can use them
  const filePath = ${JSON.stringify(filePath)};
  const smells   = ${JSON.stringify(smells)};
 
  // selectVerdict highlights the chosen button and records the choice
  function selectVerdict(index, verdict) {
    verdicts[index] = verdict;
    document.getElementById('accept-'  + index).classList.toggle('selected', verdict === 'accept');
    document.getElementById('discard-' + index).classList.toggle('selected', verdict === 'discard');
  }
 
  // submitFeedback validates input and POSTs to the backend /feedback route
  async function submitFeedback(index) {
    const verdict  = verdicts[index];
    const comment  = document.getElementById('comment-' + index).value.trim();
    const statusEl = document.getElementById('status-'  + index);
 
    // Both verdict and comment are required before submitting
    if (!verdict) {
      statusEl.style.color = '#f57f17';
      statusEl.textContent = 'Please select Accept or Discard first.';
      return;
    }
    if (!comment) {
      statusEl.style.color = '#f57f17';
      statusEl.textContent = 'Comment is required before submitting.';
      return;
    }
 
    const smell = smells[index];
 
    const payload = {
      file_path     : filePath,
      class_name    : smell.smell_location,
      smell_category: smell.smell_category,
      verdict       : verdict,
      comment       : comment
    };
 
    try {
      const res  = await fetch('http://localhost:5000/feedback', {
        method : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body   : JSON.stringify(payload)
      });
      const data = await res.json();
 
      if (data.status === 'saved') {
        statusEl.style.color = '#2e7d32';
        statusEl.textContent = '✅ Feedback saved to context.txt';
        // Grey out the feedback box so it cannot be submitted again
        document.getElementById('feedback-' + index).classList.add('done');
      } else {
        statusEl.style.color = '#c62828';
        statusEl.textContent = 'Error: ' + JSON.stringify(data);
      }
    } catch (err) {
      statusEl.style.color = '#c62828';
      statusEl.textContent = 'Could not reach backend: ' + err.message;
    }
  }
</script>
</body>
</html>`;
}
 
 
/**
 * Maps a severity number (1-10) to a CSS class name for colour coding.
 *   1-3  → "low"  (green)
 *   4-6  → "mid"  (orange)
 *   7-10 → "high" (red)
 */
function severityClass(n: number): string {
  if (n <= 3) { return 'low'; }
  if (n <= 6) { return 'mid'; }
  return 'high';
}
 
 
// ─────────────────────────────────────────────
//  Extension lifecycle
// ─────────────────────────────────────────────
 
/**
 * Called by VS Code once when the extension is first activated.
 * Sets up two triggers:
 *   1. Auto-analysis on Ctrl+S (onDidSaveTextDocument)
 *   2. Manual analysis via the Command Palette (smash.analyzeFile)
 */
export function activate(context: vscode.ExtensionContext): void {
  console.log('SMASH Smell Detector is now active.');
 
  // ── Trigger 1: Ctrl+S auto-analysis ──────────────────────────────────────
  //
  // onDidSaveTextDocument fires every time any file is saved in VS Code.
  // We check two things before doing anything:
  //   (a) Is SMASH enabled in settings? (smash.enableOnSave)
  //   (b) Is the file written in a language SMASH supports?
  //
  // If both checks pass, we:
  //   1. Extract all class names from the file
  //   2. Analyse each class individually (one backend call per class)
  //   3. Combine all results and show the notification
  //
  // fileName = short name for display (e.g. akhq_7201.java)
  // filePath = full absolute path sent to backend so it can find context.txt
  const saveListener = vscode.workspace.onDidSaveTextDocument(async (doc) => {
    const config  = vscode.workspace.getConfiguration('smash');
    const enabled: boolean = config.get('enableOnSave', true);
 
    if (!enabled) { return; }
 
    if (!SUPPORTED_LANGUAGES.includes(doc.languageId)) { return; }
 
    const backendUrl: string = config.get('backendUrl', 'http://localhost:5000');
    const code      = doc.getText();
    const language  = detectLanguage(doc.languageId);
    const classNames = extractClassNames(code, doc.languageId);
 
    // fileName is the short name shown in notifications and the panel title
    const fileName = doc.fileName.split(/[\\/]/).pop() || classNames[0];
 
    // filePath is the full absolute path sent to the backend
    // so the backend can find the correct context.txt for this file
    const filePath = doc.fileName;
 
    const statusBar = vscode.window.setStatusBarMessage(
      `$(sync~spin) SMASH: Analysing ${classNames.length} class(es) in ${language}…`
    );
 
    try {
      const smells = await analyzeAllClasses(backendUrl, classNames, code, language, filePath);
      await showSmellNotification(smells, fileName, filePath);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`SMASH Error: ${msg}`);
    } finally {
      statusBar.dispose();
    }
  });
 
  // ── Trigger 2: Manual command via Command Palette ─────────────────────────
  //
  // The developer can run: Ctrl+Shift+P → "SMASH: Analyze Current File"
  // This does exactly the same thing as the save listener but fires on demand.
  // Useful when the developer wants to re-analyse without making a change.
  const manualCommand = vscode.commands.registerCommand('smash.analyzeFile', async () => {
    const editor = vscode.window.activeTextEditor;
 
    if (!editor) {
      vscode.window.showErrorMessage('SMASH: No active editor found.');
      return;
    }
 
    if (!SUPPORTED_LANGUAGES.includes(editor.document.languageId)) {
      const supportedNames = SUPPORTED_LANGUAGES.map(detectLanguage).join(', ');
      vscode.window.showWarningMessage(
        `SMASH: Language "${editor.document.languageId}" is not supported. ` +
        `Supported languages: ${supportedNames}.`
      );
      return;
    }
 
    const config     = vscode.workspace.getConfiguration('smash');
    const backendUrl: string = config.get('backendUrl', 'http://localhost:5000');
    const code       = editor.document.getText();
    const language   = detectLanguage(editor.document.languageId);
    const classNames = extractClassNames(code, editor.document.languageId);
    const fileName   = editor.document.fileName.split(/[\\/]/).pop() || classNames[0];
    const filePath   = editor.document.fileName;
 
    const statusBar = vscode.window.setStatusBarMessage(
      `$(sync~spin) SMASH: Analysing ${classNames.length} class(es) in ${language}…`
    );
 
    try {
      const smells = await analyzeAllClasses(backendUrl, classNames, code, language, filePath);
      await showSmellNotification(smells, fileName, filePath);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`SMASH Error: ${msg}`);
    } finally {
      statusBar.dispose();
    }
  });
 
  context.subscriptions.push(saveListener, manualCommand);
}
 
export function deactivate(): void {}