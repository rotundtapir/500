#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
"""Capture the fastlane phone screenshots reproducibly.

The store listing kept drifting from the app because captures were manual (see #50). The engine is
strictly seed-deterministic, so a chosen board can be re-shot identically after any UI change: this
drives the real app through the same intent-extra overrides the connected suite uses
(EXTRA_SEED / EXTRA_ANIMATION_SPEED=OFF / EXTRA_SOUND_VOLUME=0) and taps by *label*, not by
coordinate, so it survives layout tweaks.

Prerequisites:
  - an emulator at 1080x2400 (the existing shots' size — fivehundred_api35 is that), ANDROID_SERIAL set
  - the foss debug APK installed: ./gradlew :app:installFossDebug
    (foss, not play: the play flavour renders an ad slot that must not appear in F-Droid's listing)
  - for the online shots only: a local dev server, e.g.
      DEV_MODE=true PORT=8085 ./gradlew :server:run
    and pass --server ws://10.0.2.2:8085

The 'online' shot here fills the other seats with bots. For the listing shot that shows a real
second player (which is what sells cross-play), run a peer web client against the same server while
this script waits in the lobby — serve web/build/dist/wasmJs/productionExecutable under a /500/
path and open
  /500/?joinCode=<code>&playerName=Priya&serverUrl=ws://localhost:<port>&animationSpeed=OFF
then Join + Ready up. The peer must stay connected: once its socket goes, the seat reverts to a bot
and the label picks up "(bot)".

Usage:
  scripts/screenshots.py --list
  scripts/screenshots.py home bidding tutorial
  scripts/screenshots.py --server ws://10.0.2.2:8085 lobby online
  scripts/screenshots.py --out /tmp/shots all

Output files are named <shot>.png; the mapping onto fastlane's numbered files is printed at the end
rather than applied, so replacing a listing image stays a deliberate step.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

PKG = "io.github.rotundtapir.fivehundred"
ACTIVITY = f"{PKG}/.MainActivity"
EXTRA = f"{PKG}."
# Deterministic boards. Same seed + same code = same deal, so a shot is re-takeable exactly.
SEED_BIDDING = 42
TUTORIAL_SEED = 9  # documented in ui/Tutorial.kt; the tutorial pins it itself


def adb(*args: str, check: bool = True) -> str:
    out = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and out.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def demo_status_bar(on: bool) -> None:
    """A clean, fixed status bar: no VPN/battery clutter, no clock drift between captures."""
    if on:
        adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo",
            "-e", "command", "enter")
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo",
            "-e", "command", "clock", "-e", "hhmm", "1000")
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo",
            "-e", "command", "battery", "-e", "plugged", "false", "-e", "level", "100")
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo",
            "-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4",
            "-e", "mobile", "hide")
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo",
            "-e", "command", "notifications", "-e", "visible", "false")
    else:
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit")


def launch(seed: int | None = None, server: str | None = None, name: str | None = None) -> None:
    adb("shell", "am", "force-stop", PKG)
    cmd = ["shell", "am", "start", "-n", ACTIVITY,
           "--es", f"{EXTRA}ANIMATION_SPEED", "OFF",
           "--ef", f"{EXTRA}SOUND_VOLUME", "0"]
    if seed is not None:
        cmd += ["--el", f"{EXTRA}SEED", str(seed)]
    if server:
        cmd += ["--es", f"{EXTRA}SERVER_URL", server]
    if name:
        cmd += ["--es", f"{EXTRA}PLAYER_NAME", name]
    adb(*cmd)
    time.sleep(4)


NODE_RE = re.compile(r'text="([^"]*)" [^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def nodes() -> list[tuple[str, str, tuple[int, int]]]:
    xml = subprocess.run(["adb", "exec-out", "uiautomator", "dump", "/dev/tty"],
                         capture_output=True, text=True).stdout
    found = []
    for text, desc, x1, y1, x2, y2 in NODE_RE.findall(xml):
        centre = ((int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2)
        found.append((text, desc, centre))
    return found


def dismiss_anr() -> bool:
    """A slow emulator throws "System UI isn't responding" over the app; "Wait" clears it."""
    for text, _desc, centre in nodes():
        if text in ("Wait", "Close app") and text == "Wait":
            adb("shell", "input", "tap", str(centre[0]), str(centre[1]))
            time.sleep(3)
            return True
    return False


def tap(label: str, *, timeout: float = 20.0) -> None:
    """Tap the node labelled label. Exact matches win over substring ones: a screen's title often
    contains a button's label ("Play with bots" vs the "Play" button), and tapping the title does
    nothing while looking like success."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        dismiss_anr()
        found = nodes()
        exact = [c for t, d, c in found if label in (t, d)]
        loose = [c for t, d, c in found if label in t or label in d]
        for centre in exact + loose:
            adb("shell", "input", "tap", str(centre[0]), str(centre[1]))
            time.sleep(1.5)
            return
        time.sleep(1)
    sys.exit(f"never found a tappable '{label}' — labels on screen: "
             f"{[t or d for t, d, _ in nodes()][:12]}")


