#!/usr/bin/env python3
"""
Import the game artwork this watch face draws.

This repository deliberately ships no Half-Life artwork — it is Valve's, and
redistributing it is not ours to do. The face runs without it (rows fall back to
each data source's own icon), but the HUD sprites and Combine insignia are what
make it look right, so this script imports them from sources you fetch yourself.

    python3 setup_assets.py path/to/hud-sheet.png

HL1 HUD sprite sheet — download "Miscellaneous - HUD" from The Spriters Resource:

    https://www.spriters-resource.com/pc_computer/halflife/asset/149252/

Combine insignia (HL2) are fetched from the Half-Life wiki automatically:

    https://half-life.fandom.com/wiki/Combine_imagery

Everything lands in app/src/main/res/drawable/ and is git-ignored.
"""

import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "app", "src", "main", "res", "drawable")

SPRITERS_URL = "https://www.spriters-resource.com/pc_computer/halflife/asset/149252/"
COMBINE_SVGS = {
    "hl2_combine": (
        "https://static.wikia.nocookie.net/half-life/images/1/1e/"
        "Combine_main_symbol.svg/revision/latest?cb=20100327182309&path-prefix=en"
    ),
    "hl2_cmb": (
        "https://static.wikia.nocookie.net/half-life/images/e/e3/"
        "CMB.svg/revision/latest?cb=20100327183311&path-prefix=en"
    ),
}

# Boxes measured against the 585x842 Spriters Resource sheet.
HL1_SPRITES = {
    # Gluon Gun: its bright variant sits further right than the other weapons',
    # so it needs its own box rather than the uniform column.
    "hl_egon": (213, 316, 338, 407),
    "hl_icon_suit": (355, 227, 391, 263),
    "hl_icon_health": (421, 228, 455, 256),
    "hl_icon_battery": (464, 228, 500, 256),
}
# Damage strip: one uniform band, eight 64x64 cells in the sheet's own order.
DMG_Y0, DMG_Y1, DMG_CELL = 679, 743, 64
DAMAGE = ["chem", "o2", "bio", "shock", "poison", "freeze", "fire", "rad"]
for _i, _name in enumerate(DAMAGE):
    HL1_SPRITES[f"hl_dmg_{_name}"] = (_i * DMG_CELL, DMG_Y0, (_i + 1) * DMG_CELL, DMG_Y1)

EXPECTED_SHEET = (585, 842)


def die(msg):
    print(f"\nERROR: {msg}\n", file=sys.stderr)
    sys.exit(1)


def import_hl1(sheet_path):
    try:
        from PIL import Image
    except ImportError:
        die("Pillow is not installed. Run:  python3 -m pip install --user pillow")

    if not os.path.isfile(sheet_path):
        die(f"No such file: {sheet_path}\nDownload the sheet from {SPRITERS_URL}")

    img = Image.open(sheet_path).convert("RGBA")
    if img.size != EXPECTED_SHEET:
        print(
            f"  ! sheet is {img.size[0]}x{img.size[1]}, expected "
            f"{EXPECTED_SHEET[0]}x{EXPECTED_SHEET[1]} — crops may be off.\n"
            f"    Make sure this is 'Miscellaneous - HUD' from {SPRITERS_URL}"
        )

    for name, box in sorted(HL1_SPRITES.items()):
        crop = img.crop(box).convert("RGBA")
        px = crop.load()
        # Rewrite as a white glyph whose alpha follows source luminance, so the
        # renderer can tint it to whichever palette is active.
        for y in range(crop.height):
            for x in range(crop.width):
                r, g, b, _ = px[x, y]
                px[x, y] = (255, 255, 255, max(r, g, b))
        bbox = crop.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
        if bbox:
            crop = crop.crop(bbox)
        crop.save(os.path.join(OUT_DIR, name + ".png"))
        print(f"  {name}.png  ({crop.width}x{crop.height})")


def svg_to_vector(svg, name):
    """Convert the wiki's SVGs to an Android VectorDrawable.

    VectorDrawable understands neither <polygon> nor <rect>, and CMB.svg is built
    entirely from those, so each shape is rewritten as an M/L/Z subpath.
    """
    vb = re.search(r'viewBox="([\d.\s-]+)"', svg)
    if not vb:
        die(f"{name}: no viewBox in the downloaded SVG")
    _, _, vw, vh = [float(v) for v in vb.group(1).split()]

    subpaths = []
    for d in re.findall(r'\sd="([^"]*)"', svg, re.S):
        subpaths.append(" ".join(d.split()))
    for pts in re.findall(r'<polygon[^>]*points="([^"]*)"', svg):
        n = re.split(r"[\s,]+", pts.strip())
        c = [(n[i], n[i + 1]) for i in range(0, len(n) - 1, 2)]
        subpaths.append("M" + f"{c[0][0]},{c[0][1]}" + "".join(f"L{x},{y}" for x, y in c[1:]) + "Z")
    for tag in re.findall(r"<rect[^>]*>", svg):
        g = lambda a: float(re.search(a + r'="([-\d.]+)"', tag).group(1))
        x, y, w, h = g("x"), g("y"), g("width"), g("height")
        subpaths.append(f"M{x},{y}L{x+w},{y}L{x+w},{y+h}L{x},{y+h}Z")

    if not subpaths:
        die(f"{name}: found no drawable shapes in the SVG")

    data = "".join(subpaths).replace("&", "&amp;").replace('"', "&quot;")
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Imported by setup_assets.py from the Half-Life wiki. Not redistributed. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{data}" />
</vector>
"""
    with open(os.path.join(OUT_DIR, name + ".xml"), "w") as f:
        f.write(xml)
    print(f"  {name}.xml  ({len(subpaths)} subpaths)")


def import_combine():
    for name, url in COMBINE_SVGS.items():
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                svg = r.read().decode("utf-8", "replace")
        except Exception as e:
            print(f"  ! could not fetch {name}: {e}")
            print(f"    The Combine theme will fall back to a drawn shape.")
            continue
        svg_to_vector(svg, name)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    if len(sys.argv) < 2:
        print(__doc__)
        die("Give me the path to the HL1 HUD sheet.")
    print("Importing HL1 HUD sprites...")
    import_hl1(sys.argv[1])
    print("Fetching Combine insignia...")
    import_combine()
    print(f"\nDone. Artwork is in {os.path.relpath(OUT_DIR, HERE)} (git-ignored).")
    print("Now build:  ./build.sh")


if __name__ == "__main__":
    main()
