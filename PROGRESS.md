# Arrows Solver: progress

Android app (React Native 0.88.1 shell + native Kotlin core). It puts a floating bubble over the
game `com.ecffri.arrows`. **Analyze** reads the level from screenshots. **Solve** clears it
one tap at a time without losing lives.

## Game rule (verified on device, 2026-11-05)
- Tapping an arrow makes it slide out head-first, with the body following its own path like a snake.
- It leaves only if every cell on the straight ray from its head to the board edge is empty.
  Otherwise a life is lost, the arrow turns red and stays pending, and it leaves on its own once its path clears.
- Removing an arrow never blocks another, so greedy removal always works.
- **Never tap twice quickly.** Wait until no arrow is moving (moving arrows turn blue (111,130,252)).
  A double tap is the only thing that has cost a life.

## Measured colours (Nothing Phone 1, 1080x2400)
Line (172,180,244); background (30,32,45); empty-cell dot (58,62,85); '#' button (64,70,104);
moving arrow (111..131,130..147,250); red blocked arrow / hearts (255,75,93); hearts at y≈266, x 468/540/610.
At default zoom the grid pitch is 40 px. The board band starts below about 16% of screen height.

## Done
- `android/app/src/main/java/com/patterns/arrowssolver/`
  - `Board.kt`: model, ray rule, free/plan solver.
  - `Vision.kt`: `Shot` pixel classes and integral images, `Grid` (pitch and phase from projections),
    `Parser` (nodes, edges, arrowhead probes), `Survey` (merge panned views, register, build arrows).
  - `Engine.kt`: analyze (pans to see boards wider than the screen) and solve (verify-before-every-tap,
    busy wait, heart guard, locate via lines and dots, choose the tap cell farthest from blocked arrows).
  - Moved `MainActivity.kt` and `MainApplication.kt` out of the broken `java/com/com.patterns.arrowssolver/` folder.
- `prototype/`: the Python version that solved the live level (parse.py, solve.py, runner.py) plus
  reference screenshots. `screens/recon.png` is the parsed board.

## Status 2026-11-05 (built, installed, tested on the phone)
- Every step from the list below is done. The app builds (`gradlew assembleRelease`, arm64 only) and is installed. The accessibility service is on.
- **The level database is the main path.** the game's own level bundles are packed into `assets/levels.bin` by `tools/extract_levels.py` (not committed; build it from your own APK)
  (5 MB, magic `ARW1`). `LevelDb.match` uses visible arrowheads to vote for a level, mirror and offset, then checks every cell.
  Verified: data y=0 is the bottom screen row. The 05 Nov daily matched with 76/76 arrows.
- Half-played levels work: an arrow whose visible cells all show the "emptied" dot counts as already cleared. Red (pending) arrows
  are never tapped and are expected to leave on their own. Arrows found cleared later during solve are reconciled (`vanished`).
- Level 133 (main, 38×38, 103 arrows): 3 Solve runs cleared about 45% with **0 lives lost by the app**. Each stop was a safety stop.
- Fixed after the tests: the settle wait after pans and zoom changes, flexible drag search, collapsing the panel during Solve, and
  detecting the `#` grid view (guide lines (56,64,95)) and switching it off. A tight dot colour keeps the guide lines from reading as dots.
  Taps are allowed up to 0.35 cell from the screen edge, and free arrows the board won't pan to are skipped.
- **Not yet verified on device:** the grid-view auto-off, the edge-tap margin and unreachable-target skipping (the last build).
  The pinch zoom gets cancelled whenever someone touches the screen. Keep hands off the phone during runs.
- Debug trail: `adb shell cat /sdcard/Android/data/com.patterns.arrowssolver/files/debug/log.txt`, with frames `f*.png` beside it
  (Git Bash needs `MSYS_NO_PATHCONV=1`).

