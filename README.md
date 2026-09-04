# Lambda HUD — Half-Life watch face for Wear OS

A Half-Life themed watch face, built and tested on a Galaxy Watch 5 (SM-R910).
Two themes, switchable on the watch: **Lambda** (HL1 HEV amber) and **Combine**
(HL2 neon blue).

CRT scanlines and aperture grille, a seconds arc, split time, four configurable
complication slots, an animated background watermark, and a heart rate read
straight from Health Services.

> **No game artwork is included** — it is Valve's. The face builds and runs
> without it; `setup_assets.py` imports it from sources you fetch yourself.
>
> Unofficial fan project, not affiliated with or endorsed by Valve. Half-Life,
> the lambda logo and Combine imagery are trademarks of Valve Corporation.

## Build

You need a JDK and the Android SDK. Installing
[Android Studio](https://developer.android.com/studio) gets both and is the easy
route — you never have to open it. If you would rather not, the
[command-line tools](https://developer.android.com/studio#command-line-tools-only)
plus any JDK 17+ work too; point `ANDROID_HOME` at the SDK.

Works on macOS, Linux and Windows. `adb` must be on your `PATH` (it ships with
the SDK, under `platform-tools`).

```bash
git clone git@github.com:resonanse-cascade/hl-watchface.git
cd hl-watchface

python3 -m pip install --user pillow          # optional, for the artwork
python3 setup_assets.py ~/Downloads/hud.png   # optional, see below

./build.sh --install                          # Windows: build.bat --install
```

The build script locates the JDK, writes `local.properties`, builds, and installs
to a connected watch. Without `--install` it just builds to
`app/build/outputs/apk/debug/app-debug.apk`.

## Artwork

| What | Source |
|---|---|
| HL1 HUD sprites | [The Spriters Resource](https://www.spriters-resource.com/pc_computer/halflife/asset/149252/) — download "Miscellaneous - HUD", pass the file to the script |
| HL2 Combine insignia | [Half-Life wiki](https://half-life.fandom.com/wiki/Combine_imagery) — fetched by the script |

`setup_assets.py` slices the sheet, rewrites each sprite so the renderer can tint
it to either palette, and converts the Combine SVGs to vector drawables. Output
lands in `app/src/main/res/drawable/` and is git-ignored.

Without artwork the face still works: rows use each data source's own icon, the
Combine emblem falls back to a drawn shape, and the watermark and damage strip
are skipped.

## Connecting the watch

Wear OS installs over Wi-Fi, not USB. On the watch, tap `Settings` → `About
watch` → `Software version` seven times, then enable **ADB debugging** and
**Wireless debugging**. Open `Wireless debugging` → `Pair new device` for a
pairing port and code.

```bash
adb pair WATCH_IP:PAIRING_PORT     # six-digit code from the watch
adb connect WATCH_IP:ADB_PORT      # the port on the main screen — a different one
```

Both ports change whenever wireless debugging is toggled. `./install_watchface.sh
WATCH_IP ADB_PORT` wraps connect-and-install.

## Using it

Long-press the face → **Customize** to switch theme and assign complications.
The first time you open a slot, accept the complication-data permission — the
chooser cannot open without it.

Each row shows the data source's icon, its value, and a segmented bar. **The bar
only appears when the value has a range** (a `RANGED_VALUE`/`GOAL_PROGRESS`
complication, or text like `72%`); most sources are plain text, so they show no
bar rather than an empty one that reads as zero.

**Heart rate** does not come from the complication. Samsung Health refuses real
values to a side-loaded face, so the face measures it via Health Services. Tap
**[ PULSE ] allow heart rate sensor** in Customize to grant `BODY_SENSORS`. The
sensor runs in 30-second bursts every 3 minutes while worn — a lock takes ~20 s,
so shorter bursts return nothing. `--` means no current reading, which is correct
on a charger or off the wrist.

## Notes

Implementation details, and the several Wear OS traps this hit along the way, are
documented in comments at the relevant code:
`LambdaWatchFaceService.kt`, `HeartRateSource.kt`, `WatchFaceConfigActivity.kt`,
`AndroidManifest.xml` and `setup_assets.py`.

Side-loaded faces generally do not appear in the phone companion app; use the
on-watch picker.
