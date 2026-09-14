"""Render the shared quota-cell mark. Development-only dependency: Pillow."""

import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BACKGROUND = "#191715"
TRACK = "#493B32"
TERRA = "#E28E6F"
CREAM = "#F4E7D6"


def render(size: int, scale: float = 1.0) -> Image.Image:
    """Supersample round ends, then reduce to the required launcher size."""
    factor = size * 4 / 108
    image = Image.new("RGB", (size * 4, size * 4), BACKGROUND)
    draw = ImageDraw.Draw(image)

    def point(x: float, y: float) -> tuple[float, float]:
        return ((54 + (x - 54) * scale) * factor, (54 + (y - 54) * scale) * factor)

    def pill(x: float, top: float, bottom: float, color: str) -> None:
        draw.rounded_rectangle((*point(x, top), *point(x + 11, bottom)),
                               radius=5.5 * factor * scale, fill=color)

    for x in (32, 49, 66):
        pill(x, 43, 78, TRACK)
    pill(32, 51, 78, TERRA)
    pill(49, 43, 78, CREAM)
    pill(66, 62, 78, TERRA)

    points = []
    for i in range(101):
        t = i / 100
        x = (1 - t) ** 2 * 33 + 2 * (1 - t) * t * 54 + t * t * 75
        y = (1 - t) ** 2 * 34 + 2 * (1 - t) * t * 17 + t * t * 34
        points.append(point(x, y))
    width = round(4.5 * factor * scale)
    draw.line(points, fill=TERRA, width=width)
    draw.line([point(75, 26), point(75, 34), point(67, 34)], fill=TERRA, width=width)
    radius = width / 2
    for px, py in points + [point(75, 26), point(75, 34), point(67, 34)]:
        draw.ellipse((px - radius, py - radius, px + radius, py + radius), fill=TERRA)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def main() -> None:
    catalog = ROOT / "ios/UsageLimits/Sources/Assets.xcassets"
    iconset = catalog / "AppIcon.appiconset"
    iconset.mkdir(parents=True, exist_ok=True)
    info = {"version": 1, "author": "Usage Limits"}
    (catalog / "Contents.json").write_text(json.dumps({"info": info}, indent=2) + "\n")
    entries = []
    sizes_by_idiom = [
        ("iphone", [(20, 2), (20, 3), (29, 2), (29, 3), (40, 2), (40, 3), (60, 2), (60, 3)]),
        ("ipad", [(20, 1), (20, 2), (29, 1), (29, 2), (40, 1), (40, 2), (76, 1), (76, 2), (83.5, 2)]),
        ("ios-marketing", [(1024, 1)]),
    ]
    for idiom, sizes in sizes_by_idiom:
        for points, density in sizes:
            pixels = int(points * density)
            filename = f"icon-{pixels}.png"
            render(pixels, scale=1.28).save(iconset / filename, optimize=True)
            entries.append({"idiom": idiom, "size": f"{points}x{points}",
                            "scale": f"{density}x", "filename": filename})
    (iconset / "Contents.json").write_text(json.dumps({"images": entries, "info": info}, indent=2) + "\n")


if __name__ == "__main__":
    main()
