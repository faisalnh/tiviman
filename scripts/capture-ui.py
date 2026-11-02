#!/usr/bin/env python3
"""Capture a local-only UI reference, optionally after sending remote keys."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("name")
parser.add_argument("keys", nargs="*")
parser.add_argument("--delay", type=float, default=0.5)
args = parser.parse_args()
if not args.name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-_" for c in args.name):
    parser.error("Use a lowercase capture name with digits, hyphens, or underscores.")
root = Path(__file__).resolve().parents[1]
output = root / "private" / "ui-captures"
output.mkdir(parents=True, exist_ok=True)
adb = ["/opt/homebrew/share/android-commandlinetools/platform-tools/adb", "-s", "emulator-5554"]
for key in args.keys:
    subprocess.run(adb + ["shell", "input", "keyevent", "KEYCODE_" + key], check=True, timeout=10)
    time.sleep(0.2)
time.sleep(args.delay)
with (output / (args.name + ".png")).open("wb") as image:
    subprocess.run(adb + ["exec-out", "screencap", "-p"], stdout=image, check=True, timeout=15)
subprocess.run(adb + ["shell", "uiautomator", "dump", "/sdcard/tiviman-ui.xml"], capture_output=True, check=True, timeout=20)
result = subprocess.run(adb + ["exec-out", "cat", "/sdcard/tiviman-ui.xml"], capture_output=True, check=True, timeout=10)
(output / (args.name + ".xml")).write_bytes(result.stdout)
(output / (args.name + ".json")).write_text(json.dumps({
    "captured_at": datetime.now(timezone.utc).isoformat(),
    "serial": "emulator-5554",
    "keys": args.keys,
    "post_input_delay_seconds": args.delay,
    "capture_order": ["screenshot", "accessibility_hierarchy"],
    "atomic": False,
}, indent=2) + "\n")
print(output / (args.name + ".png"))
