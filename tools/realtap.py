"""Real pointer actions on the control window (pico-cli interact, no debug shortcut for the action itself) for the
ui_gates evidence: the new-game confirm, the settings first screen, a direction key, and quitting.

  DEV=emulator-5564 python3 tools/realtap.py OUTDIR

Debug broadcast is only used to set the stage (a game with a move on the board, the selection for the key test).
"""
import json, os, subprocess, sys, time
from PIL import Image

DEV = os.environ.get("DEV", "emulator-5564")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "io.embodit.abalonepush"
OUT = sys.argv[1]
CROP = (680, 500, 2000, 2200)
log = open(os.path.join(OUT, "realtap.txt"), "a", encoding="utf-8")


def note(s):
    line = time.strftime("%H:%M:%S ") + s
    print(line, flush=True); log.write(line + "\n"); log.flush()


def cmd(c, a=""):
    subprocess.run([ADB, "-s", DEV, "shell", "am", "broadcast", "-a", PKG + ".DEBUG", "-p", PKG, "--es", "cmd", c, "--es", "a", '"%s"' % a], capture_output=True)


def query():
    r = subprocess.run(["pico-cli", "interact", "query", "-d", DEV, "--package", PKG, "--compact"], capture_output=True, text=True)
    try:
        return json.loads(r.stdout)
    except ValueError:
        return {}


def click(name_prefix):
    q = query()
    for c in q.get("controls", []):
        if c.get("name", "").startswith(name_prefix):
            r = subprocess.run(["pico-cli", "interact", "perform", "-d", DEV, "--package", PKG, "--node", c["node"], "--action-id", "16"], capture_output=True, text=True)
            note(f"click '{c['name']}' -> {r.stdout.strip()[:120]}")
            return True
    note(f"NOT FOUND '{name_prefix}' among {[c.get('name') for c in q.get('controls', [])]}")
    return False


def shot(name):
    raw = os.path.join(OUT, "raw.png")
    subprocess.run(["pico-cli", "capture", "screenshot", "-d", DEV, "-o", raw, "--replace"], capture_output=True)
    Image.open(raw).crop(CROP).save(os.path.join(OUT, name))
    os.remove(raw)
    note("shot " + name)


subprocess.run(["pico-cli", "camera", "reset", "-d", DEV], capture_output=True)
subprocess.run(["pico-cli", "camera", "move", "-d", DEV, "--text", "turn right 24 degrees then move forward 0.9 meters"], capture_output=True)
cmd("lang", "zh"); cmd("freeze", "off"); cmd("goto", "S03"); time.sleep(1.5)
# a direction key with the real pointer: select C3-C5 (stage), then click the far-left key
cmd("sel", "C3,C4,C5"); time.sleep(1.0)
click("左远"); time.sleep(3.0)
subprocess.run([ADB, "-s", DEV, "logcat", "-d", "-s", "AbalonePush:V"], capture_output=True)
# new game over a game in progress: TabBar [新局] then the real [开始]
if not click("新局"):
    cmd("page", "new")
time.sleep(1.2)
click("开始"); time.sleep(1.2)
shot("newgame-confirm.png")
click("取消"); time.sleep(0.8)
# settings first screen
if not click("设置"):
    cmd("page", "settings")
time.sleep(1.2)
shot("settings-firstscreen.png")
# quit for real
click("退出应用"); time.sleep(1.0)
shot("exit-confirm.png")
click("退出"); time.sleep(4.0)
r = subprocess.run([ADB, "-s", DEV, "shell", "dumpsys", "activity", "recents"], capture_output=True, text=True)
open(os.path.join(OUT, "exit-recents.txt"), "w").write(r.stdout)
left = [l for l in r.stdout.splitlines() if "Recent #" in l and PKG in l]
ps = subprocess.run([ADB, "-s", DEV, "shell", "pidof", PKG], capture_output=True, text=True).stdout.strip()
note(f"recents rows with {PKG}: {len(left)}; process: '{ps or 'gone'}'")
