#!/usr/bin/env python3
"""Generate HDR10 / Dolby Vision (Profile 8.1) / SDR calibration clips + manifest.json.

Correctness rules:
  * Pixel values are computed in the signal domain (PQ for HDR, BT.1886 gamma 2.4 for SDR)
    and written directly as 10-bit Y'CbCr code values (limited range). No scaling, no
    colour conversion by ffmpeg.
  * HDR10 MaxCLL / MaxFALL and Dolby Vision L1 (min/max/avg PQ of maxRGB) are measured
    from the actual frames (metadata test variants override the static tags on purpose).
  * Every output file is decoded again and compared with the intended code values.

Requires: numpy, pillow, ffmpeg (libx265), dovi_tool, MP4Box (gpac).
Usage: python3 tools/generate_patterns.py [out_dir] [--only id,id] [--no-1080]
       python3 tools/generate_patterns.py --verify-only   # check committed clips + manifest, no encoding
"""
import json
import math
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ============================================================== transfer functions
M1, M2 = 2610 / 16384, 2523 / 4096 * 128
C1, C2, C3 = 3424 / 4096, 2413 / 4096 * 32, 2392 / 4096 * 32


def pq_oetf(nits):
    """Absolute nits -> PQ signal 0..1 (SMPTE ST 2084 inverse EOTF)."""
    y = np.clip(np.asarray(nits, dtype=np.float64) / 10000.0, 0, 1) ** M1
    return ((C1 + C2 * y) / (1 + C3 * y)) ** M2


def pq_eotf(sig):
    """PQ signal -> absolute nits. Negative (below-black) signal -> 0."""
    p = np.clip(np.asarray(sig, dtype=np.float64), 0, 1) ** (1 / M2)
    return 10000.0 * (np.maximum(p - C1, 0) / (C2 - C3 * p)) ** (1 / M1)


def sig_of_code(code):
    """10-bit limited-range luma code -> normalized signal (64 -> 0, 940 -> 1)."""
    return (code - 64) / 876.0


def code_of_nits(nits):
    return int(round(64 + 876 * float(pq_oetf(nits))))


def nits_of_code(code):
    return float(pq_eotf(sig_of_code(code)))


SDR_GAMMA = 2.4  # BT.1886 with zero black: L = V^2.4


def sdr_sig_of_code8(code8):
    """8-bit SDR code -> signal (16 -> 0, 235 -> 1). Exact on the 10-bit grid (x4)."""
    return (code8 * 4 - 64) / 876.0


# ============================================================== colour science
def _xyz_matrix(prims, white=(0.3127, 0.3290)):
    def xyz(x, y):
        return np.array([x / y, 1.0, (1 - x - y) / y])
    p = np.stack([xyz(*c) for c in prims], axis=1)
    s = np.linalg.solve(p, xyz(*white))
    return p * s


PRIM_709 = [(0.640, 0.330), (0.300, 0.600), (0.150, 0.060)]
PRIM_P3 = [(0.680, 0.320), (0.265, 0.690), (0.150, 0.060)]
PRIM_2020 = [(0.708, 0.292), (0.170, 0.797), (0.131, 0.046)]
M709, MP3, M2020 = _xyz_matrix(PRIM_709), _xyz_matrix(PRIM_P3), _xyz_matrix(PRIM_2020)
P3_TO_2020 = np.linalg.solve(M2020, MP3)
R709_TO_2020 = np.linalg.solve(M2020, M709)
LUM_2020 = M2020[1]  # (0.2627, 0.6780, 0.0593)
LUM_709 = M709[1]  # (0.2126, 0.7152, 0.0722)

_LMS = np.array([[1688, 2146, 262], [683, 2951, 462], [99, 309, 3688]]) / 4096


def ictcp(rgb2020_nits):
    lms = pq_oetf(_LMS @ np.asarray(rgb2020_nits, dtype=np.float64))
    i = 0.5 * lms[0] + 0.5 * lms[1]
    ct = (6610 * lms[0] - 13613 * lms[1] + 7003 * lms[2]) / 4096
    cp = (17933 * lms[0] - 17390 * lms[1] - 543 * lms[2]) / 4096
    return np.array([i, ct, cp])


def delta_e_itp(a, b):
    """ITU-R BT.2124 colour difference. ~1 = just noticeable."""
    d = ictcp(a) - ictcp(b)
    return 720 * math.sqrt(d[0] ** 2 + (0.5 * d[1]) ** 2 + d[2] ** 2)


# ============================================================== formats
class Fmt:
    def __init__(self, name, hdr, kr, kb, tags):
        self.name, self.hdr, self.kr, self.kb, self.tags = name, hdr, kr, kb, tags


HDR10 = Fmt("hdr10", True, 0.2627, 0.0593, "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc")
SDR = Fmt("sdr", False, 0.2126, 0.0722, "colorprim=bt709:transfer=bt709:colormatrix=bt709")
# Dolby Vision 8.1 = the HDR10 encode + injected RPU, so it shares HDR10 rendering.

