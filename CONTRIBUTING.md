# Contributing

Thanks for helping. Issues and pull requests are welcome.

## Ground rules
- **Never commit the game's files**: no APKs, no extracted Unity data, no `levels.bin` or `levels_export.json`, no game screenshots
  of whole levels. `.gitignore` already blocks the usual paths. Generate level data locally with `tools/extract_levels.py`.
- **Don't add features that modify the game, remove its ads or unlock purchases.** Such PRs won't be merged.
- **Safety first.** The solver must never cost a life. Keep these invariants:
  - one tap at a time; never tap while any arrow or the camera is moving;
  - before every tap, the screen must match the model exactly;
  - when unsure, stop with a clear message rather than guess.

## Reporting a bug
Please include:
- phone model, Android version, game version, light/dark mode;
- the panel's status message;
- the on-device debug folder: `adb pull /sdcard/Android/data/com.patterns.arrowssolver/files/debug` (log.txt and f*.png).
  Screenshots of a level are fine in an issue to show a bug.

## Code map
| File | What |
|---|---|
| `android/.../SolverService.kt` | accessibility service, bubble, panel, map, screenshots, gestures |
| `android/.../Engine.kt` | analyze (zoom, explore, match) and the safe solve loop |
| `android/.../Vision.kt` | pixel classes, grid detection, parser, stitching panned views |
| `android/.../LevelDb.kt` | `levels.bin` loader and on-screen level matching |
| `android/.../Board.kt` | the game rule and greedy solver |
| `App.tsx`, `specs/NativeSolver.ts`, `SolverModule.kt` | setup screen and bridge |
| `tools/extract_levels.py` | builds `levels.bin` from your own APK |
| `docs/GAME_INTERNALS.md` | the game's rules, touch and camera behaviour |
| `prototype/` | the original Python/adb solver |

Build with `cd android && ./gradlew assembleRelease`. Keep diffs focused; explain device-tested behaviour in the PR.
