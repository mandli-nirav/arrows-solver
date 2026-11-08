# How the Arrows game works (what the solver relies on)

Findings from static analysis of the shipped game (`com.ecffri.arrows`, v0.24.0, Unity IL2CPP) with
[REA](https://github.com/morluto/rea), UnityPy and capstone, plus observation on a real phone.
Method names below are from the game's IL2CPP metadata; nothing here is game code.

## Levels
- Shipped in Addressables bundles `assets/aa/Android/levels-{main,daily,thunderrush}_*.bundle`
  (3,983 main, 646 daily, 223 Thunder Rush in v0.24.0) as `ScriptableLevelData` objects.
- `Data.XSize`, `Data.YSize`, `Data.Arrows[].CompactIndices` (path corners, head first; cell = `x + XSize*y`),
  `Data._staticFlags`, `Data.SpecialArrowId`. Bends may be diagonal ("Diag" levels); heads are always axis-aligned.
- Head direction = head cell minus the next cell (`LevelData.RefreshHeadAndBends`).
- **On screen, data row y = 0 is the bottom row.** Verified by matching a parsed screenshot 76/76 arrows.
- The game can mirror a level at runtime (`LevelData.Mirrored`), so the app tries all four flips.

## The rule
- `ArrowGrid.Collides`: walk from the head one cell at a time in the head's direction until off the board.
  The arrow is blocked if any cell on that walk holds *another* arrow. The body never matters (it follows the head like a snake).
- Removing an arrow never blocks another, so tapping any free arrow is always safe and greedy can't dead-end.
- Tapping a blocked arrow costs a life (`LivesMistakeSystem`); the arrow turns red and leaves by itself once its way is clear.

## Touch handling (why the solver taps the way it does)
- `ArrowInputUtils.GetArrow`: the closest arrow within **1.0 cell** of the touch; if none, the *only* arrow within **2.5 cells**.
  → Never tap where an arrow just left: the 2.5-cell fallback can pick a different, blocked arrow. Wait until nothing moves.
- `InputUiView.OnPointerDown/OnPointerClick` only act when `fingerCount <= 1`. Two fingers landing together never pick an arrow.
- A single finger landing within range of an arrow marks it "touching", and `CameraMoveSystem` then locks pan and zoom
  until all fingers lift (`_lockedUntilFingerUp`). → The solver pans and zooms with **two fingers**.
- `isIgnoreClick = isCameraMoving`: taps made while the camera is still gliding are ignored. → Wait for two identical frames.

## Camera
- Zoom (`CameraZoomUtility`, portrait): orthographic size 15..30, start = 15 + 0.4 × 15 = 21.
  On a 9:20 phone that is at most **27 cells across** (about 40 px per cell on a 1080 px screen); start is about 57 px.
- With the server-side `LevelBasedZoom` experiment on, `CameraBoardGeometry.LevelZoom` allows up to (board width + 12) / (2·aspect).
- Pinch: `ZoomOrthoCamera`, delta = (previous finger distance − current distance) × 1.2 in world units; `ReturnZoom` springs back to the bounds.
- Input is ignored until the level is playing and `cameraStage == 10` (the intro zoom has finished).
- As the board empties the camera re-centres (`FreeAreaFocusY`) and may re-zoom by itself.

- `CameraMoveSystem` has no player-facing "reset view". Pans go through `ClampPan`/`ClampedPan` against
  `CameraBoardGeometry.PanBounds`, with `Rubberband`/`ElasticMove` springing back past the bounds, so a pan toward the
  middle can never lose the board. The game's own reset (`CameraStageResetSystem`) runs at level start only.
- `LockLevelInputSystem.LockedByCameraStage`: input is locked while a camera stage runs (intro reveal, hint focus).
- `SavedZoomRatio`: the zoom is remembered between levels.

## Game systems (IL2CPP v39 metadata + arm64 call resolution, 2026-11-09)
`Assembly-CSharp` has 2,630 types, almost all in the global namespace; the game is built on Entitas ECS
(314 `*System` classes). Calls below were resolved by disassembling each method and naming its `bl` targets
from the metadata's method addresses; constants are read from the code.

**Special arrow** (`LevelFlags.SpecialArrow`, `Data.SpecialArrowId`)
- `SpecialArrowInteractionSystem` fires when the special arrow is tapped while free (`TryToMove`, not `Blocked`).
- It calls `SpecialArrowUtil.SelectChainArrows(grid, special, arrows, maxChain = 5)`: on a copy of the grid it
  removes the special arrow, then repeatedly picks an arrow that no longer collides (`ArrowGrid.Collides`) and
  removes it too, up to 5 arrows.
- Each picked arrow gets an `AutoRemoveTimer` of `ArrowReleaseDelay(i)` = **0.2 s × i** and then leaves by itself.
  → One tap on the special arrow clears up to 5 more arrows a moment later. The solver must expect those to vanish
  (this is the "mismatch" seen on level 134).

**Auto-remove** (`ArrowAutoRemoveSystem`, `…QueueSystem`, `…LevelStartSystem`)
- An arrow with an `AutoRemoveTimer` counts down; when it reaches 0 and the arrow is not `Blocked`, it becomes
  `AutoRemoving` and moves out on its own. Blocked ones keep waiting.
- The queue spaces timers **0.1 s** apart. This is how red arrows (tapped while blocked) leave once their way clears.

**Taps** (`ArrowClickSystem`)
- `pointerClick` → `ArrowInputUtils.GetArrow` (1.0-cell then 2.5-cell rule) → ignored while `isBlockedAfterMistake`
  → otherwise the arrow is marked `TryToMove`.
- `ArrowBlockSystem` sets `Blocked`/`BlockingArrow` from `ArrowGrid.Collides`. A blocked `TryToMove` is a mistake.

**Mistakes** (`LivesMistakeSystem`, `RemoveBlockedAfterMistakeSystem`)
- A mistake takes a life (`ReplaceLives`), fails the level at 0 lives, and creates the `BlockedAfterMistake` entity
  with a **0.35 s** timer: **all taps are ignored for 0.35 s after a mistake.**

**Hint** (`HintSystem`)
- Picks from the game's own `_freeArrows` group, spends a hint (wallet), adds `HintAnimation` and **moves the camera**
  (`ReplaceCameraStage`) to the arrow. `HintBoosterDisabledWhenNoFreeArrowsSystem` greys the button when nothing is free.

**Other gameplay systems**
- `ArrowGuidelineShowOnHoldSystem` / `…ToggleOnHoldSystem`: holding an arrow draws its exit path; the
  "keep guidelines" choice is saved (`ArrowGuidelinesStaySavegame`).
- `ComboSystem`: arrows cleared within `ComboInterval` of each other build a combo (colour effect only).
- `SlomoSystem`: a developer key (`Input.GetKey`) sets `Time.timeScale` between 1 and 4; not reachable on a phone.
- `PenToolGuideLineSystem`, `PenTutorial*`: a pen tool that draws a guide line; its tutorial opens after a mistake.
- `ArrowLevelBoundsSystem`: level bounds from all arrow points (feeds the camera's pan bounds).
- `CameraRevealLevelShapeStageSystem`: the intro reveal of the level shape.
- Level kinds: `LevelFlags` = Shape, Color, Blob, Snowflake, Thunder, Removed, Abstract, Onboarding, SpecialArrow,
  Splat, Diagonal, MirroredVertical, MirroredHorizontal; difficulty strings Hard, SuperHard, Nightmare.

**Not gameplay** (most of the 314 systems): ads (AppLovin MAX, Facebook, interstitial and rewarded flow, forced ads,
banner), IAP (no-ads, hints, streak freezes), Firebase/Singular analytics and experiments, remote config, cloud save,
leagues and Thunder Rush (server `/api/v1/ThunderRush`), daily challenges and streaks, awards, notifications.

## Screen colours (1080×2400 Nothing Phone 1, dark mode)
| What | RGB |
|---|---|
| background | 30,32,45 (#1E1F2D) |
| arrow line | 172,180,244 |
| emptied-cell dot (= a cell some arrow used to occupy) | 58,62,85 |
| `#` button | 64,70,104 |
| `#` grid-view guide lines | 56,64,95 |
| arrow while moving | ~111..131,130..147,250 |
| blocked (red) arrow and hearts | 255,75,93 |

UI palette (Settings screen): card #32374E, raised #404668, text #AEB6E1, accent #5B71FF. Font: Gordita (commercial);
this app uses Poppins (OFL) as the closest free match.
