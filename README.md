# Lambda HUD — Half-Life watch face for Wear OS

A Half-Life themed watch face, built and tested on a Galaxy Watch 5 (SM-R910).
Two themes, switchable on the watch: **Lambda** (HL1 HEV amber) and **Combine**
(HL2 neon blue).

CRT scanlines and aperture grille, a seconds arc, split time, four configurable
complication slots, an animated background watermark, and a heart rate read
straight from Health Services.

| Lambda | Combine |
|---|---|
| ![Lambda theme](https://i.redd.it/2ugw1lxqednh1.gif) | ![Combine theme](https://i.redd.it/cp5yqkdrednh1.gif) |

Both shown with the Half-Life artwork imported — see [Artwork](#artwork). The
bottom strip cycles the HL1 damage indicators; the background watermark sweeps
once a minute.

> **No game artwork is included** — it is Valve's. The face builds and runs
> without it; `setup_assets.py` imports it from sources you fetch yourself.
>
> Unofficial fan project, not affiliated with or endorsed by Valve. Half-Life,
> the lambda logo and Combine imagery are trademarks of Valve Corporation.

## Install

Works on macOS, Linux and Windows. Windows users: run `build.bat` wherever these
steps say `./build.sh`.

**1. Install the build tools.** You need a JDK and the Android SDK.
[Android Studio](https://developer.android.com/studio) gets you both — install it
and you are done; you never have to open it. (Prefer not to? The
[command-line tools](https://developer.android.com/studio#command-line-tools-only)
plus any JDK 17+ also work; set `ANDROID_HOME` to the SDK.) Make sure `adb` is on
your `PATH` — it lives in the SDK's `platform-tools`.

**2. Turn on debugging on the watch.** `Settings` → `About watch` → `Software`,
then tap `Software version` seven times. Go back to `Settings` →
`Developer options` and enable **ADB debugging** and **Wireless debugging**.

**3. Pair the watch.** Open `Wireless debugging` → `Pair new device`. It shows an
IP with a *pairing* port and a six-digit code:

```bash
adb pair 192.168.1.80:37123     # then type the six-digit code
adb connect 192.168.1.80:5555   # port from the main Wireless debugging screen
```

The connect port is a **different** number from the pairing port, and both change
every time wireless debugging is toggled off and on.

**4. Get the code.**

```bash
git clone git@github.com:resonanse-cascade/hl-watchface.git
cd hl-watchface
```

**5. Add the Half-Life artwork.** Optional — skip it and the face still works,
just plainer. See [Artwork](#artwork) for where the files come from.

```bash
python3 -m pip install --user pillow
python3 setup_assets.py ~/Downloads/hud.png
```

**6. Build and install.**

```bash
./build.sh --install
```

This finds the JDK, writes `local.properties`, builds, and pushes the APK to the
watch. Without `--install` it only builds, to
`app/build/outputs/apk/debug/app-debug.apk`.

**7. Pick the face.** On the watch, long-press the current face, swipe to
**Lambda HUD**, and tap it.

**8. Set it up.** Long-press again → **Customize** to choose a theme and assign
complications. Accept the permission prompt the first time you open a slot.

### If something goes wrong

- `adb connect` refused — pair again (step 3); the ports change.
- "SDK location not found" — set `ANDROID_HOME`, or install Android Studio.
- "No Java found" — same fix; the JDK ships inside Android Studio.
- Face missing from the picker — `adb uninstall com.resonanse.hlwatchface`, then
  build and install again.

## Artwork

None of it is included here — it is Valve's. Two sources, both fetched by you:

| What | Source |
|---|---|
| HL1 HUD sprites | [The Spriters Resource](https://www.spriters-resource.com/pc_computer/halflife/asset/149252/) — download "Miscellaneous - HUD" and pass the file to the script |
| HL2 Combine insignia | [Half-Life wiki](https://half-life.fandom.com/wiki/Combine_imagery) — fetched by the script automatically |

`setup_assets.py` slices the sheet, rewrites each sprite so the renderer can tint
it to either palette, and converts the Combine SVGs to vector drawables. Output
lands in `app/src/main/res/drawable/` and is git-ignored.

Without it the face still works: rows use each data source's own icon, the
Combine emblem falls back to a drawn shape, and the watermark and damage strip
are skipped.

## Using it

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
