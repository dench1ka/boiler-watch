#!/usr/bin/env python3
"""Run available gates, persist evidence, and never report a blocked gate as passed."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", action="store_true", help="also run connectedDebugAndroidTest on an attached device/emulator")
    args = parser.parse_args()
    output = PROJECT / "artifacts/verification"
    output.mkdir(parents=True, exist_ok=True)
    gates = []

    def run(name, command, blocked_on_failure=False):
        result = subprocess.run(command, cwd=PROJECT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (output / (name + ".log")).write_text(result.stdout)
        status = "passed" if result.returncode == 0 else "blocked" if blocked_on_failure else "failed"
        gates.append({"gate": name, "command": command, "status": status, "exit_code": result.returncode})
        print(f"{name}: {status}", flush=True)
        if result.returncode:
            print(result.stdout, flush=True)
        return result.returncode == 0

    run("offline-tests", [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"])
    for name in ("build", "check-environment"):
        run("shell-syntax-" + name, ["/bin/sh", "-n", "scripts/" + name + ".sh"])
    ready = run("android-prerequisites", ["/bin/sh", "scripts/check-environment.sh"], blocked_on_failure=True)
    android_ok = ready and run("android-build-tests-lint", ["/bin/sh", "scripts/build.sh", "--stacktrace"])
    if not ready:
        gates.append({"gate": "android-build-tests-lint", "status": "blocked", "reason": "JDK/SDK prerequisites unavailable"})
    if args.device:
        gradle = shutil.which("gradle")
        if not gradle:
            gradle = str(PROJECT / ".tools/gradle-8.11.1/bin/gradle")
        if android_ok:
            # The standard Gradle connected task fails if no device is attached; it cannot silently pass.
            env_dir = str(PROJECT / ".gradle-user-home")
            os.environ.setdefault("GRADLE_USER_HOME", env_dir)
            run("device-tests", [gradle, "connectedDebugAndroidTest", "--stacktrace"])
        else:
            gates.append({"gate": "device-tests", "status": "blocked", "reason": "Android build unavailable or failed"})
    else:
        gates.append({"gate": "device-tests", "status": "not_run", "reason": "Run with --device and a connected device/emulator"})
    summary = {"timestamp_utc": datetime.now(timezone.utc).isoformat(), "gates": gates}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print("Evidence: artifacts/verification/summary.json")
    if any(g["status"] == "failed" for g in gates):
        return 1
    if any(g["status"] in ("blocked", "not_run") for g in gates):
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
