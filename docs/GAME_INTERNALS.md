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
