# Lambda HUD — Half-Life watch face for Wear OS

A Half-Life themed watch face for Wear OS, built and tested on a Samsung Galaxy
Watch 5 (SM-R910). Two themes: **Lambda** (HL1 HEV suit amber) and **Combine**
(HL2 neon blue), switchable on the watch.

> **No game artwork is included.** The Half-Life sprites and Combine insignia
> belong to Valve, so this repository ships none of them. The face builds and runs
> without them — complication rows fall back to each data source's own icon. To get
> the full look, `setup_assets.py` imports the artwork from sources you fetch
> yourself. See [Artwork](#artwork).
>
> Unofficial fan project. Not affiliated with or endorsed by Valve. Half-Life, the
> lambda logo and Combine imagery are trademarks of Valve Corporation.

## Quick start

You need [Android Studio](https://developer.android.com/studio) installed (for the
Android SDK and a bundled Java), and a Wear OS watch with developer options on.

```bash
git clone git@github.com:resonanse-cascade/hl-watchface.git
cd hl-watchface

# optional, but this is what makes it look like Half-Life — see Artwork below
python3 -m pip install --user pillow
python3 setup_assets.py ~/Downloads/hud-sheet.png

./build.sh --install
```

`build.sh` finds the JDK inside Android Studio, writes `local.properties` for you,
builds the APK, and installs it if a watch is connected. Without `--install` it just
builds, leaving the APK at `app/build/outputs/apk/debug/app-debug.apk`.

Then on the watch: long-press the current face, swipe to **Lambda HUD**, and use
**Customize** to pick a theme and assign complications.

## Artwork

Two sources, both fetched by you rather than shipped here:

| What | Where it comes from |
|---|---|
| HL1 HUD sprites — damage icons, suit/health/battery glyphs, gluon gun | ["Miscellaneous - HUD" on The Spriters Resource](https://www.spriters-resource.com/pc_computer/halflife/asset/149252/) — download the sheet, pass the file to the script |
| HL2 Combine insignia — claw symbol, CMB glyph strip | [`Combine_main_symbol.svg`](https://static.wikia.nocookie.net/half-life/images/1/1e/Combine_main_symbol.svg/revision/latest?cb=20100327182309&path-prefix=en) and [`CMB.svg`](https://static.wikia.nocookie.net/half-life/images/e/e3/CMB.svg/revision/latest?cb=20100327183311&path-prefix=en) from the [Half-Life wiki](https://half-life.fandom.com/wiki/Combine_imagery) — fetched automatically by the script |

```bash
python3 setup_assets.py path/to/hud-sheet.png
```

The script slices the sheet into sprites, rewrites each as a white glyph whose alpha
follows source luminance (so the renderer can tint it to either palette), and
converts the two Combine SVGs into Android vector drawables. Everything lands in
`app/src/main/res/drawable/` and is git-ignored.

**Without the artwork** the face still works: complication rows use the icon each
data source provides, the Combine emblem falls back to a drawn shape, and the
background watermark and bottom damage strip are simply not drawn.

## Connecting the watch

Wear OS installs over Wi-Fi rather than USB.

1. On the watch: `Settings` → `About watch` → `Software`, tap `Software version`
   seven times to unlock developer options.
2. `Settings` → `Developer options` → enable **ADB debugging** and
   **Wireless debugging**.
3. Open `Wireless debugging` → `Pair new device`. It shows an IP with a *pairing*
   port and a six-digit code.

```bash
adb pair WATCH_IP:PAIRING_PORT      # enter the six-digit code
adb connect WATCH_IP:ADB_PORT       # the port on the Wireless debugging screen
```

`ADB_PORT` is the one listed on the main Wireless debugging screen, and it is
*different* from the pairing port. Both change whenever wireless debugging is
toggled, so if `adb connect` starts refusing connections, pair again.

`./install_watchface.sh WATCH_IP ADB_PORT` wraps the connect-and-install steps.

## Set Complications

1. Long-press `Lambda HUD` -> `Customize`.
2. The first time you open a slot, the watch asks to allow the face to receive
   complication data. You must accept: the chooser cannot open without it.
3. Assign:
   - **DATE** (top slot): `Date`
   - **SUIT** (L1): `Watch battery`
   - **AUX** (L2): `Sunrise / Sunset` or `Heart rate`
   - **MOVE** (L3): `Steps` or `Calories`

Each editor button shows the data source currently assigned to that slot.

### Reading a row

    [icon]  VALUE      [ LABEL ]
    ███████░░░                     ← only when the value has a range

The icon and value come from the data source. The bar appears **only** when the source
reports a range — a `RANGED_VALUE`/`GOAL_PROGRESS` complication, or short text like
`72%` — and then filled segments are the value's position between min and max.

Most sources on this watch (steps, heart rate, sunrise/sunset) are plain `SHORT_TEXT`
with no range, so they show no bar and the value takes the whole row height instead.
An always-empty bar would read as "zero" rather than "not applicable".

### Heart rate

The heart rate **complication** cannot show a real BPM here, so the face does not use
it for the number. Samsung Health's provider checks
`ComplicationRequest.isForSafeWatchFace`, logs `safe:2` (`TargetWatchFaceSafety.UNSAFE`)
for every request from a side-loaded face, and answers with its own name instead of a
value — while its `HeartRateTracker` is emitting live readings the whole time. It is a
deliberate per-complication privacy gate on vitals: the same app's Steps complication
returns real data to the same face. Samsung Health declares no app-level
`SAFE_WATCH_FACES` allow-list, so "safe" is decided by the platform and a side-loaded
face cannot qualify. Granting `BODY_SENSORS` / `health.READ_HEART_RATE` does not change
it; that was tested.

So the face reads heart rate itself, from **Health Services**, which has no such gate
(`HeartRateSource.kt`). Assign `Heart rate` to a slot as usual — the face keeps the
provider's heart icon and tap action, and substitutes its own live BPM for the value.
The bar is scaled 40-180 bpm, which finally gives that row a meaningful range.

This needs `BODY_SENSORS`. A `WatchFaceService` cannot request a runtime permission, so
the Customize screen shows a **[ PULSE ] allow heart rate sensor** button while it is
missing; the button disappears once granted.

A heart rate row shows `--` when there is no usable reading, and never the
complication's placeholder text.

Two Health Services sources feed one value, because neither works alone:

- **Passive** (`PassiveMonitoringClient` + `HeartRateListenerService`) survives ambient,
  process death and reboot, and supplies a last-known reading the moment the face
  starts. On its own it updates far too rarely to feel live — four minutes of watching
  produced no batch at all, which is why the row looked permanently empty.
- **Live** (`MeasureClient`, `startLive`) runs only while the face is awake *and* a slot
  is actually showing heart rate, refreshing within seconds of a wrist raise. It holds
  the optical sensor on, so it is stopped the moment the face goes ambient. On its own
  it never got a lock before the screen dozed.

Because the listener can deliver while this process is dead, the reading is persisted to
SharedPreferences and read back on first use. `STALE_AFTER_MS` is 30 minutes — a
last-known window rather than a freshness one, since the live callback refreshes it
whenever you are actually looking. It still expires, so a watch left off the wrist stops
claiming a heart rate. `onPermissionLost` clears it outright.

So `--` on a charger or off the wrist is correct behaviour, not a failure.

Note the picker preview shows a number even without any of this, because previews
render the data source's *sample* data via `getPreviewData()`, which is never gated.
Preview differing from the live face is expected, not a symptom.

## Themes

`THEME_SETTING` is a `ListUserStyleSetting` with two options, so the choice is stored
by the watch face framework like any other style. The **[ THEME ]** button at the top
of Customize cycles it; adding a third palette needs only a new `Palette` plus an
option in that list.

Every colour lives in the `Palette` data class and `applyPalette` repaints every brush
in one pass, so nothing hardcodes a colour at draw time. Two things are easy to miss
when adding a theme:

- `tintedIcon` must tint from `palette.accent`, not the `ORANGE` constant, or the
  complication icons stay amber on a blue face.
- The tinted-drawable cache has the old colour baked in, so `applyPalette` clears it.

### HL2 artwork

`hl2_combine.xml` and `hl2_cmb.xml` were converted from the SVGs on the Half-Life wiki
(`Combine_main_symbol.svg`, `CMB.svg`). VectorDrawable supports neither `<polygon>` nor
`<rect>`, so `CMB.svg`'s ten shapes were rewritten as `M/L/Z` subpaths. Both are white
so they tint to whatever the palette says, and both are loaded by
`resources.getIdentifier` — delete either one and the face falls back to a drawn path
rather than failing to build.

Note `BitmapFactory.decodeResource` cannot decode a VectorDrawable; these load through
`Context.getDrawable`.

### Editor gotchas (all three cost a debugging session)

- The face must declare `com.google.android.wearable.permission.RECEIVE_COMPLICATION_DATA`.
  Without it `ComplicationHelperActivity`'s runtime permission request is auto-denied
  and the chooser closes instantly — which looks exactly like the buttons doing nothing.
- `WatchFaceConfigActivity` must **not** call `EditorSession.close()` itself.
  `createOnWatchEditorSession` registers a lifecycle observer that closes and commits
  on `ON_DESTROY`; a second `close()` throws out of `onDestroy`, killing the process
  mid-commit, so the slot choice is silently rolled back and only the editor's own
  button label appears to change.
- `Renderer.SharedAssets.onDestroy()` must not recycle or null the bitmaps. One
  instance is shared by every renderer in the process, and opening the editor creates
  a second one; tearing that down otherwise blanks every sprite on the live face until
  the process restarts.

## Showing Up in the Phone Companion App

The manifest declares `com.google.android.wearable.standalone` and ships a real preview
bitmap (`res/drawable-nodpi/preview_watch_face.png`, regenerate it from a screenshot),
which are the prerequisites for a companion app to list a watch-installed face.

Be aware, though: this APK is side-loaded straight to the watch over adb, so the phone
has no copy of it and nothing registered it with the Galaxy Store. Samsung's Galaxy
Wearable watch-face list is driven largely by that catalogue, and side-loaded faces
commonly appear only in the on-watch picker. Getting it to list reliably on the phone
generally means distributing the app rather than side-loading it.

## Optional: Closer HL1 HUD Font

Current build uses a monospace system font with glow. If you want a stricter HL-style font:
1. Add a font file to `app/src/main/res/font/` (example `hl_hud.ttf`).
2. In the renderer, replace `Typeface.MONOSPACE` with `ResourcesCompat.getFont(context, R.font.hl_hud)`.
3. Rebuild and reinstall.

## Troubleshooting

- If `adb` is not found, install Android Platform Tools.
- If pairing fails, toggle Wireless debugging off/on and pair again.
- If watch face does not appear, uninstall old package first:

```bash
adb -s WATCH_IP:ADB_PORT uninstall com.resonanse.hlwatchface
adb -s WATCH_IP:ADB_PORT install app/build/outputs/apk/debug/app-debug.apk
```
