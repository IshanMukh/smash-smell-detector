@echo off
echo ============================================================
echo   SMASH Extension Builder
echo ============================================================

cd /d "%~dp0smash-extension"

:: Check Node
node --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Node.js not found. Please install from https://nodejs.org
    pause
    exit /b 1
)

echo Installing Node dependencies...
npm install

echo.
echo Compiling TypeScript...
npm run compile
if errorlevel 1 (
    echo ERROR: TypeScript compilation failed.
    pause
    exit /b 1
)

echo.
echo Packaging extension (.vsix)...
npx vsce package --no-dependencies
if errorlevel 1 (
    echo ERROR: Packaging failed.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   SUCCESS!
echo   A .vsix file has been created in smash-extension folder.
echo   To install:
echo     1. Open VS Code
echo     2. Press Ctrl+Shift+P
echo     3. Type: Extensions: Install from VSIX
echo     4. Select the .vsix file
echo ============================================================
pause
