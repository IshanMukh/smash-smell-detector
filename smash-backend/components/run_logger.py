"""
========================================================
  COMPONENT: Run Logger
  Logs each SMASH analysis run to a timestamped CSV file
========================================================

What is this component?
------------------------
Every time the developer presses Ctrl+S and SMASH completes
a full analysis run, the Run Logger creates a new CSV file
in smash-backend/logs/ named with the exact date and time.

Each row in the CSV represents one detected smell from that run.
If no smells were detected, one row is written with smell fields empty.

CSV columns:
  timestamp         — when the run happened (YYYY-MM-DD HH:MM:SS)
  project_name      — the source file name (e.g. akhq_7201.java)
  class_name        — the class that was analysed
  language          — programming language (e.g. Java)
  smell_category    — detected smell type (e.g. God Class)
  smell_severity    — severity score 1-10
  smell_location    — class where smell was found
  model_name        — LLM model used (e.g. qwen2.5-coder:3b)
  run_duration_secs — how long the full run took in seconds
  machine_os        — operating system name and version
  machine_cpu       — CPU model
  machine_ram_gb    — total RAM in gigabytes
  machine_hostname  — machine hostname
"""

import os
import csv
import platform
import psutil
from datetime import datetime


# LOGS_DIR resolves to smash-backend/logs/ using an absolute path.
# os.path.dirname is called twice — once to go up from components/ to smash-backend/.
# The folder is created automatically on first run by os.makedirs() in log_run().
LOGS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "logs")


class RunLogger:
    """
    Logs each analysis run to a timestamped CSV file.
    One CSV per Ctrl+S press. One row per detected smell.
    """

    def log_run(
        self,
        project_name: str,
        language: str,
        model_name: str,
        smells: list,
        run_duration_secs: float,
        analysed_classes: list
    ) -> str:
        """
        Creates a timestamped CSV file and writes one row per smell.
        If no smells were found, writes one row with smell fields empty.

        Parameters:
        -----------
        project_name       — source file name (e.g. akhq_7201.java)
        language           — programming language
        model_name         — LLM model name
        smells             — list of smell dicts from ResultsPublisher
        run_duration_secs  — total time taken for the full run
        analysed_classes   — list of class names that were analysed

        Returns:
        --------
        str — path to the CSV file that was created
        """

        # ── Step 1: Create logs directory if needed ───────────────────────────
        os.makedirs(LOGS_DIR, exist_ok=True)

        # ── Step 2: Build the CSV filename using timestamp ────────────────────
        # Using seconds precision so each run gets a unique file name
        # Example: 2026-05-22_14-35-42.csv
        timestamp_str = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        csv_path = os.path.join(LOGS_DIR, f"{timestamp_str}.csv")
        # ── Step 3: Collect machine information ───────────────────────────────
        # Machine info is recorded so runs can be compared across different
        # environments — for example, comparing analysis speed on a laptop CPU
        # vs a Google Colab GPU, or tracking which OS the backend was running on.
        # psutil.virtual_memory().total gives total RAM in bytes — we convert
        # to GB by dividing twice by 1024 (bytes → KB → MB → GB).
        # ── Step 3: Collect machine information ───────────────────────────────
        machine_os       = f"{platform.system()} {platform.version()}"
        machine_cpu      = platform.processor() or platform.machine()
        machine_ram_gb   = round(psutil.virtual_memory().total / (1024 ** 3), 2)
        machine_hostname = platform.node()
        timestamp_readable = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        # ── Step 4: Write the CSV ─────────────────────────────────────────────
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=[
                "timestamp",
                "project_name",
                "class_name",
                "language",
                "smell_category",
                "smell_severity",
                "smell_location",
                "model_name",
                "run_duration_secs",
                "machine_os",
                "machine_cpu",
                "machine_ram_gb",
                "machine_hostname"
            ])
            writer.writeheader()

            if smells:
                # One row per smell
                for smell in smells:
                    writer.writerow({
                        "timestamp"         : timestamp_readable,
                        "project_name"      : project_name,
                        "class_name"        : smell.get("smell_location", ""),
                        "language"          : language,
                        "smell_category"    : smell.get("smell_category", ""),
                        "smell_severity"    : smell.get("smell_severity", ""),
                        "smell_location"    : smell.get("smell_location", ""),
                        "model_name"        : model_name,
                        "run_duration_secs" : round(run_duration_secs, 2),
                        "machine_os"        : machine_os,
                        "machine_cpu"       : machine_cpu,
                        "machine_ram_gb"    : machine_ram_gb,
                        "machine_hostname"  : machine_hostname
                    })
            else:
                # No smells found — write one row with smell fields empty
                writer.writerow({
                    "timestamp"         : timestamp_readable,
                    "project_name"      : project_name,
                    "class_name"        : ", ".join(analysed_classes),
                    "language"          : language,
                    "smell_category"    : "None detected",
                    "smell_severity"    : "",
                    "smell_location"    : "",
                    "model_name"        : model_name,
                    "run_duration_secs" : round(run_duration_secs, 2),
                    "machine_os"        : machine_os,
                    "machine_cpu"       : machine_cpu,
                    "machine_ram_gb"    : machine_ram_gb,
                    "machine_hostname"  : machine_hostname
                })

        print(f"[RunLogger] CSV saved: {csv_path}")
        return csv_path