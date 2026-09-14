"""The Aster brand mark, defined once as geometry.

Every icon, favicon and window logo in this repository is drawn from the shapes
below, so the browser, the installers and the web pages cannot drift apart: the
mark is an italic A, an orbit sweeping around it into a tail, and a spark.

The shapes are described as path commands rather than as a hand-written SVG
string, because two outputs are needed from the same definition - SVG text for
the vector copies, and flattened polygons for the PNG/ICO rasters that Windows
and the installers use. Deriving both from one source keeps them identical.
"""
from __future__ import annotations

import io
import math
import pathlib
import struct

CANVAS = 512
BACKGROUND = "#000000"
FOREGROUND = "#FFFFFF"

Point = tuple[float, float]


class Path:
    """A minimal path: straight lines and cubic curves, to SVG or to a polygon."""

    def __init__(self, start: Point):
        self.commands: list[tuple] = [("M", start)]

    def line(self, point: Point) -> "Path":
        self.commands.append(("L", point))
        return self

    def curve(self, control1: Point, control2: Point, point: Point) -> "Path":
        self.commands.append(("C", control1, control2, point))
        return self

    def through(self, points: list[Point]) -> "Path":
        """Append a smooth curve through every point (Catmull-Rom as cubics)."""
        current = self.commands[-1][-1]
        knots = [current, current, *points, points[-1]]
        for i in range(1, len(knots) - 2):
            p0, p1, p2, p3 = knots[i - 1], knots[i], knots[i + 1], knots[i + 2]
            c1 = (p1[0] + (p2[0] - p0[0]) / 6.0, p1[1] + (p2[1] - p0[1]) / 6.0)
            c2 = (p2[0] - (p3[0] - p1[0]) / 6.0, p2[1] - (p3[1] - p1[1]) / 6.0)
            self.curve(c1, c2, p2)
        return self

    def close(self) -> "Path":
        self.commands.append(("Z",))
        return self

    def to_svg(self) -> str:
        def fmt(value: float) -> str:
            return f"{value:.2f}".rstrip("0").rstrip(".")

        def pt(point: Point) -> str:
            return f"{fmt(point[0])} {fmt(point[1])}"

        parts = []
        for command in self.commands:
            if command[0] == "Z":
                parts.append("Z")
            elif command[0] == "C":
                parts.append("C" + " ".join(pt(p) for p in command[1:]))
            else:
                parts.append(command[0] + pt(command[1]))
        return "".join(parts)

    def to_polygon(self, steps: int = 18) -> list[Point]:
        points: list[Point] = []
        current: Point = (0.0, 0.0)
        for command in self.commands:
            if command[0] == "M":
                current = command[1]
                points.append(current)
            elif command[0] == "L":
                current = command[1]
                points.append(current)
            elif command[0] == "C":
                c1, c2, end = command[1], command[2], command[3]
                for step in range(1, steps + 1):
                    t = step / steps
                    u = 1 - t
                    x = (u ** 3 * current[0] + 3 * u * u * t * c1[0]
                         + 3 * u * t * t * c2[0] + t ** 3 * end[0])
                    y = (u ** 3 * current[1] + 3 * u * u * t * c1[1]
                         + 3 * u * t * t * c2[1] + t ** 3 * end[1])
                    points.append((x, y))
                current = end
        return points


# --- The italic A -----------------------------------------------------------
# Both strokes start as a point at the apex and widen downwards, the right one
# far more than the left, which is what gives the letter its written feel.

APEX = (264.0, 118.0)


def letter_path() -> Path:
    path = Path(APEX)
    path.curve((300.0, 202.0), (340.0, 286.0), (380.0, 366.0))   # right stroke, outer
    path.line((334.0, 366.0))                                     # right foot
    path.curve((306.0, 278.0), (282.0, 204.0), (257.0, 150.0))    # right stroke, inner
    path.curve((240.0, 200.0), (223.0, 258.0), (208.0, 318.0))    # left stroke, inner
    path.line((176.0, 330.0))                                     # left foot, tucked into the tail
    path.curve((196.0, 250.0), (228.0, 178.0), APEX)              # left stroke, outer
    return path.close()


# --- The orbit --------------------------------------------------------------
# An ellipse seen almost edge on. It is open at the left, where the lower edge
# carries on past the ellipse and thins into the tail.

ORBIT_CENTRE = (262.0, 256.0)
ORBIT_RX = 128.0
ORBIT_RY = 41.0
ORBIT_TILT = math.radians(-22.0)

TAIL_TIP = (120.0, 366.0)
TAIL_TIP_DIRECTION = (0.88, -0.47)
TAIL_TIP_REACH = 86.0
TAIL_JOIN_REACH = 52.0