## Zoom / pan from reverse engineering (2026-11-05, details in docs/GAME_INTERNALS.md)
- The game zooms out to at most 27 cells across (orthographic size 30) on this phone. Wider boards can't fit in-game, so the
  panel's **Map** button draws the whole level from the database (green = can go, orange = blocked, red = waiting).
- **Zoom out** button and Analyze: a two-finger pinch with both fingers landing together, repeated until the pitch stops shrinking.
- Every pan is now a two-finger parallel drag: arrows are never picked and no empty spot is needed. One-finger drags within 2.5
  cells of an arrow lock the camera, which caused the old pan loops. Drag starts keep >= 12% of the width from the edges, away from the Back swipe.
- Taps are safe only when no arrow is moving. A tap on a just-vacated cell picks the *only* arrow within 2.5 cells, which is how the morning life was lost.
- Built and installed; **Zoom out / Map / two-finger pans are not yet tested on the device.**

## 2026-11-05 afternoon: official APK, game look, more fixes
- `source/`: official Play Store APK pulled from the phone (base.apk + split_config.arm64_v8a.apk, v0.24.0, sha256 790a9071…).
  REA `inspect_artifact`: 2,151 entries; Android `res/` is only SDK libs. The game UI lives in Unity data. UnityPy shows the fonts
  Gordita Medium/Bold (commercial, not copied) plus Orbitron.
- Look matched to the game's Settings screen: bg #1E1F2D, card #32374E, raised #404668, text #AEB6E1, accent #5B71FF,
  red #FF4B5D; Poppins Medium/SemiBold (OFL, `android/app/src/main/assets/fonts`). The overlay panel, chips, bubble (drawn arrow),
  map and RN setup screen are restyled. The app label is "Arrows Solver". The bubble is hidden in our own app.
- **Ad removal / unlocking "Remove Ads" was declined** (it would bypass a paid purchase). The game was never modified or reinstalled.
- Fixes: the strict "already cleared" rule (an arrow is accepted as gone only if, with it removed, every visible cell matches exactly);
  the Nothing OS edge handle (left, about 14-26% of height) is treated as covered; taps ignored while the camera glides are retried;
  re-locate searches widely after the game re-centres or re-zooms by itself; the dot fingerprint (dots = former arrow cells)
  is used in locate; Grid falls back to dots on nearly empty boards; a failed exploration pan keeps a confirmed match.
- Level 133 was completed with 0 lives lost. Level 134 (Super Hard) is about 75% done in app runs with 0 lives lost.
- **Open:** level 134 stopped safely when the screen showed an arrow one row lower than the level data
  (`mismatch … arrow=58`). Investigate whether the special arrow (`SpecialArrowId`) moves differently.

## Original to-do (done)
1. `SolverService.kt`: AccessibilityService that provides the bubble and panel (Analyze / Solve / Stop / Plan / Close),
   `PlanView` overlay, `capture()` via `takeScreenshot` (hide overlays first), `tap`/`swipe` via
   `dispatchGesture`, and `overlayRects()`. It implements `Device` from `Engine.kt`.
2. `res/xml/accessibility_service_config.xml` (canPerformGestures, canTakeScreenshot,
   typeWindowStateChanged), manifest `<service>` + `<queries>` for `com.ecffri.arrows`, strings.
3. TurboModule bridge: `specs/NativeSolver.ts` with `codegenConfig` in package.json,
   `SolverModule.kt`, `SolverPackage.kt`, and registration in `MainApplication`.
   Methods: isServiceEnabled, openAccessibilitySettings, openGame, getStatus.
4. `App.tsx`: setup screen (enable service, open game, status).
5. Set `minSdkVersion = 30` in `android/build.gradle` (`takeScreenshot` needs API 30).
   compileSdk 37 is not installed yet (36 and 36.1 are), so AGP must download it.
6. Build `gradlew assembleRelease` (JAVA_HOME = Android Studio jbr), install over adb, then test
   **Analyze only** on a level before trusting Solve.
