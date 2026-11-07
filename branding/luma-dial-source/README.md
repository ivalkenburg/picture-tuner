# Luma Dial source kit

Editable vector sources for Picture Tuner's selected **Luma Dial** direction. Open `previews/source-sheet.png` to inspect the set, then pass this entire folder or the sibling ZIP to the agent preparing application resources. `HANDOFF.md` contains its brief.

## Artwork

| Source | Purpose |
| --- | --- |
| `svg/mark.svg` | Transparent off-white ring, center square, and amber tick |
| `svg/mark-mono-light.svg`, `svg/mark-mono-dark.svg` | Single-color variants with all three parts preserved |
| `svg/wordmark-outlined.svg` | Portable lettering, with every glyph converted to paths |
| `svg/wordmark-editable.svg` | Live text; install `fonts/NunitoSans-VF.ttf` and use weight 800 |
| `svg/logo-horizontal.svg` | Off-white and amber symbol with light outlined lettering |
| `svg/logo-horizontal-dark.svg` | Charcoal and amber symbol with dark outlined lettering |
| `svg/icon-square.svg` | Full-bleed square icon composition, 1024 × 1024 |
| `svg/icon-adaptive-foreground.svg` | Transparent foreground layer, 108 × 108 source units |
| `svg/icon-adaptive-background.svg` | Separate solid charcoal background, 108 × 108 source units |
| `svg/tv-banner.svg` | 16:9 composition, 1280 × 720 |

All production SVGs are self-contained geometry except the explicitly editable wordmark. They contain no raster images, external references, strokes, gradients, filters, clipping paths, or masks. The mark is three editable closed paths: ring, center square, and tick. The rounded arc caps and tick ends are already outlined. The area between the ring and center square, the upper-right gap, and the outer corners are transparent.

`brand.json` is the canonical palette, path geometry, type choice, and composition recipe. Geometric measurements describe the paths; when changing the design, update both the paths and those measurements. `generate.py` recreates the SVGs, preview PNGs, and `manifest.json` from that file and the bundled font. Direct SVG edits are independent variations and will be overwritten on rebuild.

## Relationship to the concept

`reference/concept.png` is the original generated Luma Dial proposal, included for provenance. The new mark follows its open circular ring, rounded square, diagonal amber tick, and palette using exact vector geometry. Subtle image-generation texture has been removed. Nunito Sans ExtraBold is an openly licensed interpretation of the generated lettering; the image did not specify a recoverable font.

The source SVGs take precedence over the concept image for exports. PNGs in `previews/` are inspection exports, tagged sRGB. The 320 × 180 banner preview checks the lettering at the existing launcher's nominal dimensions. Application resources, manifest wiring, hardware testing, and distribution-specific exports remain for the receiving agent.

## Rebuild

Python 3.10+ and the packages in `requirements.txt` are needed only for regeneration. CairoSVG also needs the native Cairo library available to Python. Existing SVGs require no Python dependencies to use.

```sh
cd output/branding/luma-dial-source
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python generate.py
```

The generator checks successful rasterization, transparent interior and corners, the opaque center square, exact off-white and amber color samples, and the small banner dimensions. `manifest.json` records artwork hashes and the font checksum.

## Licensing

The authored vector artwork, generator, and handoff use this project's MIT license, included as `LICENSE`. The bundled unmodified Nunito Sans font is separately licensed under SIL Open Font License 1.1; its full copyright and terms are included in `fonts/OFL.txt`. Its source is recorded in `brand.json`. Keep the font's license when redistributing it. Outlined lettering can be used as artwork without installing or shipping the font.
