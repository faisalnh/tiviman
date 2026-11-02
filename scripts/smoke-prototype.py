#!/usr/bin/env python3
"""Fixture-only smoke test. Refuses to send keys unless the prototype is foreground.

Starts only Tiviman (faisalnh.tiviman); never touches other packages.
Run from a fresh prototype launch in its initial guide state.
Screenshots and UI dumps stay under ignored private/.
"""
import pathlib
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "private" / ("prototype-smoke-" + time.strftime("%Y%m%d-%H%M%S"))
ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
PACKAGE = "faisalnh.tiviman"


def adb(*args):
    return subprocess.check_output([ADB, "-s", "emulator-5554", *args])


def guard():
    activity = adb("shell", "dumpsys", "activity", "activities").decode()
    resumed = [line for line in activity.splitlines() if "topResumedActivity=" in line]
    if len(resumed) != 1 or PACKAGE not in resumed[0]:
        raise RuntimeError("Prototype is not foreground; refusing remote input")


def key(value):
    guard()
    adb("shell", "input", "keyevent", value)
    time.sleep(0.15)


def capture(name, expected):
    guard()
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    adb("shell", "uiautomator", "dump", "/sdcard/fixturetv-smoke.xml")
    raw = adb("exec-out", "cat", "/sdcard/fixturetv-smoke.xml")
    (OUT / (name + ".xml")).write_bytes(raw)
    descriptions = [node.attrib.get("content-desc", "") for node in ET.fromstring(raw).iter()]
    if not any(expected in value for value in descriptions):
        raise AssertionError(f"{name}: missing expected state {expected!r}")
    print("PASS", name)


OUT.mkdir(parents=True, exist_ok=False)
capture("01-guide", "GUIDE: Horizon")
key("DPAD_DOWN")
capture("02-focus-not-tuned", "GUIDE: Atelier, Atelier: Evening edition. Tuned Horizon")
key("DPAD_CENTER")
capture("03-tuned-preview", "GUIDE: Atelier, Atelier: Evening edition. Tuned Atelier")
key("DPAD_CENTER")
capture("04-fullscreen", "FULLSCREEN: Atelier, overlay false")
key("DPAD_CENTER")
capture("04-player-info", "FULLSCREEN: Atelier, overlay true")
key("BACK")
key("BACK")
capture("05-restored-guide", "GUIDE: Atelier, Atelier: Evening edition. Tuned Atelier")
key("DPAD_RIGHT")
capture("06-future", "GUIDE: Atelier, A short journey. Tuned Atelier")
key("DPAD_LEFT")
key("DPAD_LEFT")
capture("07-groups", "GROUPS: Atelier")
key("DPAD_DOWN")
key("DPAD_CENTER")
capture("08-selected-group", "GUIDE: Horizon, Horizon: Evening edition. Tuned Atelier")
print("Private results:", OUT)
