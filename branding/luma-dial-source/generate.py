#!/usr/bin/env python3
"""Rebuild portable SVG masters and PNG inspection exports from brand.json."""

import hashlib
import io
import json
from pathlib import Path
from xml.sax.saxutils import escape

import cairosvg
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from PIL import Image, PngImagePlugin
import uharfbuzz as hb


ROOT = Path(__file__).resolve().parent
BRAND = json.loads((ROOT / "brand.json").read_text())
C = BRAND["colors"]
SVG = ROOT / "svg"
PREVIEW = ROOT / "previews"
SVG.mkdir(exist_ok=True)
PREVIEW.mkdir(exist_ok=True)


def n(value):
    return f"{value:.6f}".rstrip("0").rstrip(".") if value else "0"


def document(width, height, content, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{n(width)}" '
            f'height="{n(height)}" viewBox="0 0 {n(width)} {n(height)}" '
            f'role="img" aria-label="{escape(title)}">\n'
            f'  <title>{escape(title)}</title>\n{content}\n</svg>\n')


def background(width, height):
    return f'<rect width="{n(width)}" height="{n(height)}" fill="{C["background"]}"/>'


def mark(x=0, y=0, width=400, mono=None, ink=None):
    scale = width / BRAND["mark"]["width"]
    paths = "\n".join(
        f'<path data-name="{name}" fill="{mono or (ink if ink and layer["color"] == "foreground" else C[layer["color"]])}" d="{layer["d"]}"/>'
        for name, layer in BRAND["mark"]["paths"].items()
    )
    # Round caps are closed outline geometry, so exports need no strokes.
    return (f'<g data-name="Luma Dial" transform="translate({n(x)} {n(y)}) '
            f'scale({n(scale)})">\n{paths}\n</g>')


font = instantiateVariableFont(TTFont(ROOT / BRAND["type"]["font"]),
                               {"wght": BRAND["type"]["weight"]})
glyphs = font.getGlyphSet()
font_data = io.BytesIO()
font.save(font_data)
hb_font = hb.Font(hb.Face(font_data.getvalue()))
hb_font.scale = (font["head"].unitsPerEm, font["head"].unitsPerEm)
buffer = hb.Buffer()
buffer.add_str(BRAND["type"]["text"])
buffer.guess_segment_properties()
hb.shape(hb_font, buffer)
order = font.getGlyphOrder()
positioned = []
cursor_x = cursor_y = 0
bounds = BoundsPen(glyphs)
for info, position in zip(buffer.glyph_infos, buffer.glyph_positions):
    name = order[info.codepoint]
    gx = cursor_x + position.x_offset
    gy = cursor_y + position.y_offset
    positioned.append((name, gx, gy))
    glyphs[name].draw(TransformPen(bounds, (1, 0, 0, 1, gx, gy)))
    cursor_x += position.x_advance
    cursor_y += position.y_advance
X0, Y0, X1, Y1 = bounds.bounds
WORD_RATIO = (Y1 - Y0) / (X1 - X0)


def wordmark(x, y, width, color=None):
    scale = width / (X1 - X0)
    paths = []
    for index, (name, gx, gy) in enumerate(positioned):
        pen = SVGPathPen(glyphs, ntos=n)
        transform = (scale, 0, 0, -scale,
                     x + (gx - X0) * scale, y + (Y1 - gy) * scale)
        glyphs[name].draw(TransformPen(pen, transform))
        data = pen.getCommands()
        if data:
            paths.append(f'<path data-glyph="{index}-{name}" d="{data}"/>')
    return f'<g data-name="Picture Tuner wordmark" fill="{color or C["foreground"]}">\n' + "\n".join(paths) + "\n</g>"


def centered_wordmark(x, center_y, width, color=None):
    return wordmark(x, center_y - width * WORD_RATIO / 2, width, color)


def save(name, width, height, content, title):
    svg = document(width, height, content, title)
    (SVG / name).write_text(svg)
    return svg


save("mark.svg", 400, 400, mark(), "Luma Dial white and amber mark")
save("mark-mono-light.svg", 400, 400, mark(mono=C["foreground"]), "Luma Dial light monochrome mark")
save("mark-mono-dark.svg", 400, 400, mark(mono=C["background"]), "Luma Dial dark monochrome mark")
save("wordmark-outlined.svg", 1000, 1000 * WORD_RATIO,
     wordmark(0, 0, 1000), "Picture Tuner outlined wordmark")

# Live text is an editing convenience. The outlined versions fix shaping exactly.
live_scale = 1000 / (X1 - X0)
save("wordmark-editable.svg", 1000, 1000 * WORD_RATIO,
     f'<text x="{n(-X0 * live_scale)}" y="{n(Y1 * live_scale)}" '
     f'font-family="{BRAND["type"]["family"]}" font-weight="{BRAND["type"]["weight"]}" font-size="{n(font["head"].unitsPerEm * live_scale)}" '
     f'fill="{C["foreground"]}">Picture Tuner</text>', "Picture Tuner editable wordmark; install bundled Nunito Sans font")

for name, color in (("logo-horizontal.svg", C["foreground"]),
                    ("logo-horizontal-dark.svg", C["background"])):
    save(name, 1600, 448, mark(56, 56, 336, ink=color) + centered_wordmark(464, 224, 1080, color),
         "Picture Tuner horizontal logo")