W4K, H4K = 3840, 2160


def neutral(sig):
    return np.array([sig, sig, sig], dtype=np.float64)


def hdr_gray(nits):
    return neutral(float(pq_oetf(nits)))


def code_gray(code10):
    return neutral(sig_of_code(code10))


def sdr_gray(code8):
    return neutral(sdr_sig_of_code8(code8))


def fmt_nits(n):
    if n < 0.01:
        return f"{n:.4f}"
    if n < 1:
        return f"{n:.3f}"
    if n < 10:
        return f"{n:.2f}"
    if n < 100:
        return f"{n:.1f}"
    return f"{n:.0f}"


# ============================================================== drawing
class Canvas:
    def __init__(self, w, h, bg):
        self.w, self.h = w, h
        self.s = h / 2160.0
        self.rgb = np.empty((h, w, 3), dtype=np.float32)
        self.rgb[:] = bg

    def px(self, v):
        return int(round(v * self.s))

    def rect(self, cx, cy, rw, rh, color):
        x0, y0 = int(round(cx - rw / 2)), int(round(cy - rh / 2))
        x0, y0 = max(x0, 0), max(y0, 0)
        self.rgb[y0:y0 + int(rh), x0:x0 + int(rw)] = color

    def mask(self, m, cx, cy, color):
        h, w = m.shape
        y0, x0 = int(cy - h // 2), int(cx - w // 2)
        region = self.rgb[y0:y0 + h, x0:x0 + w]
        region[m] = color

    def mask_at(self, m, x0, y0, color):
        """Paint mask with top-left at (x0, y0), clipped to the canvas on all sides."""
        h, w = m.shape
        cx0, cy0 = max(x0, 0), max(y0, 0)
        cx1, cy1 = min(x0 + w, self.w), min(y0 + h, self.h)
        if cx1 <= cx0 or cy1 <= cy0:
            return
        region = self.rgb[cy0:cy1, cx0:cx1]
        region[m[cy0 - y0:cy1 - y0, cx0 - x0:cx1 - x0]] = color


_logo_cache = {}


def logo_mask(size):
    """Original emblem: ring + eight rays + center dot. Binary (no anti-aliasing)."""
    size = int(size)
    if size in _logo_cache:
        return _logo_cache[size]
    img = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(img)
    c = size / 2
    r_out, r_in, r_dot = size * 0.30, size * 0.22, size * 0.11
    d.ellipse([c - r_out, c - r_out, c + r_out, c + r_out], fill=255)
    d.ellipse([c - r_in, c - r_in, c + r_in, c + r_in], fill=0)
    d.ellipse([c - r_dot, c - r_dot, c + r_dot, c + r_dot], fill=255)
    for k in range(8):
        a = k * math.pi / 4
        p1 = (c + math.cos(a) * size * 0.36, c + math.sin(a) * size * 0.36)
        p2 = (c + math.cos(a) * size * 0.48, c + math.sin(a) * size * 0.48)
        d.line([p1, p2], fill=255, width=max(1, int(size * 0.07)))
    m = np.array(img) > 127
    _logo_cache[size] = m
    return m


def text_mask(text, px):
    font = ImageFont.load_default(size=max(8, int(px)))
    l, t, r, b = font.getbbox(text)
    img = Image.new("L", (r - l + 4, b - t + 4), 0)
    ImageDraw.Draw(img).text((2 - l, 2 - t), text, font=font, fill=255)
    return np.array(img) > 127


def row_x(c, n):
    return [int(round(c.w * (i + 1) / (n + 1))) for i in range(n)]


def logos_on_black(c, levels, label_color, logo=460):
    """Black-level style: logos directly on the background, numbered 1..n."""
    cy = int(0.44 * c.h)
    for i, (x, col) in enumerate(zip(row_x(c, len(levels)), levels)):
        c.mask(logo_mask(c.px(logo)), x, cy, col)
        c.mask(text_mask(str(i + 1), c.px(130)), x, cy + c.px(340), label_color)


def logos_in_patches(c, patch_colors, logo_colors, label_color, patch=500, logo=340):
    """Highlight / colour style: each logo inside its own square patch."""
    cy = int(0.44 * c.h)
    n = len(logo_colors)
    for i, x in enumerate(row_x(c, n)):
        pc = patch_colors[i] if isinstance(patch_colors, list) else patch_colors
        c.rect(x, cy, c.px(patch), c.px(patch), pc)
        c.mask(logo_mask(c.px(logo)), x, cy, logo_colors[i])
        c.mask(text_mask(str(i + 1), c.px(130)), x, cy + c.px(patch / 2 + 130), label_color)


def step_number(c, k, color):
    """Small step number bottom-right so the user can cross-check the app's readout."""
    m = text_mask(str(k + 1), c.px(60))
    c.mask_at(m, c.w - m.shape[1] - c.px(60), c.h - m.shape[0] - c.px(50), color)


# ============================================================== pattern families
FAMILIES = {}


def family(fid, formats, kind="static", crf=10, fps="24", frames=240, mdl=1000):
    def deco(fn):
        FAMILIES[fid] = dict(id=fid, formats=formats, kind=kind, crf=crf, fps=fps,
                             frames=frames, mdl=mdl, build=fn)
        return fn
    return deco


BTB_CODE10 = 56  # "blacker than black" (8-bit 14)


# --- 1 · black level ------------------------------------------------------------
@family("black", ["hdr10", "sdr"])
def build_black(fmt, w, h):
    if fmt.hdr:
        codes = [BTB_CODE10, code_of_nits(0.01), code_of_nits(0.05), code_of_nits(0.25)]
        levels = [code_gray(x) for x in codes]
        values = [None] + [nits_of_code(x) for x in codes[1:]]
        bg, label = code_gray(64), hdr_gray(2)
    else:
        codes8 = [14, 20, 25, 32]
        levels = [sdr_gray(x) for x in codes8]
        values = [x - 16 for x in codes8]
        bg, label = sdr_gray(16), sdr_gray(60)
    c = Canvas(w, h, bg)
    logos_on_black(c, levels, label)
    return [c], dict(logos=values)


# --- 2 · highlights -------------------------------------------------------------
def _highlight(fmt, w, h, patch_nits, logo_nits):
    patch_code = code_of_nits(patch_nits)
    codes = [code_of_nits(n) for n in logo_nits]
    c = Canvas(w, h, code_gray(64))
    logos_in_patches(c, code_gray(patch_code), [code_gray(x) for x in codes], hdr_gray(100))
    return [c], dict(patch=nits_of_code(patch_code), logos=[nits_of_code(x) for x in codes])


@family("highlight1000", ["hdr10"], mdl=1000)
def build_h1000(fmt, w, h):
    return _highlight(fmt, w, h, 1000, [400, 600, 800, 900])


@family("highlight4000", ["hdr10"], mdl=4000)
def build_h4000(fmt, w, h):
    return _highlight(fmt, w, h, 4000, [1000, 2000, 3000, 3600])


@family("white", ["sdr"])
def build_white(fmt, w, h):
    codes8 = [200, 215, 225, 230]
    c = Canvas(w, h, sdr_gray(16))
    logos_in_patches(c, sdr_gray(235), [sdr_gray(x) for x in codes8], sdr_gray(128))
    return [c], dict(patch=235, logos=codes8)


# --- shadow finder (ladder) -----------------------------------------------------
@family("shadow", ["hdr10", "sdr"], kind="frames")
def build_shadow(fmt, w, h):
    frames, steps = [], []
    if fmt.hdr:
        wanted = [0.0005, 0.001, 0.002, 0.003, 0.005, 0.0075, 0.01, 0.015, 0.02, 0.03,
                  0.05, 0.075, 0.1, 0.15, 0.2, 0.3, 0.5, 1.0]
        codes = sorted({code_of_nits(n) for n in wanted})
        levels = [(code_gray(x), nits_of_code(x)) for x in codes]
        bg, num = code_gray(64), hdr_gray(1)
    else:
        levels = [(sdr_gray(16 + k), k) for k in range(1, 25)]
        bg, num = sdr_gray(16), sdr_gray(40)
    for k, (col, value) in enumerate(levels):
        c = Canvas(w, h, bg)
        c.mask(logo_mask(c.px(760)), w // 2, h // 2, col)
        step_number(c, k, num)
        frames.append(c)
        steps.append(value)
    return frames, dict(steps=steps)


# --- peak finder (ladder, HDR10 only) -------------------------------------------
@family("peak", ["hdr10"], kind="frames", mdl=10000)
def build_peak(fmt, w, h):
    wanted = [400, 500, 600, 700, 800, 900, 1000, 1100, 1200, 1300, 1400, 1500, 1600, 1800,
              2000, 2200, 2500, 3000, 3500, 4000, 5000, 6000, 8000]
    codes = sorted({code_of_nits(n) for n in wanted})
    side = int(round(math.sqrt(0.10 * w * h)))  # 10 % window
    frames, steps = [], []
    for k, x in enumerate(codes):
        c = Canvas(w, h, code_gray(64))
        c.rect(w // 2, h // 2, side, side, code_gray(940))  # 10,000 nits
        c.mask(logo_mask(int(side * 0.7)), w // 2, h // 2, code_gray(x))
        step_number(c, k, hdr_gray(20))
        frames.append(c)
        steps.append(nits_of_code(x))
    return frames, dict(steps=steps, patch=nits_of_code(940), window=10)


# --- window size / auto-dimming (ladder) ----------------------------------------
@family("window", ["hdr10"], kind="frames", mdl=1000)
def build_window(fmt, w, h):
    sizes = [1, 2, 5, 10, 25, 50, 100]
    col = code_gray(code_of_nits(1000))
    frames = []
    for p in sizes:
        c = Canvas(w, h, code_gray(64))
        f = math.sqrt(p / 100)
        c.rect(w // 2, h // 2, int(round(w * f)), int(round(h * f)), col)
        frames.append(c)
    return frames, dict(steps=sizes, patch=nits_of_code(code_of_nits(1000)))


# --- colour clipping (ladder over 6 colours) -------------------------------------
COLOURS = [("Red", (1, 0, 0)), ("Green", (0, 1, 0)), ("Blue", (0, 0, 1)),
           ("Cyan", (0, 1, 1)), ("Magenta", (1, 0, 1)), ("Yellow", (1, 1, 0))]
DE_TARGETS = [24, 12, 6, 3]  # logo-vs-square difference in ΔE_ITP (1 ≈ just noticeable)
HDR_COLOUR_WHITE = 300  # nits of the (virtual) white the colour squares are scaled to


def _desat_series(prim_lin, lum, to_2020_nits):
    """Square = primary; logos = same luminance, less saturated, at fixed ΔE_ITP steps."""
    y = float(lum @ prim_lin)
    gray = np.array([y, y, y])

    def col(s):
        return (1 - s) * gray + s * prim_lin

    ref = to_2020_nits(prim_lin)
    out = []
    for target in DE_TARGETS:
        lo, hi = 0.0, 1.0  # ΔE decreases as s -> 1
        for _ in range(60):
            mid = (lo + hi) / 2
            if delta_e_itp(to_2020_nits(col(mid)), ref) > target:
                lo = mid
            else:
                hi = mid
        s = (lo + hi) / 2
        out.append((col(s), s, delta_e_itp(to_2020_nits(col(s)), ref)))
    return out


@family("color", ["hdr10", "sdr"], kind="frames", mdl=1000)
def build_color(fmt, w, h):
    frames, steps, details = [], [], []
    for k, (name, unit) in enumerate(COLOURS):
        if fmt.hdr:
            prim = P3_TO_2020 @ np.array(unit, dtype=np.float64)
            prim = np.clip(prim, 0, None)  # P3 red has a -0.0012 blue term in BT.2020; clamp
            prim_n = prim * HDR_COLOUR_WHITE
            series = _desat_series(prim_n, LUM_2020, lambda v: v)

            def enc(v):
                return pq_oetf(v)
            bg, label = code_gray(64), hdr_gray(60)
        else:
            prim_n = np.array(unit, dtype=np.float64)
            series = _desat_series(prim_n, LUM_709, lambda v: R709_TO_2020 @ (v * 100))

            def enc(v):
                return np.clip(v, 0, 1) ** (1 / SDR_GAMMA)
            bg, label = sdr_gray(16), sdr_gray(110)
        c = Canvas(w, h, bg)
        logos_in_patches(c, enc(prim_n), [enc(v) for v, _, _ in series], label)
        step_number(c, k, label)
        frames.append(c)
        steps.append(name)
        details.append({"color": name, "saturation": [round(s, 4) for _, s, _ in series],
                        "delta_e_itp": [round(d, 2) for _, _, d in series]})
    return frames, dict(steps=steps, details=details, delta_e_itp=DE_TARGETS)


# --- gradient / banding ---------------------------------------------------------
@family("gradient", ["hdr10", "sdr"], crf=4)
def build_gradient(fmt, w, h):
    c = Canvas(w, h, code_gray(64) if fmt.hdr else sdr_gray(16))
    t = np.linspace(0, 1, w)[None, :, None]
    rows, gap = 0.13 * h, 0.05 * h
    y = 0.075 * h
    if fmt.hdr:
        top = float(pq_oetf(1000))
        dark = float(pq_oetf(10))
        gray = lambda tt, mx: np.repeat(tt * mx, 3, axis=2)

        def color_ramp(unit):
            prim = np.clip(P3_TO_2020 @ np.array(unit, float), 0, None)
            # The P3 primary as it would be inside a white going 0 -> 1000 nits (PQ-uniform steps).
            white = pq_eotf(t[..., 0] * top)  # (1, w) nits
            return pq_oetf(white[..., None] * prim)
        bands = [gray(t, top), gray(t, dark)] + [color_ramp(u) for u in [(1, 0, 0), (0, 1, 0), (0, 0, 1)]]
    else:
        gray = lambda tt, mx: np.repeat(tt * mx, 3, axis=2)
        dark = sdr_sig_of_code8(64)

        def color_ramp(unit):
            return t * np.array(unit, float)[None, None, :]
        bands = [gray(t, 1.0), gray(t, dark)] + [color_ramp(u) for u in [(1, 0, 0), (0, 1, 0), (0, 0, 1)]]
    for band in bands:
        y0 = int(round(y))
        c.rgb[y0:y0 + int(rows)] = band.astype(np.float32)
        y += rows + gap
    return [c], {}


# --- overscan & sharpness -------------------------------------------------------
@family("sharpness", ["hdr10", "sdr"], crf=2)
def build_sharpness(fmt, w, h):
    if fmt.hdr:
        black, white, gray = code_gray(64), hdr_gray(203), hdr_gray(40)  # 203 = BT.2408 ref white
    else:
        black, white, gray = sdr_gray(16), sdr_gray(235), sdr_gray(128)
    c = Canvas(w, h, black)
    # 1-pixel frames: at the very edge, 1 % and 2.5 % inset
    for inset, label, lx in [(0, None, 0), (0.01, "1%", 0.30), (0.025, "2.5%", 0.36)]:
        x0, y0 = int(round(w * inset)), int(round(h * inset))
        x1, y1 = w - 1 - x0, h - 1 - y0
        c.rgb[y0, x0:x1 + 1] = white
        c.rgb[y1, x0:x1 + 1] = white
        c.rgb[y0:y1 + 1, x0] = white
        c.rgb[y0:y1 + 1, x1] = white
        if label:  # placed just inside its own frame, staggered so labels never overlap
            m = text_mask(label, c.px(44))
            c.mask_at(m, int(w * lx), y0 + c.px(10), white)
    # edge arrows pointing at the outermost frame (middle of each side)
    a = c.px(40)
    for i in range(a):  # tip nearest the edge, so arrows point outward at the outer frame
        c.rgb[c.px(60) + i, w // 2 - i // 2: w // 2 + i // 2 + 1] = white
        c.rgb[h - 1 - c.px(60) - i, w // 2 - i // 2: w // 2 + i // 2 + 1] = white
        c.rgb[h // 2 - i // 2: h // 2 + i // 2 + 1, c.px(60) + i] = white
        c.rgb[h // 2 - i // 2: h // 2 + i // 2 + 1, w - 1 - c.px(60) - i] = white
    # centre: grey field with black + white lines of 1, 2, 4 px, and text
    gw, gh = int(w * 0.46), int(h * 0.42)
    gx0, gy0 = (w - gw) // 2, (h - gh) // 2
    c.rgb[gy0:gy0 + gh, gx0:gx0 + gw] = gray
    xs = gx0 + c.px(120)
    for i, lw in enumerate([1, 2, 4]):
        for j, col in enumerate([black, white]):
            x = xs + (i * 2 + j) * c.px(110)
            c.rgb[gy0 + c.px(80):gy0 + gh - c.px(80), x:x + lw] = col
    ys = gy0 + c.px(120)
    xr0 = gx0 + gw // 2 + c.px(40)
    for i, lw in enumerate([1, 2, 4]):
        for j, col in enumerate([black, white]):
            yy = ys + (i * 2 + j) * c.px(100)
            c.rgb[yy:yy + lw, xr0:gx0 + gw - c.px(80)] = col
    m = text_mask("Sharpness 123", c.px(90))
    c.mask_at(m, xr0, gy0 + gh - c.px(170), black)
    # resolution blocks: 1-px stripes (vertical left, horizontal right)
    bs = c.px(300)
    by0 = (h - bs) // 2
    lx0, rx0 = int(w * 0.07), w - int(w * 0.07) - bs
    blk = c.rgb[by0:by0 + bs, lx0:lx0 + bs]
    blk[:, 0::2] = white
    blk[:, 1::2] = black
    blk = c.rgb[by0:by0 + bs, rx0:rx0 + bs]
    blk[0::2, :] = white
    blk[1::2, :] = black
    return [c], {}


# --- motion (24p pan) -----------------------------------------------------------
@family("motion", ["hdr10", "sdr"], kind="motion", crf=14, fps="24000/1001", frames=240)
def build_motion(fmt, w, h):
    if fmt.hdr:
        bg, white = hdr_gray(5), hdr_gray(203)
    else:
        bg, white = sdr_gray(32), sdr_gray(235)
    s = h / 2160
    speed = int(round(20 * s))  # px per frame; 240 frames * 20 = 4800 px per loop
    period = int(round(160 * s))
    bar = int(round(40 * s))
    loop = 240 * speed
    logo = logo_mask(int(round(600 * s)))
    lw = logo.shape[1]
    txt = text_mask("24p", int(round(110 * s)))
    frames = []
    for k in range(240):
        c = Canvas(w, h, bg)
        off = (k * speed) % period
        for band_y in (int(0.12 * h), int(0.76 * h)):
            for x in range(-period + off, w, period):
                x0, x1 = max(x, 0), min(x + bar, w)
                if x1 > x0:
                    c.rgb[band_y:band_y + int(0.12 * h), x0:x1] = white
        # logo crosses once per 240-frame loop (seamless): x from -lw to loop - lw. loop (4800 px at 4K) is longer
        # than w + lw, so the logo is off screen for the last ~18 frames; the jump back happens off screen.
        lx = (k * speed) % loop - lw
        cy = h // 2
        c.mask_at(logo, lx, cy - logo.shape[0] // 2, white)
        c.mask_at(txt, lx + lw // 2 - txt.shape[1] // 2, cy + logo.shape[0] // 2 + int(30 * s), white)
        frames.append(c)
    return frames, dict(speed_px_per_frame=speed, loop_frames=240)


# --- metadata response (HDR10 only, 3 tagged variants) ---------------------------
def _metadata(fmt, w, h):
    values = [100, 300, 600, 1000]
    codes = [code_of_nits(n) for n in values]
    c = Canvas(w, h, code_gray(64))
    cy = int(0.44 * h)
    for x, code in zip(row_x(c, 4), codes):
        c.rect(x, cy, c.px(500), c.px(500), code_gray(code))
        c.mask(text_mask(fmt_nits(nits_of_code(code)), c.px(110)), x, cy + c.px(370), hdr_gray(50))
    return [c], dict(patches=[nits_of_code(x) for x in codes])


for _tag in (1000, 4000, 10000):
    FAMILIES[f"metadata{_tag}"] = dict(id=f"metadata{_tag}", formats=["hdr10"], kind="static", crf=10,
                                       fps="24", frames=240, mdl=_tag, override_cll=(_tag, 400),
                                       build=_metadata)


# ============================================================== encoding
def to_ycc(rgb, fmt):
    """R'G'B' signal (H,W,3) -> (Y, Cb, Cr) uint16 code planes, 4:2:0."""
    kr, kb = fmt.kr, fmt.kb
    kg = 1 - kr - kb
    r, g, b = rgb[..., 0].astype(np.float64), rgb[..., 1].astype(np.float64), rgb[..., 2].astype(np.float64)
    y = kr * r + kg * g + kb * b
    cb = (b - y) / (2 * (1 - kb))
    cr = (r - y) / (2 * (1 - kr))
    yc = np.clip(np.rint(64 + 876 * y), 4, 1019).astype(np.uint16)

    def sub(p):
        p = (p[0::2, 0::2] + p[1::2, 0::2] + p[0::2, 1::2] + p[1::2, 1::2]) / 4
        return np.clip(np.rint(512 + 896 * p), 4, 1019).astype(np.uint16)
    return yc, sub(cb), sub(cr)


def ycc_bytes(planes):
    return b"".join(p.astype("<u2").tobytes() for p in planes)


def frame_stats(rgb, fmt):
    """maxRGB stats. HDR: nits + 12-bit PQ for Dolby Vision L1."""
    mx = np.max(rgb, axis=2).astype(np.float64)
    if not fmt.hdr:
        return None
    sig = np.clip(mx, 0, 1)
    nits = pq_eotf(sig)
    return dict(max_nits=float(nits.max()), avg_nits=float(nits.mean()),
                min_pq=int(round(4095 * sig.min())), max_pq=int(round(4095 * sig.max())),
                avg_pq=int(round(4095 * sig.mean())))


def run(cmd, **kw):
    subprocess.run(cmd, check=True, **kw)


def x265_params(fam, fmt, stats, ladder):
    # lossless=1: mathematically lossless (cu_transquant_bypass, allowed in Main10). Lossy coding
    # rings around logo edges and changes the very levels the tests depend on.
    p = [fmt.tags, "range=limited", "repeat-headers=1", "aud=1", "log-level=error", "lossless=1"]
    if ladder:
        # keyint=2, not 1: x265 labels all-intra streams "Main 10 Intra" (a RExt profile) that
        # TV decoders may reject. Exact seeking then decodes at most 2 frames per step.
        p += ["keyint=2", "min-keyint=2", "scenecut=0", "bframes=0"]
    else:
        p += ["keyint=48", "min-keyint=48", "scenecut=0"]
    if fmt.hdr:
        cll, fall = fam.get("override_cll") or (max(1, math.ceil(max(s["max_nits"] for s in stats))),
                                                max(1, math.ceil(max(s["avg_nits"] for s in stats))))
        p += [f"master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L({fam['mdl'] * 10000},1)",
              f"max-cll={cll},{fall}", "hdr10=1", "hdr10-opt=0"]
        return ":".join(p), (cll, fall)
    return ":".join(p), None


def encode(fam, fmt, w, h, hevc_out):
    frames, info = fam["build"](fmt, w, h)
    stats = [frame_stats(c.rgb, fmt) for c in frames]
    ladder = fam["kind"] == "frames"
    params, cll = x265_params(fam, fmt, stats, ladder)
    n_out = len(frames) if fam["kind"] != "static" else fam["frames"]
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
           "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-s", f"{w}x{h}", "-r", fam["fps"]]
    if fam["kind"] == "static":
        cmd += ["-stream_loop", str(n_out - 1)]
    cmd += ["-i", "pipe:0" if fam["kind"] != "static" else hevc_out + ".yuv",
            "-frames:v", str(n_out), "-c:v", "libx265", "-preset", "slow",
            "-profile:v", "main10", "-pix_fmt", "yuv420p10le", "-x265-params", params, "-f", "hevc", hevc_out]
    intended = [to_ycc(c.rgb, fmt) for c in frames]
    if fam["kind"] == "static":
        with open(hevc_out + ".yuv", "wb") as f:
            f.write(ycc_bytes(intended[0]))
        run(cmd)
        os.remove(hevc_out + ".yuv")
    else:
        p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        for planes in intended:
            p.stdin.write(ycc_bytes(planes))
        p.stdin.close()
        if p.wait() != 0:
            raise RuntimeError("ffmpeg failed")
    if fam["kind"] == "static":
        stats = stats * n_out
    return intended, stats, info, cll, n_out


def mux_plain(hevc, fps, out):
    # MP4Box: exact constant timestamps (ffmpeg's raw-HEVC muxing mangles B-frame timing).
    if os.path.exists(out):
        os.remove(out)
    run(["MP4Box", "-quiet", "-add", f"{hevc}:fps={fps}", "-brand", "mp42isom", "-new", out],
        stdout=subprocess.DEVNULL)


def make_dv(fam, hevc, stats, cll, n_out, out, tmp):
    fps = fam["fps"]
    shots, start = [], 0
    l1 = [dict(min_pq=s["min_pq"], max_pq=s["max_pq"], avg_pq=s["avg_pq"]) for s in stats]
    for i in range(1, n_out + 1):
        if i == n_out or l1[i] != l1[start]:
            shots.append({"start": start, "duration": i - start,
                          "metadata_blocks": [{"Level1": l1[start]}]})
            start = i
    cfg = {"cm_version": "V40", "profile": "8.1", "length": n_out,
           "level6": {"max_display_mastering_luminance": fam["mdl"], "min_display_mastering_luminance": 1,
                      "max_content_light_level": cll[0], "max_frame_average_light_level": cll[1]},
           "shots": shots}
    base = os.path.join(tmp, os.path.basename(out))
    with open(base + ".json", "w") as f:
        json.dump(cfg, f)
    run(["dovi_tool", "generate", "-j", base + ".json", "-o", base + ".rpu"], stdout=subprocess.DEVNULL)
    run(["dovi_tool", "inject-rpu", "-i", hevc, "--rpu-in", base + ".rpu", "-o", base + ".dv.hevc"],
        stdout=subprocess.DEVNULL)
    if os.path.exists(out):
        os.remove(out)
    run(["MP4Box", "-quiet", "-add", f"{base}.dv.hevc:dvp=8.1:fps={fps}", "-brand", "mp42isom",
         "-ab", "dby1", "-new", out], stdout=subprocess.DEVNULL)
    return cfg


# ============================================================== verification
def verify(path, w, h, intended, kind, n_out):
    """Decode the file and compare with the intended code values."""
    frame_bytes = w * h * 3  # 10-bit 4:2:0 = 1.5 samples * 2 bytes
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-"],
                         check=True, capture_output=True).stdout
    got_n = len(raw) // frame_bytes
    probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                       "stream=avg_frame_rate,nb_frames,color_transfer,color_primaries,color_space,color_range",
                                       "-of", "json", path], check=True, capture_output=True).stdout)["streams"][0]
    profile = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=profile",
                              "-of", "csv=p=0", path], check=True, capture_output=True, text=True).stdout.strip().strip(",")
    if profile != "Main 10":
        raise AssertionError(f"{path}: HEVC profile is '{profile}', must be 'Main 10' for TV decoders")
    if int(probe["nb_frames"]) != n_out or probe["color_range"] != "tv":
        raise AssertionError(f"{path}: bad container info {probe}")
    if got_n != n_out:
        raise AssertionError(f"{path}: decoded {got_n} frames, expected {n_out}")
    if kind == "static":
        check = [(0, 0), (n_out - 1, 0)]
    elif kind == "frames":
        check = [(i, i) for i in range(n_out)]
    else:
        check = [(i, i) for i in (0, 60, 119, 239)]
    worst_luma = worst_chroma = 0
    exact = []
    for fi, ii in check:
        buf = np.frombuffer(raw[fi * frame_bytes:(fi + 1) * frame_bytes], dtype="<u2")
        y = buf[:w * h].reshape(h, w)
        cb = buf[w * h:w * h + w * h // 4].reshape(h // 2, w // 2)
        cr = buf[w * h + w * h // 4:].reshape(h // 2, w // 2)
        iy, icb, icr = intended[ii]
        dy = np.abs(y.astype(int) - iy.astype(int))
        dc = np.maximum(np.abs(cb.astype(int) - icb.astype(int)), np.abs(cr.astype(int) - icr.astype(int)))
        worst_luma, worst_chroma = max(worst_luma, int(dy.max())), max(worst_chroma, int(dc.max()))
        exact.append(float((dy == 0).mean()))
    return worst_luma, worst_chroma, min(exact)


# ============================================================== verify-only
def _json_norm(x):
    """Round floats so the comparison ignores JSON float formatting."""
    if isinstance(x, float):
        return round(x, 6)
    if isinstance(x, list):
        return [_json_norm(v) for v in x]
    if isinstance(x, dict):
        return {k: _json_norm(v) for k, v in x.items()}
    return x


def verify_only(out_dir):
    """Decode every committed clip and compare with freshly rendered frames; check manifest levels.

    Needs only ffmpeg/ffprobe, numpy and pillow (no x265 / dovi_tool / MP4Box), so it can run in CI.
    """
    manifest = json.load(open(os.path.join(out_dir, "manifest.json")))
    bad, n = [], 0
    for fid, fam in FAMILIES.items():
        entry = manifest["families"].get(fid)
        if entry is None:
            bad.append(f"manifest: missing family {fid}")
            continue
        for fmt_name in fam["formats"]:
            fmt = HDR10 if fmt_name == "hdr10" else SDR
            for w, h, suffix in [(W4K, H4K, ""), (1920, 1080, "_1080")]:
                frames, info = fam["build"](fmt, w, h)
                if suffix == "":
                    stored = {k: v for k, v in entry["info"].get(fmt_name, {}).items()
                              if k not in ("max_cll", "max_fall", "mdl")}
                    if _json_norm(stored) != _json_norm(json.loads(json.dumps(info))):
                        bad.append(f"manifest: {fid}/{fmt_name} levels differ from the generator")
                intended = [to_ycc(c.rgb, fmt) for c in frames]
                n_out = len(frames) if fam["kind"] != "static" else fam["frames"]
                outs = [fmt_name] + (["dv"] if fmt.hdr and fid != "peak" and not fid.startswith("metadata") else [])
                for o in outs:
                    path = os.path.join(out_dir, f"{fid}{suffix}_{o}.mp4")
                    n += 1
                    try:
                        wl, wc, _ = verify(path, w, h, intended, fam["kind"], n_out)
                        ok = wl == 0 and wc <= 1
                    except Exception as e:  # missing file, wrong profile, wrong frame count, ...
                        ok, wl, wc = False, str(e), "-"
                    print(f"{'OK ' if ok else 'BAD'} {os.path.basename(path):34s} luma={wl} chroma={wc}", flush=True)
                    if not ok:
                        bad.append(path)
    if bad:
        print("VERIFICATION FAILED:", *bad, sep="\n  ")
        sys.exit(1)
    print(f"All {n} committed clips and the manifest match the generator.")


# ============================================================== main
def main():
    args = sys.argv[1:]
    if "--verify-only" in args:
        args.remove("--verify-only")
        verify_only(args[0] if args else os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                      "..", "app", "src", "main", "assets", "patterns"))
        return
    only = None
    res_list = [(W4K, H4K, ""), (1920, 1080, "_1080")]
    if "--only" in args:
        i = args.index("--only")
        only = set(args[i + 1].split(","))
        del args[i:i + 2]
    if "--no-1080" in args:
        args.remove("--no-1080")
        res_list = res_list[:1]
    out_dir = args[0] if args else os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                "..", "app", "src", "main", "assets", "patterns")
    os.makedirs(out_dir, exist_ok=True)
    manifest_path = os.path.join(out_dir, "manifest.json")
    manifest = {"version": 2, "families": {}}
    if only and os.path.exists(manifest_path):
        manifest = json.load(open(manifest_path))
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        for fid, fam in FAMILIES.items():
            if only and fid not in only:
                continue
            entry = {"kind": fam["kind"], "fps": fam["fps"], "formats": [], "info": {}}
            for fmt_name in fam["formats"]:
                fmt = HDR10 if fmt_name == "hdr10" else SDR
                for w, h, suffix in res_list:
                    tag = f"{fid}{suffix}"
                    hevc = os.path.join(tmp, f"{tag}_{fmt_name}.hevc")
                    intended, stats, info, cll, n_out = encode(fam, fmt, w, h, hevc)
                    outs = [(os.path.join(out_dir, f"{tag}_{fmt_name}.mp4"), fmt_name)]
                    mux_plain(hevc, fam["fps"], outs[0][0])
                    if fmt.hdr and fid not in ("peak",) and not fid.startswith("metadata"):
                        dv_out = os.path.join(out_dir, f"{tag}_dv.mp4")
                        make_dv(fam, hevc, stats, cll, n_out, dv_out, tmp)
                        outs.append((dv_out, "dv"))
                    for path, fn in outs:
                        wl, wc, ex = verify(path, w, h, intended, fam["kind"], n_out)
                        ok = wl == 0 and wc <= 1
                        mark = "OK " if ok else "BAD"
                        extra = f" MaxCLL/FALL={cll}" if cll else ""
                        print(f"{mark} {os.path.basename(path):34s} {os.path.getsize(path) / 1e6:6.2f} MB "
                              f"luma maxerr={wl} chroma maxerr={wc} exact={ex * 100:.3f}%{extra}", flush=True)
                        if not ok:
                            failures.append(path)
                        if suffix == "" and fn not in entry["formats"]:
                            entry["formats"].append(fn)
                    if suffix == "":
                        entry["info"][fmt_name] = info
                        if cll:
                            entry["info"][fmt_name]["max_cll"], entry["info"][fmt_name]["max_fall"] = cll
                            entry["info"][fmt_name]["mdl"] = fam["mdl"]
            manifest["families"][fid] = entry
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=1)
    if failures:
        print("VERIFICATION FAILED:", *failures, sep="\n  ")
        sys.exit(1)
    print("All clips verified.")


if __name__ == "__main__":
    main()
