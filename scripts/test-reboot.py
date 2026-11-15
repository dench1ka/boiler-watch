#!/usr/bin/env python3
"""Destructive only to this app's data on a local emulator. Never run on a real phone.
Prove BOOT_COMPLETED recovery without launching/unlocking the app after reboot.
"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "ru.dacha.boiler"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5556")
    args = parser.parse_args()
    # Guard before any ADB mutation: integration fixtures clear application data.
    if not args.serial.startswith("emulator-"):
        parser.error("Only local emulator-* serials are allowed; real device data will not be touched")
    adb = str(ROOT / ".tools/android-sdk/platform-tools/adb")
    if not Path(adb).is_file():
        adb = "adb"
    out = ROOT / "artifacts/verification/reboot"
    out.mkdir(parents=True, exist_ok=True)

    def call(*argv, stdin=None, timeout=20, check=True):
        result = subprocess.run([adb, "-s", args.serial, *argv], input=stdin,
                                text=True, capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f"ADB command failed ({argv[0]}): {result.stderr}")
        return result.stdout.strip()

    def shell(*argv, **kwargs):
        return call("shell", *argv, **kwargs)

    assert shell("getprop", "ro.kernel.qemu") == "1", "Not an emulator"
    sdk = int(shell("getprop", "ro.build.version.sdk"))
    assert 23 <= sdk <= 27, "Full background camera restart is deliberately limited to Android6–8.1"
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    call("install", "-r", str(apk), timeout=60)
    cases = []

    def prepare(enabled):
        assert shell("pm", "clear", PACKAGE) == "Success"
        shell("pm", "grant", PACKAGE, "android.permission.CAMERA")
        # Native preferences fixture; no password/real recipient or external request.
        now = int(time.time() * 1000)
        next_time = (now // 60_000 + 2) * 60_000
        zone_name = shell("getprop", "persist.sys.timezone") or "UTC"
        times = datetime.fromtimestamp(next_time / 1000, ZoneInfo(zone_name)).strftime("%H:%M")
        prefs = ET.Element("map")
        ET.SubElement(prefs, "boolean", name="enabled", value=str(enabled).lower())
        ET.SubElement(prefs, "long", name="next", value=str(next_time))
        ET.SubElement(prefs, "string", name="times").text = times
        # Deliberately unreachable loopback SMTP. No external destination can be contacted.
        for key, value in {"host": "127.0.0.1", "user": "fixture", "from": "fixture@example.invalid", "to": "fixture@example.invalid"}.items():
            ET.SubElement(prefs, "string", name=key).text = value
        ET.SubElement(prefs, "int", name="port", value="9")
        data = ET.tostring(prefs, encoding="unicode")
        shell("run-as", PACKAGE, "mkdir", "-p", "shared_prefs")
        # -T is essential: stdin must reach the app-owned file unmodified.
        call("shell", "-T", "run-as", PACKAGE, "sh", "-c",
             "'cat > shared_prefs/boiler.xml'", stdin=data)
        launch = shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        assert "Status: ok" in launch, launch
        shell("input", "keyevent", "KEYCODE_HOME")
        # Android 7 persists its stopped/notLaunched package flags on a delayed handler.
        # A reboot immediately after this first launch can restore the earlier "stopped"
        # package state, even though dumpsys already shows stopped=false in memory.
        # Allow the package manager's delayed write before testing BOOT_COMPLETED.
        time.sleep(15)
        # This raw fixture must have the same durability as SharedPreferences.commit().
        shell("sync")
        saved = ET.fromstring(shell("run-as", PACKAGE, "cat", "shared_prefs/boiler.xml"))
        assert saved.find("boolean[@name='enabled']").get("value") == str(enabled).lower()
        (out / ("prepared-enabled-package.txt" if enabled else "prepared-disabled-package.txt")).write_text(shell("dumpsys", "package", PACKAGE))
        return next_time

    def reboot(label):
        before = shell("cat", "/proc/sys/kernel/random/boot_id")
        call("reboot")
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            try:
                if (shell("getprop", "sys.boot_completed", timeout=5) == "1" and
                        shell("cat", "/proc/sys/kernel/random/boot_id", timeout=5) != before):
                    print(f"{label}: system rebooted; app not opened after reboot", flush=True)
                    return
            except (RuntimeError, subprocess.TimeoutExpired):
                pass
            time.sleep(2)
        raise RuntimeError("Emulator did not finish rebooting within 180 seconds")

    def services():
        return shell("dumpsys", "activity", "services", PACKAGE)

    def prefs():
        return ET.fromstring(shell("run-as", PACKAGE, "cat", "shared_prefs/boiler.xml"))

    def has_daily_alarm(dump):
        lines = dump.splitlines()
        return any("tag=*walarm*:daily" in line and i > 0 and
                   PACKAGE in lines[i - 1] and "Alarm{" in lines[i - 1]
                   for i, line in enumerate(lines))

    try:
        next_time = prepare(True)
        reboot("enabled")
        deadline = time.monotonic() + 45
        service = ""
        while time.monotonic() < deadline:
            service = services()
            if "ru.dacha.boiler/.MonitorService" in service and "isForeground=true" in service:
                break
            time.sleep(1)
        assert "ru.dacha.boiler/.MonitorService" in service and "isForeground=true" in service, service
        alarms = shell("dumpsys", "alarm")
        (out / "enabled-alarms.txt").write_text(alarms)
        assert has_daily_alarm(alarms), "Daily alarm not restored"
        event_log = shell("run-as", PACKAGE, "cat", "files/events.txt")
        assert "android.intent.action.BOOT_COMPLETED" in event_log, "No real boot broadcast recorded"
        state = prefs()
        assert state.find("boolean[@name='enabled']").get("value") == "true"
        assert int(state.find("long[@name='next']").get("value")) == next_time
        (out / "enabled-services.txt").write_text(service)
        (out / "enabled-alarms.txt").write_text(alarms)
        (out / "enabled-events.txt").write_text(event_log)
        cases.append({"case": "enabled_restores_service_and_daily_alarm", "status": "passed"})
        print("enabled: foreground service and daily alarm restored automatically", flush=True)
        print("enabled: waiting for scheduled camera capture after reboot (no external SMTP)", flush=True)
        deadline = time.monotonic() + 150
        captured = None
        while time.monotonic() < deadline and captured is None:
            folders = shell("run-as", PACKAGE, "ls", "files/reports/pending").splitlines()
            for folder in folders:
                if folder.startswith("."):
                    continue
                try:
                    metadata = json.loads(shell("run-as", PACKAGE, "cat", "files/reports/pending/" + folder + "/report.json"))
                    if metadata.get("slot") != next_time:
                        continue
                    jpeg = subprocess.run([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE,
                        "cat", "files/reports/pending/" + folder + "/photo.jpg"], capture_output=True, check=True, timeout=15).stdout
                    assert jpeg.startswith(bytes([0xff, 0xd8])) and len(jpeg) > 1000
                    (out / "scheduled-after-reboot.jpg").write_bytes(jpeg)
                    (out / "scheduled-after-reboot.json").write_text(json.dumps(metadata, indent=2) + "\n")
                    captured = metadata
                    break
                except (RuntimeError, json.JSONDecodeError):
                    pass
            if captured is None:
                time.sleep(2)
        assert captured is not None, "No scheduled JPEG captured after reboot"
        cases.append({"case": "scheduled_photo_captured_after_reboot_without_opening_app", "status": "passed"})
        print("enabled: scheduled JPEG captured without opening the app", flush=True)

        prepare(False)
        reboot("disabled")
        # Allow delayed BOOT_COMPLETED delivery, then ensure the explicit stop is respected.
        for _ in range(8):
            assert "ru.dacha.boiler/.MonitorService" not in services()
            time.sleep(1)
        assert prefs().find("boolean[@name='enabled']").get("value") == "false"
        alarms = shell("dumpsys", "alarm")
        assert not has_daily_alarm(alarms)
        (out / "disabled-services.txt").write_text(services())
        cases.append({"case": "disabled_stays_disabled_after_reboot", "status": "passed"})
        print("disabled: service not started; daily alarm absent", flush=True)
        report = {"status": "passed", "serial": args.serial, "api": sdk,
                  "app_opened_after_reboot": False, "cases": cases}
        (out / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    except Exception as error:
        diagnostics = {
            "failure-users": ["dumpsys", "user"],
            "failure-package": ["dumpsys", "package", PACKAGE],
            "failure-services": ["dumpsys", "activity", "services", PACKAGE],
            "failure-alarms": ["dumpsys", "alarm"],
            "failure-logcat": ["logcat", "-d", "-v", "time"],
            "failure-preferences": ["run-as", PACKAGE, "cat", "shared_prefs/boiler.xml"],
        }
        for name, command in diagnostics.items():
            try:
                result = subprocess.run([adb, "-s", args.serial, "shell", *command], text=True,
                                        capture_output=True, timeout=10)
                (out / (name + ".txt")).write_text(result.stdout + result.stderr)
            except Exception:
                pass
        (out / "summary.json").write_text(json.dumps({"status": "failed", "error": str(error), "cases": cases}, indent=2) + "\n")
        raise
    finally:
        call("shell", "am", "force-stop", PACKAGE, check=False)


if __name__ == "__main__":
    main()
