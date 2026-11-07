"""Renders the app's sound timing offline (profile D51 / build.md: 快速连点 5 次录响度, peak <= -1 dBFS) from the shipped
assets, the way SoundPlayer plays them: volume 100 %, one instance per event (a new play cuts the previous one of the same
event), the timings of GameFlow (push: slide at 0, clack at PUSH_LAG 30 ms, drop at slide 290 + flip 350 ms).

  python3 tools/render_mix.py OUTDIR      writes taps5.wav, push-drop.wav, taps-over-drop.wav and prints peak / RMS
"""
import math, os, sys, wave
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
RATE = 22050


def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def render(events, total_s):
    """events: (t_ms, name). Same event name prefix = same channel: the next one cuts the previous (SoundPlayer.playing)."""
    out = np.zeros(int(total_s * RATE))
    last = {}
    for t, n in sorted(events):
        ch = n.split("_")[0]
        o = int(t / 1000 * RATE)
        if ch in last:  # cut the previous instance of this event where the new one starts
            po, plen = last[ch]
            cut = max(0, o - po)
            if cut < plen:
                out[o:po + plen] -= load_cache[last_name[ch]][cut:plen][: len(out) - o]
        x = load_cache.setdefault(n, load(n))
        out[o:o + len(x)] += x[: len(out) - o]
        last[ch] = (o, len(x)); last_name[ch] = n
    return out


load_cache, last_name = {}, {}


def report(name, x, outdir):
    peak = 20 * math.log10(max(1e-9, np.abs(x).max()))
    act = x[np.abs(x) > 10 ** (-50 / 20)]
    rms = 20 * math.log10(math.sqrt(np.mean(act ** 2))) if len(act) else -99
    with wave.open(os.path.join(outdir, name), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    print("%-20s peak %6.1f dBFS   RMS (sounding) %6.1f dBFS   %.2f s" % (name, peak, rms, len(x) / RATE))
    return peak


out = sys.argv[1] if len(sys.argv) > 1 else "."
os.makedirs(out, exist_ok=True)
# five fast taps: select, add, add, shorten, deselect, 100 ms apart -> five plays of the one select tick
p1 = report("taps5.wav", render([(i * 100, "select") for i in range(5)], 1.0), out)
# a push that drops a marble: select tick, pad tap 300 ms later
push = [(0, "select"), (300, "slide"), (330, "clack"), (300 + 290 + 350, "drop")]
p2 = report("push-drop.wav", render(push, 1.8), out)
# five fast taps on top of a push + drop (worst overlap the code can produce)
p3 = report("taps-over-drop.wav", render(push + [(600 + i * 100, "select") for i in range(5)], 2.0), out)
# win right after the sixth drop
p4 = report("drop-win.wav", render(push + [(300 + 290 + 350 + 250, "win")], 2.5), out)
worst = max(p1, p2, p3, p4)
print("WORST peak %.1f dBFS -> %s" % (worst, "OK (<= -1)" if worst <= -1.0 else "TOO LOUD"))
