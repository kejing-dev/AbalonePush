"""Sounds for the marble board (profile B20 / D51 + ui_gates sound-source): physical sounds are Kenney CC0 recordings
(impact-sounds / casino-audio), only the "can't" cue is a synthesised UI tone.

  python3 tools/import_cc0_sounds.py [~/Downloads/cc0-sounds]

Decodes the .ogg files with macOS afconvert (22.05 kHz mono), trims silence, low-passes the harsh top, fades the tail,
cuts long ones, gently compresses (short, light, soft, clear: no dull thump) and normalises to -1 dBFS peak, writing
app/src/main/assets/sounds/<name>[_n].wav. The app adds a ±5 % random pitch per play and rotates the variants.
Also writes qa-sample.wav (every cue in a row, the sounding segments only) for the loudness evidence.
"""
import math, os, subprocess, sys, tempfile, wave
import numpy as np

SRC = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/cc0-sounds")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
RATE = 22050
IMP = "kenney_impact-sounds/Audio/"
CAS = "kenney_casino-audio/Audio/"

# event -> (max seconds, low-pass Hz, gain dB, [files])
MAP = {
    "select": (0.22, 7000, 0, [IMP + "impactGlass_light_000.ogg", IMP + "impactGlass_light_001.ogg", IMP + "impactGlass_light_002.ogg"]),
    "slide": (0.26, 3200, -3, [CAS + "card-slide-1.ogg", CAS + "card-slide-2.ogg", CAS + "card-slide-3.ogg"]),
    "clack": (0.22, 6500, 0, [CAS + "chips-collide-1.ogg", CAS + "chips-collide-2.ogg", CAS + "chips-collide-3.ogg"]),
    "drop": (0.45, 7000, 0, [IMP + "impactGlass_heavy_001.ogg", IMP + "impactGlass_heavy_002.ogg", IMP + "impactGlass_heavy_003.ogg"]),
}
SOURCES = {}


def decode(path):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "x.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@%d" % RATE, "-c", "1", path, out], check=True)
        with wave.open(out) as w:
            return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def lowpass(x, f, passes=2):
    w = 2 * math.pi * f / RATE; cw, sw = math.cos(w), math.sin(w); alpha = sw / (2 * 0.707)
    b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]; a = [1 + alpha, -2 * cw, 1 - alpha]
    b = [v / a[0] for v in b]; a1, a2 = a[1] / a[0], a[2] / a[0]
    for _ in range(passes):
        y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
        for i, v in enumerate(x):
            o = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
            x2, x1, y2, y1 = x1, v, y1, o; y[i] = o
        x = y
    return x


def finish(x, gain_db=0.0):
    k = min(len(x), int(0.04 * RATE)); x[-k:] *= np.linspace(1, 0, k)
    k = min(len(x), int(0.003 * RATE)); x[:k] *= np.linspace(0, 1, k)
    x = x / max(1e-9, np.abs(x).max())
    # gentle compression: lift the decay so the cue is clear at the default volume without a louder attack
    x = np.tanh(2.2 * x) / math.tanh(2.2)
    return x * (10 ** ((-1 + gain_db) / 20))


def process(x, max_s, lp):
    thr = 10 ** (-45 / 20)
    idx = np.where(np.abs(x) > thr)[0]
    if len(idx): x = x[max(0, idx[0] - 40):idx[-1] + 1]
    x = x[:int(max_s * RATE)]
    return lowpass(x, lp)


def write(name, x):
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def resample(x, ratio):
    """Pitch / speed by ratio (> 1 = higher, shorter)."""
    n = int(len(x) / ratio)
    return np.interp(np.arange(n) * ratio, np.arange(len(x)), x)


def illegal_tone():
    """UI cue: two soft sine plucks going down a fourth (440 -> 330 Hz) with a fast decay; not a thump."""
    out = []
    for f0, dur in ((440.0, 0.09), (330.0, 0.14)):
        t = np.arange(int(dur * RATE)) / RATE
        env = np.exp(-t * 22) * (1 - np.exp(-t * 900))
        out.append((np.sin(2 * math.pi * f0 * t) + 0.25 * np.sin(4 * math.pi * f0 * t)) * env)
    return np.concatenate(out)


os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    if f.endswith(".wav"):
        os.remove(os.path.join(OUT, f))
made = {}
for event, (max_s, lp, gain, files) in MAP.items():
    for i, rel in enumerate(files):
        name = event if i == 0 else f"{event}_{i + 1}"
        x = finish(process(decode(os.path.join(SRC, rel)), max_s, lp), gain)
        write(name, x); made[name] = x
        SOURCES[name] = "CC0 录音 Kenney " + rel.replace("/Audio/", " / ")
        print(f"{name}.wav <- Kenney {rel}")

# win: three glass taps climbing (C, E, G of the tap's own pitch), 140 ms apart, like three marbles dropping in turn
tap = process(decode(os.path.join(SRC, IMP + "impactGlass_light_001.ogg")), 0.25, 7000)
win = np.zeros(int(0.75 * RATE))
for k, ratio in enumerate((1.0, 1.26, 1.5)):
    s = resample(tap, ratio); o = int(k * 0.14 * RATE)
    win[o:o + len(s)] += s[:len(win) - o]
write("win", finish(win)); made["win"] = finish(win)
SOURCES["win"] = "CC0 录音 Kenney impact-sounds / impactGlass_light_001.ogg（三次升调拼接：三颗珠接连落槽）"
# lose: one low, short wooden knock (the tap slowed 10 %), quieter
lose = resample(process(decode(os.path.join(SRC, IMP + "impactWood_light_002.ogg")), 0.3, 4000), 0.9)
write("lose", finish(lose, -2)); made["lose"] = finish(lose, -2)
SOURCES["lose"] = "CC0 录音 Kenney impact-sounds / impactWood_light_002.ogg（降 10 %）"
il = finish(illegal_tone(), -1)
write("illegal", il); made["illegal"] = il
SOURCES["illegal"] = "合成（UI）两声柔和正弦拨音 440→330 Hz、快衰减，非实物声（点灰牌 / 不成线时的提示）"

# the sounding segments back to back (loudness evidence, "发声段" rule)
order = ["select", "select_2", "slide", "clack", "slide_2", "clack_2", "drop", "select_3", "slide_3", "clack_3", "drop_2", "illegal", "drop_3", "win", "lose"]
seq = np.concatenate([np.concatenate([made[n], np.zeros(int(0.02 * RATE))]) for n in order])
with wave.open(os.path.join(ROOT, "tools", "qa-sample.wav"), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes((np.clip(seq, -1, 1) * 32767).astype("<i2").tobytes())
with open(os.path.join(ROOT, "tools", "sound-sources.txt"), "w", encoding="utf-8") as f:
    for n in sorted(SOURCES):
        f.write(f"- {n}.wav：{SOURCES[n]}\n")
rms = 20 * math.log10(math.sqrt(np.mean(seq ** 2)))
peak = 20 * math.log10(np.abs(seq).max())
print("sample: RMS %.1f dBFS, peak %.1f dBFS, %.1f s" % (rms, peak, len(seq) / RATE))
