#!/usr/bin/env python3
"""Generate every Matrix Chat icon from one definition of the mark.

The mark is the Matrix "[m]" wordmark: a white `m` between accent-green brackets. It is drawn
from DejaVu Sans Bold outlines at high resolution and downsampled, so it stays crisp at every
density and depends on no font being present on the device.

Run from the repository root:

    python3 tools/chagai/make_icons.py

Writes (overwriting):
  appicon/element/src/main/res/mipmap-*/ic_launcher_foreground.webp   adaptive foreground
  appicon/element/src/main/res/mipmap-*/ic_launcher_monochrome.webp   themed icon
  appicon/element/src/main/res/mipmap-*/ic_launcher.webp              legacy square tile
  appicon/element/src/main/res/mipmap-*/ic_launcher_round.webp        legacy round tile
  appicon/element/src/main/ic_launcher-playstore.png                  512x512 store icon
  app/src/main/res/drawable-*/ic_splash_logo.webp                     Android 12+ splash icon
  fastlane/metadata/android/en-US/images/icon.png                     store listing icon
  fastlane/metadata/android/en-US/images/featureGraphic.png           store listing banner
  ../artifacts/matrix-chat-icon.png                                   the icon the download page uses

The adaptive-icon background colours live in
appicon/element/src/{release,debug,nightly}/res/drawable/ic_launcher_background.xml and are not
generated here, because they are one-line vector drawables.

Requires Pillow.
"""
from __future__ import annotations

import os
import pathlib

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[2]

U = 108                           # adaptive-icon design canvas, in dp
S = 16                            # supersampling factor
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

FG = (255, 255, 255, 255)         # the m
ACCENT = (13, 189, 139, 255)      # the brackets, and the splash mark
BG = (16, 20, 25, 255)            # #101419, matches ic_launcher_background.xml (release)

DENSITIES = (("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0))


def mark(size_units: float = 58.0) -> Image.Image:
    """The '[m]' wordmark on a transparent U x U canvas, at supersampled resolution."""
    im = Image.new("RGBA", (U * S, U * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    f = ImageFont.truetype(FONT, int(size_units * S))
    gap = 2.0 * S
    parts = [("[", ACCENT), ("m", FG), ("]", ACCENT)]
    boxes = [d.textbbox((0, 0), t, font=f) for t, _ in parts]
    widths = [b[2] - b[0] for b in boxes]
    total = sum(widths) + gap * 2
    top = min(b[1] for b in boxes)
    bottom = max(b[3] for b in boxes)
    x = (U * S - total) / 2
    y = (U * S - (bottom - top)) / 2 - top
    for (t, col), b, w in zip(parts, boxes, widths):
        d.text((x - b[0], y), t, font=f, fill=col)
        x += w + gap
    return im


def fit(safe_units: float, canvas_units: int = U) -> Image.Image:
    """The mark scaled so its ink fits a centred safe box of `safe_units` dp."""
    src = mark()
    ink = src.crop(src.getbbox())
    scale = (safe_units * S) / max(ink.width, ink.height)
    ink = ink.resize((max(1, round(ink.width * scale)), max(1, round(ink.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas_units * S, canvas_units * S), (0, 0, 0, 0))
    out.alpha_composite(ink, ((out.width - ink.width) // 2, (out.height - ink.height) // 2))
    return out


def recolour(im: Image.Image, colour) -> Image.Image:
    flat = Image.new("RGBA", im.size, colour)
    flat.putalpha(im.split()[3])
    return flat


def on_bg(im: Image.Image, bg=BG) -> Image.Image:
    base = Image.new("RGBA", im.size, bg)
    base.alpha_composite(im)
    return base


def save(im: Image.Image, path: pathlib.Path, size: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    out = im.resize((size, size), Image.LANCZOS)
    if path.suffix == ".png":
        out.convert("RGB").save(path, "PNG")
    else:
        out.save(path, "WEBP", lossless=True)


def main() -> None:
    os.chdir(ROOT)

    # --- launcher icons -------------------------------------------------------
    # The adaptive safe zone is the central 72dp of 108dp; 62dp leaves a little air.
    foreground = fit(62)
    monochrome = recolour(foreground, FG)
    # Legacy tiles are drawn as-is by the launcher, so the mark fills more of them.
    legacy = on_bg(fit(76))

    circle = Image.new("L", legacy.size, 0)
    ImageDraw.Draw(circle).ellipse([0, 0, legacy.size[0] - 1, legacy.size[1] - 1], fill=255)
    squircle = Image.new("L", legacy.size, 0)
    ImageDraw.Draw(squircle).rounded_rectangle(
        [0, 0, legacy.size[0] - 1, legacy.size[1] - 1], radius=round(legacy.size[0] * 0.18), fill=255
    )

    icons = pathlib.Path("appicon/element/src/main/res")
    for dpi, scale in DENSITIES:
        d = icons / f"mipmap-{dpi}"
        save(foreground, d / "ic_launcher_foreground.webp", round(108 * scale))
        save(monochrome, d / "ic_launcher_monochrome.webp", round(108 * scale))
        sq = legacy.copy(); sq.putalpha(squircle)
        save(sq, d / "ic_launcher.webp", round(48 * scale))
        rd = legacy.copy(); rd.putalpha(circle)
        save(rd, d / "ic_launcher_round.webp", round(48 * scale))

    save(legacy, pathlib.Path("appicon/element/src/main/ic_launcher-playstore.png"), 512)

    # --- splash icon ----------------------------------------------------------
    # Android 12+ uses a 288dp canvas whose central 2/3 is guaranteed visible. Drawn entirely
    # in accent green so it reads on both the light and dark splash background.
    green = recolour(mark(), ACCENT)
    green_ink = green.crop(green.getbbox())
    for dpi, scale in DENSITIES:
        px = round(288 * scale)
        inner = round(168 * scale)
        f = inner / max(green_ink.width, green_ink.height)
        g = green_ink.resize((round(green_ink.width * f), round(green_ink.height * f)), Image.LANCZOS)
        out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        out.alpha_composite(g, ((px - g.width) // 2, (px - g.height) // 2))
        p = pathlib.Path(f"app/src/main/res/drawable-{dpi}/ic_splash_logo.webp")
        p.parent.mkdir(parents=True, exist_ok=True)
        out.save(p, "WEBP", lossless=True)

    # --- store listing + download page ---------------------------------------
    save(legacy, pathlib.Path("fastlane/metadata/android/en-US/images/icon.png"), 512)
    artifacts = ROOT.parent / "artifacts"
    if artifacts.is_dir():
        save(legacy, artifacts / "matrix-chat-icon.png", 512)

    banner = Image.new("RGBA", (2048, 1000), BG)
    ink = mark().crop(mark().getbbox())
    f = 520 / max(ink.width, ink.height)
    g = ink.resize((round(ink.width * f), round(ink.height * f)), Image.LANCZOS)
    banner.alpha_composite(g, ((2048 - g.width) // 2, (1000 - g.height) // 2 - 60))
    d = ImageDraw.Draw(banner)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 84)
    b = d.textbbox((0, 0), "Matrix Chat", font=font)
    d.text(((2048 - (b[2] - b[0])) / 2 - b[0], 700), "Matrix Chat", font=font, fill=FG)
    banner.resize((1024, 500), Image.LANCZOS).convert("RGB").save(
        "fastlane/metadata/android/en-US/images/featureGraphic.png"
    )

    print("icons regenerated")


if __name__ == "__main__":
    main()
