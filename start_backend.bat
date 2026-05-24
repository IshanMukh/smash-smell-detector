@echo off
echo ============================================================
echo   SMASH Backend Launcher
echo ============================================================

cd /d "%~dp0smash-backend"

:: Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found. Please install Python 3.9+ from https://python.org
    pause
    exit /b 1
)

:: Install dependencies if not already installed
echo Installing Python dependencies...
pip install -r requirements.txt

echo.
echo Starting SMASH backend on http://localhost:5000 ...
echo Press Ctrl+C to stop.
echo.
python app.py

pause
