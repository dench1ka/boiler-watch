"""Execute the real prerequisite/build scripts with isolated tool fixtures.
These tests check the build launcher, not Kotlin/Android behavior.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]


class EnvironmentCheckTest(unittest.TestCase):
    def setUp(self):
        self.sandbox = tempfile.TemporaryDirectory(prefix="boiler-env-")
        self.addCleanup(self.sandbox.cleanup)
        self.root = Path(self.sandbox.name)
        (self.root / "scripts").mkdir()
        for name in ("check-environment.sh", "build.sh"):
            shutil.copy2(PROJECT / "scripts" / name, self.root / "scripts" / name)
        self.jdk = self.root / "jdk with spaces"
        (self.jdk / "bin").mkdir(parents=True)
        self.sdk = self.root / "sdk with spaces"
        (self.sdk / "platforms/android-35").mkdir(parents=True)
        (self.sdk / "platforms/android-35/android.jar").touch()
        (self.sdk / "build-tools/35.0.0").mkdir(parents=True)
        self.java('openjdk version "17.0.12"')
        self.tool(self.jdk / "bin/javac", 'echo "javac 17.0.12"')
        self.env = {k: v for k, v in os.environ.items() if k not in
                    ("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME")}
        self.env.update(JAVA_HOME=str(self.jdk), ANDROID_HOME=str(self.sdk))

    def tool(self, path, body):
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o755)

    def java(self, version):
        self.tool(self.jdk / "bin/java", f"printf '%s\\n' '{version}' >&2")

    def run_script(self, name="check-environment.sh", *args):
        return subprocess.run(["/bin/sh", str(self.root / "scripts" / name), *args],
                              cwd="/", env=self.env, text=True, capture_output=True)

    def test_accepts_jdk17_and_sdk_paths_with_spaces(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Environment ready", result.stdout)

    def test_accepts_jdk21(self):
        self.java('java version "21.0.1"')
        self.assertEqual(0, self.run_script().returncode)

    def test_rejects_jdk11(self):
        self.java('openjdk version "11.0.20"')
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("JDK 17+", result.stderr)

    def test_rejects_unparseable_java(self):
        self.java("unknown runtime")
        self.assertNotEqual(0, self.run_script().returncode)

    def test_rejects_runtime_without_compiler(self):
        (self.jdk / "bin/javac").unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("javac", result.stderr)

    def test_rejects_broken_java_home(self):
        self.env["JAVA_HOME"] = str(self.root / "missing-jdk")
        self.assertNotEqual(0, self.run_script().returncode)

    def test_rejects_missing_sdk(self):
        del self.env["ANDROID_HOME"]
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ANDROID_HOME", result.stderr)

    def test_supports_android_sdk_root(self):
        self.env["ANDROID_SDK_ROOT"] = self.env.pop("ANDROID_HOME")
        self.assertEqual(0, self.run_script().returncode)

    def test_supports_local_properties(self):
        del self.env["ANDROID_HOME"]
        (self.root / "local.properties").write_text(f"sdk.dir={self.sdk}\n")
        self.assertEqual(0, self.run_script().returncode)

    def test_rejects_missing_platform(self):
        (self.sdk / "platforms/android-35/android.jar").unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Platform 35", result.stderr)

    def test_rejects_missing_build_tools(self):
        (self.sdk / "build-tools/35.0.0").rmdir()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Build Tools", result.stderr)

    def test_build_does_not_download_when_prerequisites_fail(self):
        self.env["JAVA_HOME"] = str(self.root / "missing-jdk")
        result = self.run_script("build.sh")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / ".tools").exists())
        self.assertIn("JDK", result.stderr)

    def test_build_propagates_test_failure_and_preserves_arguments(self):
        tools = self.root / "bin"
        tools.mkdir()
        record = self.root / "gradle-arguments.txt"
        self.tool(tools / "gradle", 'printf "%s\\n" "$@" > "$ARGUMENT_LOG"\nexit 7')
        self.env["PATH"] = str(tools) + os.pathsep + self.env["PATH"]
        self.env["ARGUMENT_LOG"] = str(record)
        result = self.run_script("build.sh", "--stacktrace", "-Pexample=has spaces")
        self.assertEqual(7, result.returncode)
        self.assertEqual(["check", "lintRelease", "assembleDebug", "assembleRelease", "assembleDebugAndroidTest", "--stacktrace", "-Pexample=has spaces"],
                         record.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
