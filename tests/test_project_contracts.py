"""Validate install-time Android manifest/resource contracts without an SDK.
Not a substitute for Android Lint, compilation, or device tests.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
MAIN = PROJECT / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class ManifestContractTest(unittest.TestCase):
    def setUp(self):
        self.manifest = ET.parse(MAIN / "AndroidManifest.xml").getroot()
        self.app = self.manifest.find("application")

    def test_all_xml_resources_parse(self):
        for filename in MAIN.rglob("*.xml"):
            with self.subTest(filename=filename):
                ET.parse(filename)

    def test_components_have_implementations(self):
        source = "\n".join(p.read_text() for p in (MAIN / "java").rglob("*.kt"))
        for component in self.app:
            if component.tag in {"activity", "service", "receiver"}:
                name = component.get(ANDROID + "name").split(".")[-1]
                self.assertRegex(source, rf"\bclass\s+{name}\b")

    def test_background_and_camera_permissions_are_declared(self):
        permissions = {n.get(ANDROID + "name") for n in self.manifest.findall("uses-permission")}
        for suffix in ["CAMERA", "INTERNET", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED",
                       "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CAMERA", "POST_NOTIFICATIONS",
                       "SCHEDULE_EXACT_ALARM"]:
            self.assertIn("android.permission." + suffix, permissions)

    def test_private_components_cannot_be_started_by_other_apps(self):
        for component in list(self.app.findall("receiver")) + list(self.app.findall("service")):
            self.assertEqual("false", component.get(ANDROID + "exported"))

    def test_credentials_and_photos_are_excluded_from_backup(self):
        self.assertEqual("false", self.app.get(ANDROID + "allowBackup"))
        self.assertEqual("false", self.app.get(ANDROID + "usesCleartextTraffic"))

    def test_camera_service_type_is_declared(self):
        service = self.app.find("service")
        self.assertEqual("camera", service.get(ANDROID + "foregroundServiceType"))

    def test_boot_and_clock_changes_restore_schedule(self):
        actions = {a.get(ANDROID + "name") for a in self.app.findall("receiver/intent-filter/action")}
        for action in ["BOOT_COMPLETED", "MY_PACKAGE_REPLACED", "TIME_SET", "TIMEZONE_CHANGED"]:
            self.assertIn("android.intent.action." + action, actions)

    def test_launcher_activity_is_explicitly_exported(self):
        activities = self.app.findall("activity")
        launchers = [a for a in activities if any(
            n.get(ANDROID + "name") == "android.intent.category.LAUNCHER"
            for n in a.findall("intent-filter/category"))]
        self.assertEqual(1, len(launchers))
        self.assertEqual("true", launchers[0].get(ANDROID + "exported"))

    def test_app_icon_and_theme_exist(self):
        icon = self.app.get(ANDROID + "icon").removeprefix("@drawable/")
        self.assertTrue((MAIN / "res/drawable" / (icon + ".xml")).is_file())
        theme = self.app.get(ANDROID + "theme").removeprefix("@style/")
        styles = ET.parse(MAIN / "res/values/styles.xml").getroot()
        self.assertIn(theme, [s.get("name") for s in styles.findall("style")])


if __name__ == "__main__":
    unittest.main()
