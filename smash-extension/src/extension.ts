import * as vscode from 'vscode';
import * as http from 'http';

interface SmellResult {
  smell_category: string;
  smell_severity: number;
  smell_location: string;
  smell_description: string;
  smell_suggestion: string;
}


/**
 * Reads the Java source code and extracts all class names using a regular expression.
 * Returns the first matched class name or 'UnknownClass' if none are found.
 * * @param javaCode - The full Java source code string from the active editor.
 * @returns An array of string containing the extracted class names.
 */
function extractClassNames(javaCode: string): string[] {
  const classRegex = /(?:public\s+)?(?:abstract\s+)?(?:final\s+)?class\s+(\w+)/g;
  const names: string[] = [];
  let match: RegExpExecArray | null;
  while ((match = classRegex.exec(javaCode)) !== null) {
    names.push(match[1]);
  }
  return names.length > 0 ? names : ['UnknownClass'];
}


/**
 * Sends the extracted Java code and class name to the Flask backend via an HTTP POST request.
 * Handles the network communication, waits for the Ollama LLM to process the code, 
 * and parses the resulting JSON array of architectural smells.
 * * @param backendUrl - The local URL of the Flask server (e.g., http://localhost:5000).
 * @param className - The primary Java class name to analyze.
 * @param code - The raw Java source code to be sent in the payload.
 * @returns A Promise that resolves to an array of SmellResult objects.
 * @throws Will reject the Promise if the network request fails, the JSON is invalid, or the 300-second timeout is reached.
 */
function analyzeWithBackend(
  backendUrl: string,
  className: string,
  code: string
): Promise<SmellResult[]> {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify({ class_name: className, code });
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
    req.setTimeout(300_000, () => {
      req.destroy();
      reject(new Error('Request timed out (300 s). LLM may still be loading.'));
    });

    req.write(payload);
    req.end();
  });
}


/**
 * Builds the summary text displayed in the VS Code notification popup.
 * Maps over the detected smells to list their categories and severities.
 * * @param smells - The array of SmellResult objects returned from the backend.
 * @param className - The name of the analyzed Java class.
 * @returns A formatted string containing the notification summary.
 */
function buildSummary(smells: SmellResult[], className: string): string {
  if (smells.length === 0) {
    return `✅ SMASH: No architectural smells detected in ${className}.`;
  }
  const categories = smells.map((s) => `${s.smell_category} (severity ${s.smell_severity}/10)`);
  return `⚠️ SMASH [${className}]: ${smells.length} smell(s) found → ${categories.join(' | ')}`;
}


/**
 * Displays the appropriate VS Code notification based on the analysis results.
 * Shows an Information Message if no smells are found, or a Warning Message if smells are detected.
 * Handles the user's interaction with the 'See Details' button to open a detailed Webview panel.
 * * @param smells - The array of SmellResult objects.
 * @param className - The name of the analyzed Java class.
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
 * Generates the complete HTML document for the 'See Details' Webview panel.
 * Iterates through the detected smells to build formatted HTML cards containing 
 * the category, severity, location, description, and refactoring suggestions.
 * * @param smells - The array of SmellResult objects returned from the backend.
 * @param className - The name of the analyzed Java class.
 * @returns A string containing the full HTML layout, styled using native VS Code CSS variables.
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
 * A helper function that maps a numerical severity score to a specific CSS class name.
 * Used to color-code the severity badges in the Webview panel (Green for low, Orange for mid, Red for high).
 * * @param n - The severity score of the architectural smell (expected 1 to 10).
 * @returns A string representing the CSS class ('low', 'mid', or 'high').
 */
function severityClass(n: number): string {
  if (n <= 3) { return 'low'; }
  if (n <= 6) { return 'mid'; }
  return 'high';
}


/**
 * The entry point of the extension. Called automatically by VS Code on startup.
 * Registers the 'onDidSaveTextDocument' listener (Ctrl+S) and the manual command palette trigger.
 * * @param context - The extension context provided by VS Code, used to manage subscriptions and lifecycle.
 */
export function activate(context: vscode.ExtensionContext): void {
  console.log('SMASH Smell Detector is now active.');

  const saveListener = vscode.workspace.onDidSaveTextDocument(async (doc) => {
    const config = vscode.workspace.getConfiguration('smash');
    const enabled: boolean = config.get('enableOnSave', true);

    if (!enabled || doc.languageId !== 'java') {
      return;
    }

    const backendUrl: string = config.get('backendUrl', 'http://localhost:5000');
    const code = doc.getText();
    const classNames = extractClassNames(code);
    const primaryClass = classNames[0];

    const statusBar = vscode.window.setStatusBarMessage(
      `$(sync~spin) SMASH: Analysing ${primaryClass}…`
    );

    try {
      const smells = await analyzeWithBackend(backendUrl, primaryClass, code);
      await showSmellNotification(smells, primaryClass);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      vscode.window.showErrorMessage(`SMASH Error: ${msg}`);
    } finally {
      statusBar.dispose();
    }
  });

  const manualCommand = vscode.commands.registerCommand('smash.analyzeFile', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      vscode.window.showErrorMessage('SMASH: No active editor found.');
      return;
    }
    if (editor.document.languageId !== 'java') {
      vscode.window.showWarningMessage('SMASH: Please open a Java file to analyze.');
      return;
    }

    const config = vscode.workspace.getConfiguration('smash');
    const backendUrl: string = config.get('backendUrl', 'http://localhost:5000');
    const code = editor.document.getText();
    const classNames = extractClassNames(code);
    const primaryClass = classNames[0];

    const statusBar = vscode.window.setStatusBarMessage(
      `$(sync~spin) SMASH: Analysing ${primaryClass}…`
    );

    try {
      const smells = await analyzeWithBackend(backendUrl, primaryClass, code);
      await showSmellNotification(smells, primaryClass);
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