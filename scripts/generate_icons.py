"""Package the approved HAM artwork without redrawing it.

The source file is deliberately retained byte-for-byte in design/. Re-run this
script after changing the approved source, then inspect the launcher previews.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design" / "ham-approved-original.png"
RES = ROOT / "app" / "src" / "main" / "res"
DESIGN = ROOT / "design"
ART = Image.open(SOURCE).convert("RGBA")
if ART.size != (1254, 1254):
    raise SystemExit(f"Unexpected approved artwork size: {ART.size}")

# Keep all colored content well within the adaptive icon's central safe zone.
# The original cream card remains intact; the outer fill matches its center.
FILL = (254, 247, 232, 255)

def foreground(size):
    canvas = Image.new("RGBA", (size, size), FILL)
    fitted = ART.resize((round(size * .87), round(size * .87)), Image.Resampling.LANCZOS)
    offset = ((size - fitted.width) // 2, (size - fitted.height) // 2)
    canvas.alpha_composite(fitted, offset)
    return canvas

def monochrome(size):
    # Saturated teal/orange strokes are the mark. Low-saturation cream and
    # white pixels belong to the baked-in card, not the monochrome glyph.
    rgb = ART.convert("RGB")
    hsv = rgb.convert("HSV")
    mask = hsv.getchannel("S").point(lambda value: 255 if value >= 75 else 0)
    mask = mask.filter(ImageFilter.MedianFilter(3))
    mask = mask.resize((round(size * .87), round(size * .87)), Image.Resampling.LANCZOS)
    alpha = Image.new("L", (size, size), 0)
    alpha.paste(mask, ((size - mask.width) // 2, (size - mask.height) // 2))
    glyph = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    glyph.putalpha(alpha)
    return glyph

for density, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    output = RES / f"mipmap-{density}"
    output.mkdir(parents=True, exist_ok=True)
    # Full 108dp layers for adaptive icons, also safe as legacy bitmap icons.
    foreground(px).save(output / "ic_launcher_foreground.png", optimize=True)
    monochrome(px).save(output / "ic_launcher_monochrome.png", optimize=True)
    legacy_size = round(px * 48 / 108)
    foreground(legacy_size).save(output / "ic_launcher.png", optimize=True)
    circle = foreground(legacy_size)
    circle_mask = Image.new("L", circle.size, 0)
    ImageDraw.Draw(circle_mask).ellipse((0, 0, legacy_size - 1, legacy_size - 1), fill=255)
    circle.putalpha(circle_mask)
    circle.save(output / "ic_launcher_round.png", optimize=True)

# Google Play listing artwork uses the approved source at 512px, without a
# launcher mask. This is an internal candidate until publication approval.
ART.convert("RGB").resize((512, 512), Image.Resampling.LANCZOS).save(
    DESIGN / "ham-google-play-512.png", optimize=True
)

# Mask previews are for visual QA only, never shipped in the Android package.
sheet = Image.new("RGBA", (432 * 3, 432), "#dde3e1")
base = foreground(432)
for index, shape in enumerate(("circle", "squircle", "square")):
    mask = Image.new("L", (432, 432), 0)
    draw = ImageDraw.Draw(mask)
    if shape == "circle":
        draw.ellipse((0, 0, 431, 431), fill=255)
    elif shape == "squircle":
        draw.rounded_rectangle((0, 0, 431, 431), radius=105, fill=255)
    else:
        draw.rectangle((0, 0, 431, 431), fill=255)
    clipped = base.copy()
    clipped.putalpha(mask)
    sheet.alpha_composite(clipped, (index * 432, 0))
sheet.convert("RGB").save(DESIGN / "ham-launcher-mask-preview.png", optimize=True)

print("Generated icons from", SOURCE)
