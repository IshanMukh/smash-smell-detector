import * as vscode from 'vscode';
import * as http from 'http';

// ─────────────────────────────────────────────────────────────────────────────
//  LANGUAGE INDEPENDENCE CHANGE — Overview
// ─────────────────────────────────────────────────────────────────────────────
//
// The old version of this file had three Java-specific hardcodings:
//
//   1. A check: if (doc.languageId !== 'java') return;
//      This blocked ALL non-Java files from being analysed.
//
//   2. extractClassNames() used a Java-only regex that matched
//      "public class MyClass" — other languages were not covered.
//
//   3. The language was never sent to the backend at all —
//      the backend just assumed Java.
//
// What we changed:
//
//   1. SUPPORTED_LANGUAGES — a list of language IDs that SMASH accepts.
//      Easy to extend: just add a new entry to the list and its helper.
//
//   2. detectLanguage() — maps VS Code's internal languageId
//      (e.g. "csharp") to a human-readable name (e.g. "C#") for the LLM.
//
//   3. extractClassNames() — now takes a language parameter and uses
//      the right regex for each language instead of always using Java syntax.
//
//   4. analyzeWithBackend() — now sends { class_name, code, language }
//      instead of just { class_name, code }.
//
//   Nothing else in the pipeline changed — the modular structure is intact.
// ─────────────────────────────────────────────────────────────────────────────


// ─────────────────────────────────────────────
//  Types
// ─────────────────────────────────────────────

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
    // For any future language added to SUPPORTED_LANGUAGES,
    // add a case here with its proper display name.
    default:            return languageId;  // fallback: use the ID as-is
  }
}


/**
 * Extracts the primary class name from source code.
 *
 * Why is this language-aware?
 * ---------------------------
 * Different languages have different syntax for declaring a class.
 * Using a Java-only regex on Python code would fail to find any class name.
 *
 * Each language branch uses a regex tailored to that language's syntax:
 *   Java       : public class MyClass { ... }
 *   Python     : class MyClass:
 *   C#         : public class MyClass { ... }
 *   C++        : class MyClass { ... }
 *   TypeScript : class MyClass { ... }  or  export class MyClass
 *   JavaScript : class MyClass { ... }  or  export class MyClass
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
      // Java: optional modifiers before "class", then the class name
      // Example: "public abstract class MyService {"
      classRegex = /(?:public\s+)?(?:abstract\s+)?(?:final\s+)?class\s+(\w+)/g;
      break;

    case 'python':
      // Python: "class" at the start of a line, then name, then colon
      // Example: "class MyService:"  or  "class MyService(BaseClass):"
      // The (?:...) group matches the optional parent class — we don't capture it
      classRegex = /^class\s+(\w+)(?:\s*[\(:])/gm;
      break;

    case 'csharp':
      // C#: optional access modifier, optional abstract/sealed, then "class"
      // Example: "public sealed class MyService {"
      classRegex = /(?:public\s+|private\s+|internal\s+)?(?:abstract\s+|sealed\s+)?class\s+(\w+)/g;
      break;

    case 'cpp':
      // C++: just "class" followed by the name
      // Example: "class MyService {"  or  "class MyService : public BaseClass {"
      classRegex = /\bclass\s+(\w+)/g;
      break;

    case 'typescript':
    case 'javascript':
      // TypeScript and JavaScript share the same class syntax
      // Example: "export class MyService {"  or  "class MyService {"
      classRegex = /(?:export\s+)?(?:abstract\s+)?class\s+(\w+)/g;
      break;

    default:
      // For any other language, use a simple generic regex.
      // Most modern languages use "class ClassName" syntax.
      classRegex = /\bclass\s+(\w+)/g;
      break;
  }

  // Run the regex against the code and collect all matches
  const names: string[] = [];
  let match: RegExpExecArray | null;
  while ((match = classRegex.exec(code)) !== null) {
    names.push(match[1]);
  }

  // Return found names, or a fallback if none were found.
  // Some files (like standalone Python scripts) might not have a class at all.
  return names.length > 0 ? names : ['UnknownClass'];
}


/**
 * POST the source code to the SMASH backend and return the parsed smell array.
 *
 * WHAT CHANGED vs old version:
 * ----------------------------
 * Old payload: { class_name, code }
 * New payload: { class_name, code, language }
 *
 * The "language" field tells the backend what language we are analysing.
 * The backend's ContextRetriever uses this to fill the LLM prompt correctly.
 *
 * Parameters:
 *   backendUrl — the URL of the Flask backend (default: http://localhost:5000)
 *   className  — the primary class name extracted from the code
 *   code       — the full source code to analyse
 *   language   — the human-readable language name (e.g. "Java", "Python")
 */
