#!/usr/bin/env python3
"""Generate legacy launcher PNGs and a README icon from the same shield design."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
DOCS = ROOT / "docs"

SHIELD = [
    (12.0, 1.8), (3.3, 5.4), (3.3, 11.0), (4.2, 14.2), (6.5, 17.8),
    (9.0, 20.0), (12.0, 21.7), (15.0, 20.0), (17.5, 17.8),
    (19.8, 14.2), (20.7, 11.0), (20.7, 5.4),
]
BOLT = [
    (13.4, 6.4), (7.7, 13.4), (11.2, 13.4), (10.7, 17.8),
    (16.3, 10.8), (12.9, 10.8),
]
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def gradient(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pixels = image.load()
    start = (59, 232, 164)
    end = (43, 184, 255)
    for y in range(size):
        for x in range(size):
            t = (x + y) / max(1, (size - 1) * 2)
            pixels[x, y] = (
                round(start[0] + (end[0] - start[0]) * t),
                round(start[1] + (end[1] - start[1]) * t),
                round(start[2] + (end[2] - start[2]) * t),
                255,
            )
    return image


def render(size: int, rounded: bool) -> Image.Image:
    scale = 4
    canvas = size * scale
    image = gradient(canvas)
    mask = Image.new("L", (canvas, canvas), 0)
    draw_mask = ImageDraw.Draw(mask)
    if rounded:
        draw_mask.ellipse((0, 0, canvas - 1, canvas - 1), fill=255)
    else:
        draw_mask.rounded_rectangle(
            (0, 0, canvas - 1, canvas - 1), radius=round(canvas * 0.22), fill=255)
    image.putalpha(mask)

    draw = ImageDraw.Draw(image)
    unit = canvas * 0.72 / 24.0
    offset = (canvas - unit * 24) / 2

    def points(shape):
        return [(offset + x * unit, offset + y * unit) for x, y in shape]

    draw.polygon(points(SHIELD), fill=(255, 255, 255, 255))
    draw.polygon(points(BOLT), fill=(10, 16, 28, 255))
    return image.resize((size, size), Image.LANCZOS)


def main() -> None:
    for density, size in SIZES.items():
        directory = RES / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        render(size, rounded=False).save(directory / "ic_launcher.png")
        render(size, rounded=True).save(directory / "ic_launcher_round.png")
    DOCS.mkdir(exist_ok=True)
    render(512, rounded=False).save(DOCS / "icon.png")
    print("Icons written.")


if __name__ == "__main__":
    main()