icon = BRAND["templates"]["icon"]
icon_width = icon["mark_width"]
icon_height = icon_width
save("icon-square.svg", 1024, 1024,
     background(1024, 1024) + mark((1024 - icon_width) / 2, (1024 - icon_height) / 2, icon_width),
     "Picture Tuner square icon composition")

adaptive_width = BRAND["templates"]["adaptive_foreground"]["mark_width"]
save("icon-adaptive-foreground.svg", 108, 108,
     mark((108 - adaptive_width) / 2, (108 - adaptive_width) / 2, adaptive_width),
     "Picture Tuner adaptive icon foreground")
save("icon-adaptive-background.svg", 108, 108, background(108, 108),
     "Picture Tuner adaptive icon background")

banner = BRAND["templates"]["tv_banner"]
banner_body = (background(1280, 720)
               + mark(banner["left"], (720 - banner["mark_width"]) / 2, banner["mark_width"])
               + centered_wordmark(banner["wordmark_left"], 360, banner["wordmark_width"]))
save("tv-banner.svg", 1280, 720, banner_body, "Picture Tuner 16:9 TV banner")


def label(x, y, text, size=20):
    return (f'<text x="{x}" y="{y}" fill="{C["foreground"]}" '
            f'font-family="sans-serif" font-size="{size}">{escape(text)}</text>')


# Inspection sheet only: production SVGs above contain no clipping or labels.
sheet = background(1600, 1120) + label(64, 62, "Luma Dial / vector source", 28)
sheet += mark(64, 112, 280) + centered_wordmark(408, 252, 1064)
sheet += label(64, 446, "Square icon") + label(448, 446, "16:9 TV banner")
sheet += f'<rect x="64" y="472" width="320" height="320" fill="{C["background"]}" stroke="#343740"/>'
sheet += mark(124, 532, 200)
sheet += f'<g transform="translate(448 472) scale(0.8)">{banner_body}</g>'
sheet += label(64, 854, "Adaptive foreground / circular mask example", 18)
sheet += '<defs><clipPath id="round-mask"><circle cx="164" cy="980" r="82"/></clipPath></defs>'
sheet += f'<circle cx="164" cy="980" r="82" fill="{C["background"]}" stroke="#343740"/>'
sheet += '<g clip-path="url(#round-mask)">' + mark(118, 934, 92) + '</g>'
sheet += label(320, 1000, "Small mark: 48 px", 18) + mark(520, 972, 48)
sheet += label(672, 1000, "Monochrome", 18) + mark(848, 924, 112, C["foreground"])
sheet += label(1080, 1000, "White + amber / transparent interior", 18)
(PREVIEW / "source-sheet.svg").write_text(document(1600, 1120, sheet, "Luma Dial vector source inspection sheet"))

for source_name, output_name, width in (
        ("mark.svg", "mark-transparent.png", 1600),
        ("logo-horizontal.svg", "logo-horizontal.png", 1600),
        ("icon-square.svg", "icon-square.png", 1024),
        ("tv-banner.svg", "tv-banner.png", 1280),
        ("tv-banner.svg", "tv-banner-320x180.png", 320)):
    cairosvg.svg2png(url=str(SVG / source_name), write_to=str(PREVIEW / output_name), output_width=width)
cairosvg.svg2png(url=str(PREVIEW / "source-sheet.svg"), write_to=str(PREVIEW / "source-sheet.png"))

# Verify the interior around the center square is transparent and colors exact.
with Image.open(PREVIEW / "mark-transparent.png").convert("RGBA") as im:
    assert im.size == (1600, 1600)
    assert im.getpixel((800, 400))[3] == 0, "Interior outside center square must remain transparent"
    assert im.getpixel((0, 0))[3] == 0, "Outer corners must remain transparent"
    for point, name in (((800, 800), "foreground"), ((120, 800), "foreground"), ((1324, 276), "amber")):
        expected = tuple(bytes.fromhex(C[name][1:])) + (255,)
        assert im.getpixel(point) == expected, f"Unexpected {name} color"
with Image.open(PREVIEW / "tv-banner-320x180.png") as im:
    assert im.size == (320, 180)

# PNG colors use the same sRGB hex values as SVG and brand.json.
for path in PREVIEW.glob("*.png"):
    with Image.open(path) as im:
        im.load()
        info = PngImagePlugin.PngInfo()
        info.add(b"sRGB", b"\x00")
        info.add_text("Source", "Vector artwork from brand.json and generate.py; not AI raster generation")
        im.save(path, pnginfo=info)

font_bytes = (ROOT / BRAND["type"]["font"]).read_bytes()
manifest = {
    "name": BRAND["name"],
    "direction": BRAND["direction"],
    "version": BRAND["version"],
    "font_sha256": hashlib.sha256(font_bytes).hexdigest(),
    "svg_count": len(list(SVG.glob("*.svg"))),
    "checks": ["SVG rasterization", "transparent interior and corners", "opaque center square", "exact white and amber palette", "320x180 TV export"],
    "wordmark_height_per_width": WORD_RATIO,
    "files": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in sorted([*SVG.glob("*.svg"), *PREVIEW.glob("*.png")])}
}
(ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Wrote {manifest['svg_count']} SVG sources and {len(list(PREVIEW.glob('*.png')))} PNG previews. Export checks passed.")