_ATTACH = 0.70 * math.pi          # where the tail meets the ellipse
_OPEN_END = -0.97 * math.pi       # where the orbit stops, above the tail
_HALF_WIDTH = 9.4
_TAPER = 0.13 * math.pi           # how long the open end takes to reach a point

# How thick the band is around the orbit: heaviest at the front, lightest where
# it passes behind the letter and reaches the spark.
_WIDTH_PROFILE = [
    (_ATTACH, 1.0),
    (0.35 * math.pi, 0.95),
    (0.0, 0.78),
    (-0.35 * math.pi, 0.62),
    (-0.70 * math.pi, 0.52),
    (_OPEN_END, 0.46),
]


def _orbit_point(t: float) -> Point:
    x, y = ORBIT_RX * math.cos(t), ORBIT_RY * math.sin(t)
    return (ORBIT_CENTRE[0] + x * math.cos(ORBIT_TILT) - y * math.sin(ORBIT_TILT),
            ORBIT_CENTRE[1] + x * math.sin(ORBIT_TILT) + y * math.cos(ORBIT_TILT))


def _orbit_heading(t: float) -> Point:
    """Unit vector along the direction the band is drawn (t decreasing)."""
    dx, dy = ORBIT_RX * math.sin(t), -ORBIT_RY * math.cos(t)
    x = dx * math.cos(ORBIT_TILT) - dy * math.sin(ORBIT_TILT)
    y = dx * math.sin(ORBIT_TILT) + dy * math.cos(ORBIT_TILT)
    length = math.hypot(x, y) or 1.0
    return (x / length, y / length)


def _profile_width(t: float) -> float:
    for (t0, w0), (t1, w1) in zip(_WIDTH_PROFILE, _WIDTH_PROFILE[1:]):
        if t1 <= t <= t0:
            ratio = (t0 - t) / (t0 - t1)
            factor = w0 + (w1 - w0) * ratio
            break
    else:
        factor = _WIDTH_PROFILE[-1][1]
    taper = min(1.0, max(0.0, (t - _OPEN_END) / _TAPER))
    return _HALF_WIDTH * factor * math.sin(taper * math.pi / 2)


def _bezier(p0: Point, c1: Point, c2: Point, p1: Point, t: float) -> Point:
    u = 1 - t
    return (u ** 3 * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * p1[0],
            u ** 3 * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * p1[1])


def _orbit_centreline() -> list[tuple[Point, Point, float]]:
    """Samples of (point, heading, half width) from the tail tip to the open end."""
    samples: list[tuple[Point, Point, float]] = []

    join = _orbit_point(_ATTACH)
    join_heading = _orbit_heading(_ATTACH)
    c1 = (TAIL_TIP[0] + TAIL_TIP_DIRECTION[0] * TAIL_TIP_REACH,
          TAIL_TIP[1] + TAIL_TIP_DIRECTION[1] * TAIL_TIP_REACH)
    c2 = (join[0] - join_heading[0] * TAIL_JOIN_REACH,
          join[1] - join_heading[1] * TAIL_JOIN_REACH)
    tail_steps = 7
    join_width = _profile_width(_ATTACH)
    for step in range(tail_steps):
        t = step / tail_steps
        point = _bezier(TAIL_TIP, c1, c2, join, t)
        ahead = _bezier(TAIL_TIP, c1, c2, join, min(1.0, t + 0.004))
        heading = (ahead[0] - point[0], ahead[1] - point[1])
        length = math.hypot(*heading) or 1.0
        samples.append(((point[0], point[1]), (heading[0] / length, heading[1] / length),
                        join_width * (t ** 0.62)))

    orbit_steps = 22
    for step in range(orbit_steps + 1):
        t = _ATTACH + (_OPEN_END - _ATTACH) * step / orbit_steps
        samples.append((_orbit_point(t), _orbit_heading(t), _profile_width(t)))
    return samples


def orbit_path() -> Path:
    samples = _orbit_centreline()
    left: list[Point] = []
    right: list[Point] = []
    for point, heading, width in samples:
        normal = (-heading[1], heading[0])
        left.append((point[0] + normal[0] * width, point[1] + normal[1] * width))
        right.append((point[0] - normal[0] * width, point[1] - normal[1] * width))
    path = Path(left[0])
    path.through(left[1:])
    path.through(list(reversed(right)))
    return path.close()


# --- The spark --------------------------------------------------------------

SPARK_CENTRE = (386.0, 166.0)
SPARK_RX = 34.0
SPARK_RY = 42.0
_SPARK_WAIST = 0.19