def wait_for(label: str, *, timeout: float = 25.0) -> None:
    """Block until label is on screen. A tap acts on the dump that found it, so the next step must
    confirm the screen actually changed — otherwise a stale dump taps thin air and the shot captures
    the previous screen (which is how the first run of this script produced a bot-setup 'bidding')."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        dismiss_anr()
        for text, desc, _ in nodes():
            if label in text or label in desc:
                return
        time.sleep(1)
    sys.exit(f"'{label}' never appeared — labels on screen: {[t or d for t, d, _ in nodes()][:12]}")


def type_text(value: str) -> None:
    adb("shell", "input", "text", value)
    adb("shell", "input", "keyevent", "111")  # ESC closes the IME so it can't cover the shot
    time.sleep(1)


def capture(out: Path, name: str) -> None:
    dismiss_anr()  # never immortalise a system dialog in the store listing
    out.mkdir(parents=True, exist_ok=True)
    remote = "/sdcard/shot.png"
    adb("shell", "screencap", "-p", remote)
    with open(out / f"{name}.png", "wb") as fh:
        proc = subprocess.run(["adb", "exec-out", "cat", remote], stdout=fh)
    adb("shell", "rm", "-f", remote)
    if proc.returncode != 0:
        sys.exit(f"failed to pull {name}.png")
    print(f"  captured {out / (name + '.png')}")


# --- the shots -------------------------------------------------------------------------------------

def shot_home(out: Path, args) -> None:
    """8.png — the home screen (both game modes, narration toggle, About)."""
    launch()
    capture(out, "home")


def shot_bidding(out: Path, args) -> None:
    """3.png — the auction, with the current solid-pill bid styling."""
    launch(seed=SEED_BIDDING)
    tap("Play with bots")
    wait_for("4 players")
    tap("Play")
    wait_for("Your bid")   # the auction has reached the human
    capture(out, "bidding")


def shot_tutorial(out: Path, args) -> None:
    """7.png — the tutorial's first scripted decision (its own seed; trace lives in ui/Tutorial.kt)."""
    launch()
    tap("How to play")
    # Four explainer pages come before the practice hand; page 5's button is "Start", not "Next".
    for _ in range(4):
        wait_for("Next")
        tap("Next")
    wait_for("Start")
    tap("Start")
    wait_for("Your bid")   # the first scripted decision (BidStep) — what the listing should show
    capture(out, "tutorial")


def shot_lobby(out: Path, args) -> None:
    """New — an online lobby showing its join code."""
    require_server(args)
    launch(server=args.server, name="Alex")
    tap("Play with friends")
    wait_for("Create a game")
    tap("Create a game")
    wait_for("Table")
    tap("Create")
    wait_for("Code")
    capture(out, "lobby")


def shot_online(out: Path, args) -> None:
    """New — an online game in progress, with a human name in a seat label."""
    require_server(args)
    launch(server=args.server, name="Alex")
    tap("Play with friends")
    wait_for("Create a game")
    tap("Create a game")
    wait_for("Table")
    tap("Create")
    wait_for("Code")
    tap("Start")
    wait_for("Your bid")
    capture(out, "online")


def require_server(args) -> None:
    if not args.server:
        sys.exit("this shot needs --server ws://10.0.2.2:<port> and a dev server running there")


SHOTS = {
    "home": shot_home,
    "bidding": shot_bidding,
    "tutorial": shot_tutorial,
    "lobby": shot_lobby,
    "online": shot_online,
}

# Which listing file each shot belongs to. Both stores publish in *lexicographic* filename order,
# which is why these are zero-padded: a bare "10.png" sorts between "1.png" and "2.png".
TARGETS = {
    "online": "01-online-game.png",
    "bidding": "02-bidding.png",
    "tutorial": "04-tutorial.png",
    "lobby": "05-online-lobby.png",
    "home": "06-home.png",
}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("shots", nargs="*", help="shot names, or 'all'")
    p.add_argument("--out", default="build/screenshots", type=Path)
    p.add_argument("--server", help="ws:// URL of a dev server, for the online shots")
    p.add_argument("--list", action="store_true")
    args = p.parse_args()

    if args.list or not args.shots:
        for name, fn in SHOTS.items():
            print(f"{name:9} -> {TARGETS[name]:7} {fn.__doc__.splitlines()[0]}")
        return

    if not shutil.which("adb"):
        sys.exit("adb not on PATH")
    if "1080x2400" not in adb("shell", "wm", "size"):
        print("warning: the existing listing images are 1080x2400; this device is "
              f"{adb('shell', 'wm', 'size').strip()}", file=sys.stderr)

    wanted = list(SHOTS) if args.shots == ["all"] else args.shots
    demo_status_bar(True)
    try:
        for name in wanted:
            if name not in SHOTS:
                sys.exit(f"unknown shot '{name}' (see --list)")
            print(f"{name}:")
            SHOTS[name](args.out, args)
    finally:
        demo_status_bar(False)

    print("\nTo publish, copy over the listing files deliberately:")
    for name in wanted:
        print(f"  {args.out / (name + '.png')} -> "
              f"fastlane/metadata/android/en-US/images/phoneScreenshots/{TARGETS[name]}")


if __name__ == "__main__":
    main()
