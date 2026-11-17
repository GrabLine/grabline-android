"""Run the phone <-> desktop end-to-end test (P1-A16) on an emulator or a real phone.

    python scripts/e2e/run_e2e.py [--serial emulator-5554] [--desktop PATH] [--python PATH]
    python scripts/e2e/run_e2e.py --serial <phone serial> --reverse   # real phone over USB

With --reverse the listener port is tunnelled with `adb reverse`, and the phone
dials 127.0.0.1 instead of the emulator's 10.0.2.2.

Starts the desktop peer (scripts/e2e/phone_peer.py in the desktop repo) with a
throwaway data dir, waits for its pairing code, runs PcEndToEndTest on the
emulator with that code, then stops the peer and prints what the PC saw.
Machine paths come from CLAUDE.local.md; override them with the flags.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    parser.add_argument("--desktop", default=str(ANDROID.parent / "GrabLine-phone-remote"))
    parser.add_argument("--python", default=str(ANDROID.parent / "GrabLine" / ".venv" / "Scripts" / "python.exe"))
    parser.add_argument("--port", default="47831")
    parser.add_argument("--reverse", action="store_true", help="real phone: tunnel the port with adb reverse")
    parser.add_argument("--adb", default=str(ANDROID.parent.parent / "android-sdk" / "platform-tools" / "adb.exe"))
    args = parser.parse_args()

    state = Path(tempfile.mkdtemp(prefix="grabline-e2e-state-")) / "state.json"
    env = dict(os.environ, GRABLINE_E2E_PORT=args.port, QT_QPA_PLATFORM="offscreen")
    peer = subprocess.Popen(
        [args.python, "scripts/e2e/phone_peer.py", str(state)],
        cwd=args.desktop,
        env=env,
    )
    try:
        deadline = time.time() + 60
        info: dict[str, object] = {}
        while time.time() < deadline:
            if state.exists():
                info = json.loads(state.read_text(encoding="utf-8"))
                if info.get("code"):
                    break
            if peer.poll() is not None:
                print("desktop peer exited early", file=sys.stderr)
                return 2
            time.sleep(0.5)
        else:
            print("no pairing code from the desktop peer", file=sys.stderr)
            return 2
        gradle_env = dict(
            os.environ,
            JAVA_HOME=os.environ.get("JAVA_HOME", r"D:\Projects\jdk-17"),
            GRADLE_USER_HOME=os.environ.get("GRADLE_USER_HOME", r"F:\dev-cache\gradle"),
            ANDROID_SERIAL=args.serial,
        )
        runner = "-Pandroid.testInstrumentationRunnerArguments."
        gradlew = str(ANDROID / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        cmd = [
            gradlew,
            "connectedDebugAndroidTest",
            "--console=plain",
            f"{runner}class=dev.grabline.app.e2e.PcEndToEndTest",
            f"{runner}e2eCode={info['code']}",
            f"{runner}e2ePort={info['port']}",
            f"{runner}e2eMedia={info['mediaBase']}",
        ]
        if args.reverse:
            subprocess.run([args.adb, "-s", args.serial, "reverse", f"tcp:{info['port']}", f"tcp:{info['port']}"], check=True)
            cmd.append(f"{runner}e2eHost=127.0.0.1")
        result = subprocess.run(cmd, cwd=ANDROID, env=gradle_env, check=False)
        info = json.loads(state.read_text(encoding="utf-8"))
        print("PC events:")
        for event in info.get("events", []):
            print("  ", event)
        return result.returncode
    finally:
        peer.terminate()
        try:
            peer.wait(10)
        except subprocess.TimeoutExpired:
            peer.kill()


if __name__ == "__main__":
    sys.exit(main())
