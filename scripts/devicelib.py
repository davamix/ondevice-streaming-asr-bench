#!/usr/bin/env python3
"""Shared adb helpers, device selection and the pre-flight safety gate.

The test device is the owner's only phone -- personal, daily-use and
irreplaceable (PLAN.md §11). Every operation here is treated as production:

  * the device is always selected explicitly with `-s`, never implicitly,
    because an emulator is frequently attached at the same time
  * writes are confined to the two paths in §11.2 and nowhere else
  * a run does not start unless storage, battery, temperature and charging
    state are all in range (§11.6)

Nothing here needs or requests elevated privileges. No root, no bootloader, no
verity, no governor changes -- see §11.1 for why the last one is refused on
methodological grounds as well as safety ones.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

# The safety messages below cite PLAN.md sections by their section sign, and a
# Windows console defaults to cp1252, which mangles them. A safety warning that
# renders as mojibake is a safety warning people stop reading.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

ROOT = Path(__file__).resolve().parent.parent

APPLICATION_ID = "io.github.davamix.asrbench"
TEST_RUNNER = f"{APPLICATION_ID}.test/androidx.test.runner.AndroidJUnitRunner"

# The only two paths on the device this project ever writes to (§11.2).
APP_FILES = f"/sdcard/Android/data/{APPLICATION_ID}/files"
STAGING = "/data/local/tmp/bench"

ADB_CANDIDATES = [
    Path("D:/Android/Sdk/platform-tools/adb.exe"),
    Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe",
]

# Thermal / battery gate (§11.3).
TEMP_START_MAX_C = 35.0
TEMP_ABORT_C = 43.0
BATTERY_MIN_PCT = 30
BATTERY_MAX_PCT = 80
FREE_STORAGE_MIN_GB = 5.0
FREE_STORAGE_MIN_GB_EMULATOR = 0.3


def adb_path() -> str:
    for p in ADB_CANDIDATES:
        if p.exists():
            return str(p)
    found = shutil.which("adb")
    if found:
        return found
    raise SystemExit(
        "adb not found. Looked in:\n  " + "\n  ".join(str(p) for p in ADB_CANDIDATES)
    )


@dataclass
class Device:
    serial: str
    model: str
    is_emulator: bool

    @property
    def short(self) -> str:
        # Never print or log a full serial: this repo is public (§11.7).
        return f"{self.model} ({'emulator' if self.is_emulator else 'physical'})"


def list_devices() -> list[Device]:
    out = subprocess.run([adb_path(), "devices", "-l"], capture_output=True, text=True)
    devices = []
    for line in out.stdout.splitlines()[1:]:
        line = line.strip()
        if not line or "device" not in line.split():
            continue
        serial = line.split()[0]
        m = re.search(r"model:(\S+)", line)
        model = m.group(1) if m else "unknown"
        devices.append(Device(serial, model, serial.startswith("emulator-")))
    return devices


def pick_device(want: str | None = None, prefer_emulator: bool = False) -> Device:
    """Select a device explicitly.

    `want` may be a serial or one of "emulator" / "physical". Ambiguity is an
    error rather than a guess -- pushing a run at the wrong device is exactly
    the mistake §11.6 exists to prevent.
    """
    devices = list_devices()
    if not devices:
        raise SystemExit("no adb devices attached")

    if want == "emulator" or (want is None and prefer_emulator):
        cands = [d for d in devices if d.is_emulator]
        if not cands:
            raise SystemExit(
                "no emulator attached. Start one first -- §11.5 requires every new "
                "adb path to be exercised on the emulator before the phone."
            )
        return cands[0]
    if want == "physical":
        cands = [d for d in devices if not d.is_emulator]
        if not cands:
            raise SystemExit("no physical device attached")
        if len(cands) > 1:
            raise SystemExit(f"{len(cands)} physical devices attached; pass --device <serial>")
        return cands[0]
    if want:
        for d in devices:
            if d.serial == want:
                return d
        raise SystemExit(f"device {want!r} not attached")

    if len(devices) > 1:
        listing = "\n  ".join(f"{d.serial}  {d.short}" for d in devices)
        raise SystemExit(
            f"{len(devices)} devices attached -- pass --device explicitly:\n  {listing}"
        )
    return devices[0]


def sh(dev: Device, *args: str, check: bool = True, timeout: int | None = None) -> str:
    cmd = [adb_path(), "-s", dev.serial, *args]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and proc.returncode != 0:
        raise SystemExit(
            f"adb {' '.join(args)} failed ({proc.returncode})\n"
            f"stdout: {proc.stdout}\nstderr: {proc.stderr}"
        )
    return proc.stdout


def shell(dev: Device, cmd: str, check: bool = True, timeout: int | None = None) -> str:
    return sh(dev, "shell", cmd, check=check, timeout=timeout)


# ── telemetry ────────────────────────────────────────────────────────────

def battery(dev: Device) -> dict:
    out = shell(dev, "dumpsys battery", check=False)
    info: dict = {}
    for line in out.splitlines():
        line = line.strip()
        if ":" not in line:
            continue
        k, _, v = line.partition(":")
        k, v = k.strip().lower(), v.strip()
        if k == "temperature" and v.lstrip("-").isdigit():
            info["temp_c"] = int(v) / 10.0
        elif k == "level" and v.isdigit():
            info["pct"] = int(v)
        elif k in ("ac powered", "usb powered", "wireless powered"):
            info[k.replace(" ", "_")] = v == "true"
    info["charging"] = any(
        info.get(k) for k in ("ac_powered", "usb_powered", "wireless_powered")
    )
    return info


def free_storage_gb(dev: Device) -> float | None:
    out = shell(dev, "df /sdcard", check=False)
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 4 and parts[3].isdigit():
            return int(parts[3]) / (1024 * 1024)  # df reports 1K blocks
    return None


def device_props(dev: Device) -> dict:
    """Non-identifying device properties. Never includes the serial (§11.7)."""
    wanted = {
        "ro.product.model": "model",
        "ro.product.device": "device",
        "ro.soc.model": "soc_model",
        "ro.soc.manufacturer": "soc_manufacturer",
        "ro.build.version.release": "android_release",
        "ro.build.version.sdk": "sdk_int",
        "ro.product.cpu.abi": "abi",
    }
    out = {}
    for prop, key in wanted.items():
        v = shell(dev, f"getprop {prop}", check=False).strip()
        if v:
            out[key] = v
    return out


# ── pre-flight ───────────────────────────────────────────────────────────

class PreflightError(SystemExit):
    pass


def preflight(dev: Device, *, require_thermal: bool | None = None,
              verbose: bool = True) -> dict:
    """The §11.6 checklist. Refuses to proceed rather than risking the phone.

    On an emulator the battery and thermal checks are reported but not
    enforced: the values are synthetic, so gating on them would be theatre.
    """
    if require_thermal is None:
        require_thermal = not dev.is_emulator

    report: dict = {"device": dev.short, "is_emulator": dev.is_emulator}
    problems: list[str] = []

    bat = battery(dev)
    report["battery"] = bat
    free = free_storage_gb(dev)
    report["free_storage_gb"] = round(free, 1) if free is not None else None

    if verbose:
        print(f"[preflight] {dev.short}")
        print(f"[preflight] battery: {bat.get('pct')}% "
              f"{bat.get('temp_c')} C charging={bat.get('charging')}")
        print(f"[preflight] free storage: {report['free_storage_gb']} GB")

    # The 5 GB floor is sized for the phone, which has to hold the corpus plus
    # up to ~670 MB of weights per arm with room to spare. An emulator running
    # a plumbing check needs a fraction of that.
    min_free = FREE_STORAGE_MIN_GB if not dev.is_emulator else FREE_STORAGE_MIN_GB_EMULATOR
    if free is not None and free < min_free:
        problems.append(f"free storage {free:.1f} GB < {min_free} GB")

    if require_thermal:
        temp = bat.get("temp_c")
        pct = bat.get("pct")
        if temp is None:
            problems.append("could not read battery temperature")
        elif temp >= TEMP_ABORT_C:
            problems.append(f"battery {temp} C >= abort threshold {TEMP_ABORT_C} C")
        elif temp >= TEMP_START_MAX_C:
            problems.append(
                f"battery {temp} C >= start gate {TEMP_START_MAX_C} C -- let it cool"
            )
        if bat.get("charging"):
            problems.append(
                "device is charging; charging heat plus inference heat compounds (§11.3)"
            )
        if pct is not None and not (BATTERY_MIN_PCT <= pct <= BATTERY_MAX_PCT):
            problems.append(
                f"battery {pct}% outside {BATTERY_MIN_PCT}-{BATTERY_MAX_PCT}% (§11.3)"
            )

    report["problems"] = problems
    if problems:
        msg = "\n  ".join(problems)
        raise PreflightError(
            f"pre-flight failed for {dev.short}:\n  {msg}\n\n"
            "Nothing was run. Fix the above and retry, or pass --skip-preflight "
            "only if you understand why each check exists (PLAN.md §11.3, §11.6)."
        )
    if verbose:
        print("[preflight] OK")
    return report


def main() -> int:
    import argparse

    ap = argparse.ArgumentParser(description="Device status and pre-flight check")
    ap.add_argument("--device", help="serial, or 'emulator' / 'physical'")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    devices = list_devices()
    if not args.device and len(devices) != 1:
        print("attached devices:")
        for d in devices:
            print(f"  {d.serial}  {d.short}")
        if not devices:
            return 1

    dev = pick_device(args.device)
    props = device_props(dev)
    bat = battery(dev)
    free = free_storage_gb(dev)

    if args.json:
        print(json.dumps({"props": props, "battery": bat, "free_gb": free}, indent=2))
    else:
        print(f"device: {dev.short}")
        for k, v in props.items():
            print(f"  {k}: {v}")
        print(f"  battery: {bat.get('pct')}% {bat.get('temp_c')} C "
              f"charging={bat.get('charging')}")
        print(f"  free: {free:.1f} GB" if free else "  free: unknown")
    return 0


if __name__ == "__main__":
    sys.exit(main())
