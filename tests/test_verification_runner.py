"""Regression tests for honest pass/fail/blocked aggregation by the verifier."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]


class VerificationRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="boiler-verify-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "tests").mkdir()
        shutil.copy2(PROJECT / "scripts/verify.py", self.root / "scripts/verify.py")
        self.fixture_test(True)
        self.shell("check-environment", 0)
        self.shell("build", 0)
        self.env = os.environ.copy()

    def fixture_test(self, passing):
        (self.root / "tests/test_fixture.py").write_text(
            "import unittest\nclass Fixture(unittest.TestCase):\n"
            f"    def test_fixture(self): self.assertTrue({passing})\n")

    def shell(self, name, exit_code):
        (self.root / f"scripts/{name}.sh").write_text(f"#!/bin/sh\necho fixture-{name}\nexit {exit_code}\n")

    def run_verifier(self, *args):
        result = subprocess.run([sys.executable, str(self.root / "scripts/verify.py"), *args],
                                cwd="/", env=self.env, text=True, capture_output=True)
        summary = json.loads((self.root / "artifacts/verification/summary.json").read_text())
        return result, {g["gate"]: g for g in summary["gates"]}

    def test_missing_prerequisites_are_blocked_not_passed(self):
        self.shell("check-environment", 1)
        result, gates = self.run_verifier("--device")
        self.assertEqual(2, result.returncode)
        self.assertEqual("passed", gates["offline-tests"]["status"])
        self.assertEqual("blocked", gates["android-build-tests-lint"]["status"])
        self.assertEqual("blocked", gates["device-tests"]["status"])

    def test_offline_failure_is_not_hidden_by_android_blocker(self):
        self.fixture_test(False)
        self.shell("check-environment", 1)
        result, gates = self.run_verifier()
        self.assertEqual(1, result.returncode)
        self.assertEqual("failed", gates["offline-tests"]["status"])

    def test_android_build_failure_is_reported_as_failure(self):
        self.shell("build", 7)
        result, gates = self.run_verifier("--device")
        self.assertEqual(1, result.returncode)
        self.assertEqual("failed", gates["android-build-tests-lint"]["status"])
        self.assertEqual(7, gates["android-build-tests-lint"]["exit_code"])
        self.assertEqual("blocked", gates["device-tests"]["status"])

    def test_omitted_device_tests_prevent_full_success(self):
        result, gates = self.run_verifier()
        self.assertEqual(2, result.returncode)
        self.assertEqual("not_run", gates["device-tests"]["status"])

    def test_failed_device_tests_prevent_full_success(self):
        self.device_gradle(4)
        result, gates = self.run_verifier("--device")
        self.assertEqual(1, result.returncode)
        self.assertEqual("failed", gates["device-tests"]["status"])

    def test_success_requires_device_gate_and_keeps_logs(self):
        self.device_gradle(0)
        result, gates = self.run_verifier("--device")
        self.assertEqual(0, result.returncode)
        self.assertTrue(all(g["status"] == "passed" for g in gates.values()))
        self.assertIn("connectedDebugAndroidTest", (self.root / "artifacts/verification/device-tests.log").read_text())

    def device_gradle(self, exit_code):
        folder = self.root / "bin"
        folder.mkdir()
        script = folder / "gradle"
        script.write_text(f'#!/bin/sh\nprintf "%s\\n" "$@"\nexit {exit_code}\n')
        script.chmod(0o755)
        self.env["PATH"] = str(folder) + os.pathsep + self.env["PATH"]


if __name__ == "__main__":
    unittest.main()
