"""QA / self-test screenshot driver (debug builds): debug-broadcast steps, then a capture, cropped to the window or the board.

  DEV=emulator-5564 python3 tools/shots.py OUTDIR [win|board|all] [zh|en|both]

The camera pose is set once per group (pico-cli camera reset + move): "win" looks at the control window from about 1 m,
"board" at the board from the front. Crops are the window / board rectangles of a frontal capture (2560 x 2558).
Every state is reached through the same debug commands QA uses (AGENTS.md), which call the same GameFlow functions as
pinches and panel buttons.
"""
import os, subprocess, sys, time
from PIL import Image

DEV = os.environ.get("DEV", "emulator-5564")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "io.embodit.abalonepush"
POSES = {"win": "turn right 24 degrees then move forward 0.9 meters", "board": "move forward 1.2 meters", "reset": ""}
CROPS = {"win": (680, 500, 2000, 2200), "board": (200, 560, 2560, 2300), "reset": None}

# name -> steps; ("sleep", s) waits; others are debug commands
WIN = [
    ("S01-welcome", [("goto", "S01"), ("sleep", 2.0)]),
    ("S02-new", [("newmode", "ai"), ("goto", "S02"), ("sleep", 1.5)]),
    ("S02-new-2p", [("goto", "S02"), ("newmode", "2p"), ("sleep", 1.2)]),
    ("S03-idle", [("newmode", "ai"), ("coach", "off"), ("goto", "S03"), ("sleep", 1.5)]),
    ("S03-coach", [("coach", "on"), ("goto", "S03"), ("sleep", 1.5)]),
    ("S04-push", [("coach", "off"), ("goto", "S04"), ("sleep", 1.5)]),
    ("S04-one", [("goto", "S04 1"), ("sleep", 1.5)]),
    ("S05-moving", [("goto", "S05 120"), ("sleep", 1.0)]),
    ("S06-thinking", [("goto", "S06"), ("sleep", 1.5)]),
    ("S08-sideways", [("goto", "S08"), ("sleep", 1.0)]),
    ("S08-line", [("goto", "S08 line"), ("sleep", 1.0)]),
    ("S09-result", [("goto", "S09"), ("sleep", 2.5)]),
    ("S11-settings", [("hold", "off"), ("goto", "S11"), ("sleep", 1.5)]),
    ("S12-rules", [("goto", "S03"), ("sleep", 0.5), ("goto", "S12"), ("sleep", 1.5)]),
    ("S13-restart", [("goto", "S03"), ("sleep", 0.5), ("goto", "S13"), ("sleep", 1.5)]),
    ("S13-resign", [("goto", "S03"), ("sleep", 0.5), ("goto", "S13 resign"), ("sleep", 1.5)]),
    ("S13-exit", [("exitask", ""), ("sleep", 1.5)]),
    ("S13-newgame", [("no", ""), ("goto", "S03"), ("sleep", 0.5), ("move", "C3,C4,C5 2"), ("sleep", 2.5), ("newask", ""), ("sleep", 1.5)]),
    ("S17-drop", [("page", "play"), ("goto", "S17"), ("sleep", 1.5)]),
    ("S03-2p", [("freeze", "off"), ("new", "2p"), ("sleep", 1.5)]),
]
BOARD = [
    ("board-S03", [("lang", "zh"), ("coach", "off"), ("goto", "S03"), ("sleep", 1.5)]),
    ("board-S04-push", [("goto", "S04"), ("sleep", 1.5)]),
    ("board-S04-one", [("goto", "S04 1"), ("sleep", 1.5)]),
    ("board-S04-two", [("goto", "S04 2"), ("sleep", 1.5)]),
    ("board-S04-three", [("goto", "S04 3"), ("sleep", 1.5)]),
    ("board-S05", [("goto", "S05 120"), ("sleep", 1.0)]),
    ("board-S06", [("goto", "S06"), ("sleep", 1.5)]),
    ("board-S08", [("goto", "S08"), ("sleep", 1.0)]),
    ("board-S17", [("goto", "S17"), ("sleep", 1.5)]),
    ("board-S09", [("freeze", "off"), ("goto", "S09"), ("sleep", 2.5)]),
    ("board-hint", [("goto", "S03"), ("hint", "on"), ("sleep", 2.0)]),
    ("board-en-S04", [("hint", "off"), ("lang", "en"), ("goto", "S04"), ("sleep", 1.5)]),
]


def cmd(c, a=""):
    subprocess.run([ADB, "-s", DEV, "shell", "am", "broadcast", "-a", PKG + ".DEBUG", "-p", PKG, "--es", "cmd", c, "--es", "a", '"%s"' % a], capture_output=True)


def pose(p):
    subprocess.run(["pico-cli", "camera", "reset", "-d", DEV], capture_output=True)
    if POSES[p]:
        subprocess.run(["pico-cli", "camera", "move", "-d", DEV, "--text", POSES[p]], capture_output=True)
    time.sleep(1.0)


def shot(path):
    subprocess.run(["pico-cli", "capture", "screenshot", "-d", DEV, "-o", path, "--replace"], capture_output=True)


def run(out, group, items, lang=None):
    pose(group)
    if lang:
        cmd("lang", lang); time.sleep(0.8)
    for name, steps in items:
        for st in steps:
            if st[0] == "sleep":
                time.sleep(float(st[1]))
            else:
                cmd(st[0], st[1]); time.sleep(0.2)
        raw = os.path.join(out, "raw.png")
        shot(raw)
        im = Image.open(raw)
        c = CROPS[group]
        tag = f"{name}-{lang}" if lang else name
        (im.crop(c) if c else im).save(os.path.join(out, tag + ".png"))
        if group == "win":
            im.save(os.path.join(out, "full", tag + ".png"))
        print("shot", tag, flush=True)
    if os.path.exists(os.path.join(out, "raw.png")):
        os.remove(os.path.join(out, "raw.png"))


def main():
    out = sys.argv[1]
    which = sys.argv[2] if len(sys.argv) > 2 else "all"
    langs = {"zh": ["zh"], "en": ["en"], "both": ["zh", "en"]}[sys.argv[3] if len(sys.argv) > 3 else "both"]
    os.makedirs(os.path.join(out, "full"), exist_ok=True)
    if which in ("win", "all"):
        for lg in langs:
            run(out, "win", WIN, lg)
    if which in ("board", "all"):
        run(out, "board", BOARD)
    cmd("freeze", "off"); cmd("hold", "off"); cmd("hint", "off"); cmd("lang", "system")


main()
