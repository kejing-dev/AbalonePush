# 弹珠棋 Abalone Push: project guidance

Abalone (official 1990 rules) for PICO OS 6 Shared Space, PICO Spatial SDK BOM 6.1.9, package `io.embodit.abalonepush`, v0.1.1 (select cue: one soft -24 dBFS tick, one instance per event; name-based hit resolution + BoardTargetTest replay).
Design contract: spatial-app-factory `apps/abalone-push/design-package.json` (v1.4; profile A12 one main loop, `v2[]` not built:
no drag-to-push, no board turn / hand-over, no tilt / skins, no game codes, no puzzles, no 4th / 5th AI level, no online).
Skeleton from the Royal Game of Ur project (itself Five Stones <- kejing-dev/CloudAir v1.0.2: two containers, theme, sound
pool, level gate, factory board-input template); the hex rules, AI, flow, scene and pages are new.

## Surfaces
- **Board volume** (default container `AbaloneBoard`, Volumetric 0.66 x 0.36 x 0.56 m, fixed scale, gravity aligned, uniform
  resize). `board.glb` (hexagon R 0.30 m, 3 cm thick, 61 dimples with light-wood lips on a deep-sea teal field, a 4.5 cm gutter
  with 36 slot marks, walnut sides, light-wood rim) on `tiltRoot` (20° fixed, pivot on the near-bottom edge). 28 glass marbles
  (`marble_black/white.glb`), the direction-pad pool (`pad_*.glb`: blue = move, amber + dots = push n, red rim = drops one,
  grey = can't), rings (selection, joinable, hint, alert, last move, hover) and the gutter glow are boardRoot children.
  Counter plate (AttachmentPanel 232 x 87 dp ~ 160 x 60 mm) stands upright above the far rim: score + the status sentence.
- **Control window** (`AbaloneControl`, Planar 560 x 640 dp, `Placement.right(AbaloneBoard)`): native SpatialUI `TabBar`, four
  pages 对局 / 新局 / 规则 / 设置. Every page: header with [静音], content fills the window (blank <= 25 %). Game page: whose turn,
  score row, status card, six direction keys (D43: same `GameFlow.tapPad` as a pinch on the 3D pad; grey keys answer with the
  reason), [重做] [悔一步] [给个提示], [认输] [重开]. Confirmations are a whole-page card and lock the volume.
  Settings [重开控制窗] closes and reopens beside the board (D44). [退出应用] closes both containers, removes the tasks, exits.

## Code
| Path | What |
|---|---|
| `domain/Hex.kt` | 61 cells, axial (q, r), neighbours, metres, A1..I9 notation |
| `domain/Abalone.kt` | groups, `classify` (one Sumito function: MOVE / PUSH n / BLOCKED reason / OWN), `pads`, `moveOf`, `legalMoves`, `apply`, winner |
| `domain/Ai.kt` | 入门 / 中级 / 高级 (negamax + alpha-beta, iterative deepening, 720 ms cap) |
| `domain/GameFlow.kt` | the single `PlayState`, phases S00..S17, selection rules, AI timing, undo (a whole round) / redo, hint, draws, save |
| `domain/Game.kt` | config, settings, `SaveCodec` (`files/game.txt`: config + move list, replayed and checked on load, atomic + backup) |
| `domain/Layout.kt` | metres for everything (tilt, marbles, pads, gutter slots, plate), eyes, D35 checks (unit tested) |
| `ui/board/*` | `BoardScene` (all entities; per-frame placement in `onFrame`, driven by `BoardFrameSystem`), `BoardScreen` (taps, plate), `input/BoardInput.kt` (factory template) |
| `ui/control/*` | control window pages; every Text logs `OVERFLOW <tag>` when cut |
| `ui/StatusText.kt` | the one status sentence (plate short form, window long form) |
| `platform/*` | Application / Graph, SoundPlayer, WindowCtl (+ LevelGate); debug builds add `DebugCommandReceiver` |
| `tools/` | `build_models.py` (all GLBs), `import_cc0_sounds.py` (Kenney CC0 + one UI tone, `qa-sample.wav`), `build_icon.py`, `strings.py` (zh / en strings), `shots.py`, `blank.py`, `realtap.py`, `ap.sh` |

Rules - state - render: the scene only renders `PlayState`. Volume input goes through the board-input template (D50: short
drags count as taps, targets resolved up the parent chain, hover callback); a pinch on a collider (`cell:<i>` = a cell or the
marble standing on it, `pad:<d>`, `board`) goes through `route()` to the same `GameFlow` calls the panel and the debug broadcast use.
A tap on the marble / cell right under a pad counts as that pad (the ray can pass the 4 cm gap and hit the marble).

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest            # rules (Sumito table, two generators), flow, layout / D35, AI (12 games)
./gradlew :app:testDebugUnitTest --tests '*AiTest' -Dab.games=50   # the 50-game AI ladder (~25 min)
python3 tools/build_models.py ; python3 tools/import_cc0_sounds.py ; python3 tools/build_icon.py --icon512 icon.png ; python3 tools/strings.py
DEV=emulator-5564 tools/ap.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow abalone-push` in the factory, return after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.abalonepush.DEBUG -p io.embodit.abalonepush --es cmd <cmd> --es a "<arg>"` (or `tools/ap.sh cmd <cmd> [arg]`). Log tag `AbalonePush`.

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 10 s, `s00hold off` | flat slab stand-in until board.glb is in |
| S01 第一次 | `goto S01` | default game (人机 · 中级 · 执黑) + rules page; the first tap on a marble or [开始] ends it |
| S02 新局 | `goto S02` (`newmode 2p` for the two-player page) | [继续上一盘] on top when a save exists |
| S03 待走 | `goto S03` (`coach on` for the first-game line) | |
| S04 已选串 | `goto S04` (3 black v 2 white: 2 blue / 1 amber 2 dots red rim / 3 grey) · `goto S04 1|2|3` (opening, 1-3 marbles) | |
| S05 推动中 | `goto S05 [ms]` | push held at ms (default 150) |
| S06 AI 在想 | `goto S06` | AI holds until `hold off` |
| S08 提示 | `goto S08` (grey pad "人不够多") · `goto S08 line` ("不在一条线上") | held by freeze; `freeze off` |
| S09 终局 | `goto S09` | the 6th marble goes off |
| S11 设置 / S12 规则 | `goto S11` / `goto S12` · `exitask` | |
| S13 确认 | `goto S13` (restart) · `goto S13 resign` · `exitask` · `newask` (new game over a game in progress) · `confirm` / `no` | |
| S16 恢复 | make a move, force-stop, relaunch | back to S03 / S06 with the same moves |
| S17 落槽中 | `goto S17 [ms]` or `hold S17` before a drop | the marble in the air over the rim, gutter glow on |
Other: `tap <A1..I9|q,r|pad:d|board>`, `sel C3,C4`, `push <d>`, `move "C3,C4,C5 2"`, `setup "b:E5,E6 w:E8 s:0,2 side:b [2p]"`,
`new "white ai=3"`, `newmode ai|2p`, `level 1..3`, `undo`, `redo`, `hint on|off`, `resign`, `restart`, `lang zh|en|system`, `sound on|off`,
`volume 0-2`, `reduced on|off`, `coach on|off`, `seed N`, `aitime MS|off`, `freeze MS|off`, `hold on|off|S17`, `autoplay on|off`,
`reopen`, `closepanel`, `hover <collider>`, `dump`, `evidence`.

## Known
- 3D taps (marbles, cells, pads) are not reachable by `pico-cli interact` (only the 2D window publishes nodes); on the emulator
  they were driven by the debug broadcast through the same `route()`. Controller ray / hand ray / gaze + pinch: headset only.
- Sounds are SoundPool (not spatial). Physical sounds are Kenney CC0 recordings; the "can't" cue is a synthesised UI tone.
- Hover: a faint ring on the cell / a 4 mm lift of the pad (template `onHover`); Shared Space did not deliver hover events on the
  Go-9 headset test, so it is a bonus only.
- The system places the volume where it likes (on the emulator about 3.5 m out at reset); the window opens to its right.