function analyzeWithBackend(
  backendUrl: string,
  className: string,
  code: string,
  language: string   // ← NEW parameter
): Promise<SmellResult[]> {
  return new Promise((resolve, reject) => {

    // Build the JSON payload — language is now included
    const payload = JSON.stringify({ class_name: className, code, language });

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
 * Builds a human-readable one-line summary of the smell results.
 * Shown in the VS Code notification bar.
 */
function buildSummary(smells: SmellResult[], className: string): string {
  if (smells.length === 0) {
    return `✅ SMASH: No architectural smells detected in ${className}.`;
  }

  const categories = smells.map(
    (s) => `${s.smell_category} (severity ${s.smell_severity}/10)`
  );
  return `⚠️ SMASH [${className}]: ${smells.length} smell(s) found → ${categories.join(' | ')}`;
}


/**
 * Shows a VS Code notification with the analysis results.
 * For files with smells, offers a "See Details" button that opens
 * a formatted webview panel alongside the editor.
 */
async function showSmellNotification(smells: SmellResult[], className: string): Promise<void> {
  const summary = buildSummary(smells, className);

  if (smells.length === 0) {
    vscode.window.showInformationMessage(summary);
    return;
  }

  const choice = await vscode.window.showWarningMessage(summary, 'See Details', 'Dismiss');

  if (choice === 'See Details') {
    const panel = vscode.window.createWebviewPanel(
      'smashDetails',
      `SMASH Results – ${className}`,
      vscode.ViewColumn.Beside,
      {}
    );
    panel.webview.html = buildDetailHtml(smells, className);
  }
}


/**
 * Generates a styled HTML page for the detail webview panel.
 * Shown when the developer clicks "See Details" in the notification.
 */
function buildDetailHtml(smells: SmellResult[], className: string): string {
  const rows = smells
    .map(
      (s) => `
      <div class="card">
        <div class="header">
          <span class="category">${s.smell_category}</span>
          <span class="severity sev-${severityClass(s.smell_severity)}">Severity ${s.smell_severity}/10</span>
        </div>
        <p><strong>Location:</strong> ${s.smell_location}</p>
        <p><strong>Description:</strong> ${s.smell_description}</p>
        <p><strong>Suggestion:</strong> ${s.smell_suggestion}</p>
      </div>`
    )
    .join('\n');

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<style>
  body { font-family: var(--vscode-font-family); padding: 16px; color: var(--vscode-editor-foreground); background: var(--vscode-editor-background); }
  h1   { font-size: 1.2em; margin-bottom: 12px; }
  .card { border: 1px solid var(--vscode-panel-border); border-radius: 6px; padding: 12px; margin-bottom: 12px; }
  .header { display: flex; justify-content: space-between; margin-bottom: 8px; }
  .category { font-weight: bold; font-size: 1.05em; }
  .severity { padding: 2px 8px; border-radius: 4px; font-size: 0.85em; }
  .sev-low  { background: #2e7d32; color: #fff; }
  .sev-mid  { background: #f57f17; color: #fff; }
  .sev-high { background: #c62828; color: #fff; }
  p { margin: 4px 0; font-size: 0.92em; }
</style>
</head>
<body>
<h1>🔍 SMASH – Architectural Smell Report for <code>${className}</code></h1>
${rows}
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

  // ── Trigger 1: Ctrl+S auto-analysis ─────────────────────────────────────
  //
  // onDidSaveTextDocument fires every time any file is saved in VS Code.
  // We check:
  //   (a) Is SMASH enabled in settings?
  //   (b) Is the saved file written in a language SMASH supports?
  //
  // WHAT CHANGED vs old version:
  //   Old: if (doc.languageId !== 'java') return;
  //   New: if (!SUPPORTED_LANGUAGES.includes(doc.languageId)) return;
  //
  // This single change opens SMASH to all languages in the SUPPORTED_LANGUAGES
  // list instead of blocking everything except Java.
  const saveListener = vscode.workspace.onDidSaveTextDocument(async (doc) => {
    const config = vscode.workspace.getConfiguration('smash');
    const enabled: boolean = config.get('enableOnSave', true);

    // Check if auto-analysis is enabled in VS Code settings
    if (!enabled) {
      return;
    }

    // Check if the saved file is in a language SMASH supports
    // SUPPORTED_LANGUAGES.includes() returns true if the languageId is in the list
    if (!SUPPORTED_LANGUAGES.includes(doc.languageId)) {
      return;
    }

    // Get backend URL from VS Code settings (default: http://localhost:5000)
    const backendUrl: string = config.get('backendUrl', 'http://localhost:5000');

    const code = doc.getText();

    // Detect the language — maps VS Code's internal ID to a human-readable name
    // e.g. "csharp" → "C#",  "python" → "Python",  "java" → "Java"
    const language = detectLanguage(doc.languageId);

    // Extract class names using the language-appropriate regex
    const classNames = extractClassNames(code, doc.languageId);

    // Use the first detected class name as the primary identifier
    const primaryClass = classNames[0];

    // Show a spinning indicator in the VS Code status bar while we wait
    const statusBar = vscode.window.setStatusBarMessage(
      `$(sync~spin) SMASH: Analysing ${primaryClass} (${language})…`
    );

    try {
      // Send code + language to the backend for analysis
      const smells = await analyzeWithBackend(backendUrl, primaryClass, code, language);
      await showSmellNotification(smells, primaryClass);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`SMASH Error: ${msg}`);
    } finally {
      // Always dispose the status bar message when done (success or error)
      statusBar.dispose();
    }
  });

  // ── Trigger 2: Manual command via Command Palette ────────────────────────
  //
  // The developer can also run: Ctrl+Shift+P → "SMASH: Analyze Current File"
  // This works the same as the save listener but fires on demand.
  //
  // WHAT CHANGED vs old version:
  //   Old: if (editor.document.languageId !== 'java') { show warning; return; }
  //   New: if (!SUPPORTED_LANGUAGES.includes(...)) { show warning with list; return; }
  const manualCommand = vscode.commands.registerCommand('smash.analyzeFile', async () => {
    const editor = vscode.window.activeTextEditor;

    if (!editor) {
      vscode.window.showErrorMessage('SMASH: No active editor found.');
      return;
    }

    // Check if the current file is in a supported language
    if (!SUPPORTED_LANGUAGES.includes(editor.document.languageId)) {
      // Build a friendly message listing what IS supported
      const supportedNames = SUPPORTED_LANGUAGES.map(detectLanguage).join(', ');
      vscode.window.showWarningMessage(
        `SMASH: Language "${editor.document.languageId}" is not supported. ` +
        `Supported languages: ${supportedNames}.`
      );
      return;
    }

    const config = vscode.workspace.getConfiguration('smash');
    const backendUrl: string = config.get('backendUrl', 'http://localhost:5000');

    const code = editor.document.getText();
    const language = detectLanguage(editor.document.languageId);
    const classNames = extractClassNames(code, editor.document.languageId);
    const primaryClass = classNames[0];

    const statusBar = vscode.window.setStatusBarMessage(
      `$(sync~spin) SMASH: Analysing ${primaryClass} (${language})…`
    );

    try {
      const smells = await analyzeWithBackend(backendUrl, primaryClass, code, language);
      await showSmellNotification(smells, primaryClass);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`SMASH Error: ${msg}`);
    } finally {
      statusBar.dispose();
    }
  });

  // Register both listeners so VS Code cleans them up when the extension is unloaded
  context.subscriptions.push(saveListener, manualCommand);
}

export function deactivate(): void {}