def spark_path() -> Path:
    cx, cy = SPARK_CENTRE
    ox, oy = SPARK_RX * _SPARK_WAIST, SPARK_RY * _SPARK_WAIST
    # Each quarter bows in towards the centre, which is what makes the points.
    path = Path((cx, cy - SPARK_RY))
    path.curve((cx + ox, cy - oy), (cx + SPARK_RX - ox * 2, cy - oy), (cx + SPARK_RX, cy))
    path.curve((cx + SPARK_RX - ox * 2, cy + oy), (cx + ox, cy + oy), (cx, cy + SPARK_RY))
    path.curve((cx - ox, cy + oy), (cx - SPARK_RX + ox * 2, cy + oy), (cx - SPARK_RX, cy))
    path.curve((cx - SPARK_RX + ox * 2, cy - oy), (cx - ox, cy - oy), (cx, cy - SPARK_RY))
    return path.close()


def mark_paths() -> list[Path]:
    """The three shapes of the mark, in drawing order."""
    return [orbit_path(), letter_path(), spark_path()]


# --- Output ----------------------------------------------------------------
# Three framings of one mark. "logo" is the brand image as drawn above. "icon"
# is the same mark enlarged to fill a launcher tile, because at 16 px the logo's
# generous margin leaves too little glyph to recognise. "mark" is that framing
# without the black tile, for surfaces that are already dark.

ICON_FILL = 0.88
VARIANTS = ("logo", "icon", "mark")
#: Windows picks the closest of these, from the title bar to the desktop.
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)


def mark_bounds() -> tuple[float, float, float, float]:
    xs: list[float] = []
    ys: list[float] = []
    for path in mark_paths():
        for x, y in path.to_polygon(steps=12):
            xs.append(x)
            ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


def variant_transform(variant: str) -> tuple[float, float, float]:
    """Scale and offset applied to the mark for one framing."""
    if variant == "logo":
        return 1.0, 0.0, 0.0
    left, top, right, bottom = mark_bounds()
    scale = CANVAS * ICON_FILL / max(right - left, bottom - top)
    dx = (CANVAS - (right - left) * scale) / 2 - left * scale
    dy = (CANVAS - (bottom - top) * scale) / 2 - top * scale
    return scale, dx, dy


def svg(variant: str = "logo", size: int | None = None, title: str = "Aster",
        class_name: str = "", compact: bool = False) -> str:
    """One variant as SVG. `compact` puts it on a single line, for inlining."""
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant: {variant!r}")
    scale, dx, dy = variant_transform(variant)
    attrs = f'xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}"'
    if size:
        attrs += f' width="{size}" height="{size}"'
    if class_name:
        attrs += f' class="{class_name}"'
    lines = [f'<svg {attrs} role="img" aria-label="{title}">']
    if variant != "mark":
        lines.append(f'  <rect width="{CANVAS}" height="{CANVAS}" fill="{BACKGROUND}"/>')
    group = f'<g fill="{FOREGROUND}"'
    if (scale, dx, dy) != (1.0, 0.0, 0.0):
        group += f' transform="translate({dx:.2f} {dy:.2f}) scale({scale:.4f})"'
    lines.append("  " + group + ">")
    for path in mark_paths():
        lines.append(f'    <path d="{path.to_svg()}"/>')
    lines.append("  </g>")
    lines.append("</svg>")
    if compact:
        return "".join(line.strip() for line in lines)
    return "\n".join(lines) + "\n"


def render(size: int, variant: str = "logo", supersample: int = 4):
    """Rasterise a variant with Pillow, which the Windows icons are built from.

    Drawn large and scaled down, because Pillow fills polygons without
    antialiasing and the mark is nothing but curves.
    """
    from PIL import Image, ImageDraw  # imported here so the SVG side needs no Pillow

    canvas = size * supersample
    mode, background = ("RGBA", (0, 0, 0, 0)) if variant == "mark" else ("RGB", BACKGROUND)
    image = Image.new(mode, (canvas, canvas), background)
    draw = ImageDraw.Draw(image)
    scale, dx, dy = variant_transform(variant)
    unit = canvas / CANVAS
    for path in mark_paths():
        points = [((x * scale + dx) * unit, (y * scale + dy) * unit) for x, y in path.to_polygon()]
        draw.polygon(points, fill=FOREGROUND)
    return image.resize((size, size), Image.LANCZOS)


def write_ico(path: str | pathlib.Path) -> None:
    """An .ico holding a PNG per size, so 16 px and 256 px are both sharp."""
    images = []
    for size in ICO_SIZES:
        buffer = io.BytesIO()
        render(size, "icon").save(buffer, format="PNG")
        images.append((size, buffer.getvalue()))

    header = struct.pack("<HHH", 0, 1, len(images))
    offset = len(header) + 16 * len(images)
    entries, payloads = b"", b""
    for size, data in images:
        side = size if size < 256 else 0
        entries += struct.pack("<BBBBHHII", side, side, 0, 0, 1, 32, len(data), offset)
        payloads += data
        offset += len(data)
    pathlib.Path(path).write_bytes(header + entries + payloads)
