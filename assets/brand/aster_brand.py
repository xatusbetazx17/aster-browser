"""Aster's logo: one drawing, read from asterlogo.png, written out everywhere.

`asterlogo.png` in the repository root is the artwork itself. Everything the
project shows - the icon in the browser's title bar, the Linux desktop entry,
the Windows setup executable and its window, the extension, the new tab page -
is derived from that one file here, so replacing the logo means dropping in a
new bitmap rather than hunting down a dozen copies.

The vector copies are traced from the bitmap rather than drawn by hand. The mark
is two colours with straight runs and smooth arcs, which a contour trace with
corner detection reproduces closely, and tracing keeps the SVG and the rasters
the same drawing instead of two drawings that merely resemble each other. The
rasters come straight from the bitmap, so they are exactly the artwork.
"""
from __future__ import annotations

import io
import math
import pathlib
import struct

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "asterlogo.png"

CANVAS = 512
BACKGROUND = "#000000"
FOREGROUND = "#FFFFFF"

#: A pixel this bright or brighter is part of the mark. The artwork is a flat
#: white on a flat black, so anything near the middle is an antialiased edge.
THRESHOLD = 128
#: How far, in source pixels, a traced outline may leave the bitmap's own edge.
#: Large enough to drop the staircase along a diagonal, small enough to keep the
#: shoulders of the letter and the point of the bolt.
SIMPLIFY = 1.4
#: A vertex that turns the outline by more than this stays a corner; gentler
#: ones are curved through. The arcs here turn by around 15 degrees a step at
#: the simplification above, and every real corner in the mark is far sharper.
CORNER_DEGREES = 45.0

Point = tuple[float, float]


class Path:
    """A path of lines and cubics, which can be written as SVG or flattened."""

    def __init__(self, start: Point):
        self.commands: list[tuple] = [("M", start)]

    def move(self, point: Point) -> "Path":
        self.commands.append(("M", point))
        return self

    def line(self, point: Point) -> "Path":
        self.commands.append(("L", point))
        return self

    def curve(self, control1: Point, control2: Point, point: Point) -> "Path":
        self.commands.append(("C", control1, control2, point))
        return self

    def close(self) -> "Path":
        self.commands.append(("Z",))
        return self

    def to_svg(self) -> str:
        def fmt(value: float) -> str:
            return f"{value:.1f}".rstrip("0").rstrip(".")

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

    def to_polygon(self, steps: int = 12) -> list[Point]:
        points: list[Point] = []
        current: Point = (0.0, 0.0)
        for command in self.commands:
            if command[0] in ("M", "L"):
                current = command[1]
                points.append(current)
            elif command[0] == "C":
                c1, c2, end = command[1], command[2], command[3]
                for step in range(1, steps + 1):
                    t = step / steps
                    u = 1 - t
                    points.append((
                        u ** 3 * current[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * end[0],
                        u ** 3 * current[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * end[1],
                    ))
                current = end
        return points


# --- Reading the artwork ----------------------------------------------------

_cache: dict[str, object] = {}


def _source():
    """The artwork as greyscale. Its brightness doubles as the mark's coverage."""
    if "source" not in _cache:
        from PIL import Image  # imported here: only the build path needs Pillow

        if not SOURCE.is_file():
            raise FileNotFoundError(f"the logo artwork is missing: {SOURCE}")
        _cache["source"] = Image.open(SOURCE).convert("L")
    return _cache["source"]


def _contours() -> list[list[Point]]:
    """Closed outlines around the mark, in source pixels (marching squares).

    Each cell of the pixel grid contributes the piece of outline that crosses
    it; the pieces are then stitched end to end into loops. Holes come out as
    loops of their own, which is why the paths below are filled even-odd.
    """
    if "contours" in _cache:
        return _cache["contours"]

    image = _source()
    width, height = image.size
    pixels = image.load()
    rows = [[pixels[x, y] >= THRESHOLD for x in range(width)] for y in range(height)]

    def inside(x: int, y: int) -> bool:
        return 0 <= x < width and 0 <= y < height and rows[y][x]

    # Which mid-edge points a cell joins, by its corners: top-left 1, top-right
    # 2, bottom-right 4, bottom-left 8. The two saddle cases (5 and 10) are cut
    # the same way every time, so the loops always close.
    joins = {
        1: ((3, 0),), 2: ((0, 1),), 3: ((3, 1),), 4: ((1, 2),),
        5: ((3, 0), (1, 2)), 6: ((0, 2),), 7: ((3, 2),), 8: ((2, 3),),
        9: ((2, 0),), 10: ((0, 1), (2, 3)), 11: ((2, 1),), 12: ((1, 3),),
        13: ((1, 0),), 14: ((0, 3),),
    }
    steps: dict[Point, list[Point]] = {}
    for y in range(-1, height):
        for x in range(-1, width):
            code = ((1 if inside(x, y) else 0) | (2 if inside(x + 1, y) else 0)
                    | (4 if inside(x + 1, y + 1) else 0) | (8 if inside(x, y + 1) else 0))
            if code in (0, 15):
                continue
            edges = ((x + 0.5, y), (x + 1.0, y + 0.5), (x + 0.5, y + 1.0), (x, y + 0.5))
            for start, end in joins[code]:
                steps.setdefault(edges[start], []).append(edges[end])

    contours = []
    for first in list(steps):
        while steps.get(first):
            loop = [first]
            point = first
            while True:
                onward = steps.get(point)
                if not onward:
                    break
                point = onward.pop()
                loop.append(point)
                if point == first:
                    break
            if len(loop) > 12:
                contours.append(loop)
    contours.sort(key=len, reverse=True)
    _cache["contours"] = contours
    return contours


def _simplify(points: list[Point], epsilon: float) -> list[Point]:
    """Douglas-Peucker, run over the loop without recursing into deep stacks."""
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    pending = [(0, len(points) - 1)]
    while pending:
        start, end = pending.pop()
        if end <= start + 1:
            continue
        ax, ay = points[start]
        bx, by = points[end]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        worst, index = -1.0, start
        for i in range(start + 1, end):
            px, py = points[i]
            if length == 0:
                distance = math.hypot(px - ax, py - ay)
            else:
                distance = abs(dy * px - dx * py + bx * ay - by * ax) / length
            if distance > worst:
                worst, index = distance, i
        if worst > epsilon:
            keep[index] = True
            pending.append((start, index))
            pending.append((index, end))
    return [point for point, kept in zip(points, keep) if kept]


def _path_from_contour(points: list[Point], scale: float) -> list[tuple]:
    """Turn one traced loop into path commands, curving through everything
    except the corners.

    Each edge gets a cubic whose handles reach towards the neighbouring points
    where the outline is smooth, and lie on the edge itself where it meets a
    corner. An edge between two corners therefore comes out as a straight line,
    which is what most of this mark is.
    """
    ring = [(x * scale, y * scale) for x, y in points[:-1]]
    count = len(ring)
    if count < 3:
        return []

    limit = math.cos(math.radians(CORNER_DEGREES))
    tangents: list[Point | None] = []
    for i in range(count):
        ax, ay = ring[i - 1]
        bx, by = ring[i]
        cx, cy = ring[(i + 1) % count]
        first = (bx - ax, by - ay)
        second = (cx - bx, cy - by)
        len1 = math.hypot(*first) or 1.0
        len2 = math.hypot(*second) or 1.0
        turn = (first[0] * second[0] + first[1] * second[1]) / (len1 * len2)
        if turn <= limit:
            tangents.append(None)  # a corner: the handles stay on their edges
            continue
        span = ((cx - ax), (cy - ay))
        length = math.hypot(*span) or 1.0
        tangents.append((span[0] / length, span[1] / length))

    commands: list[tuple] = [("M", ring[0])]
    for i in range(count):
        start = ring[i]
        end = ring[(i + 1) % count]
        head, tail = tangents[i], tangents[(i + 1) % count]
        if head is None and tail is None:
            commands.append(("L", end))
            continue
        # Handles reach a third of the way along this edge, which is what keeps
        # an unevenly sampled outline from bulging between distant points.
        reach = math.hypot(end[0] - start[0], end[1] - start[1]) / 3.0
        edge = ((end[0] - start[0]) / (reach * 3 or 1.0), (end[1] - start[1]) / (reach * 3 or 1.0))
        head = head or edge
        tail = tail or edge
        commands.append(("C",
                         (start[0] + head[0] * reach, start[1] + head[1] * reach),
                         (end[0] - tail[0] * reach, end[1] - tail[1] * reach),
                         end))
    commands.append(("Z",))
    return commands


def mark_path() -> Path:
    """The whole mark as one even-odd path, scaled into the 512 canvas."""
    if "path" not in _cache:
        width, height = _source().size
        scale = CANVAS / max(width, height)
        commands: list[tuple] = []
        for contour in _contours():
            commands += _path_from_contour(_simplify(contour, SIMPLIFY), scale)
        path = Path(commands[0][1])
        path.commands = commands
        _cache["path"] = path
    return _cache["path"]


# --- Framings ---------------------------------------------------------------
# One mark, three framings. "logo" is the artwork as it was drawn. "icon" is the
# same mark enlarged to fill a launcher tile, because at 16 px the artwork's
# margin leaves too little glyph to recognise. "mark" is that framing without
# the black tile, for surfaces that are already dark.

ICON_FILL = 0.88
VARIANTS = ("logo", "icon", "mark")
#: Windows picks the closest of these, from the title bar to the desktop.
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)


def mark_bounds() -> tuple[float, float, float, float]:
    """The mark's own extent inside the canvas, ignoring the artwork's margin."""
    points = mark_path().to_polygon()
    xs = [x for x, _ in points]
    ys = [y for _, y in points]
    return min(xs), min(ys), max(xs), max(ys)


def variant_transform(variant: str) -> tuple[float, float, float]:
    """Scale and offset applied to the mark for one framing."""
    if variant == "logo":
        return 1.0, 0.0, 0.0
    left, top, right, bottom = mark_bounds()
    scale = CANVAS * ICON_FILL / max(right - left, bottom - top)
    return (scale,
            (CANVAS - (right - left) * scale) / 2 - left * scale,
            (CANVAS - (bottom - top) * scale) / 2 - top * scale)


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
    group = f'<g fill="{FOREGROUND}" fill-rule="evenodd"'
    if (scale, dx, dy) != (1.0, 0.0, 0.0):
        group += f' transform="translate({dx:.1f} {dy:.1f}) scale({scale:.4f})"'
    lines.append("  " + group + ">")
    lines.append(f'    <path d="{mark_path().to_svg()}"/>')
    lines.append("  </g>")
    lines.append("</svg>")
    if compact:
        return "".join(line.strip() for line in lines)
    return "\n".join(lines) + "\n"


def render(size: int, variant: str = "logo"):
    """One variant as a bitmap, scaled from the artwork itself."""
    from PIL import Image  # imported here: only the build path needs Pillow

    if variant not in VARIANTS:
        raise ValueError(f"unknown variant: {variant!r}")
    grey = _source()
    if variant == "logo":
        return grey.convert("RGB").resize((size, size), Image.LANCZOS)

    glyph = Image.new("RGBA", grey.size, (255, 255, 255, 0))
    glyph.putalpha(grey)
    glyph = glyph.crop(grey.point(lambda value: 255 if value >= THRESHOLD else 0).getbbox())
    side = max(1, round(size * ICON_FILL))
    scale = side / max(glyph.size)
    glyph = glyph.resize((max(1, round(glyph.width * scale)), max(1, round(glyph.height * scale))),
                         Image.LANCZOS)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0) if variant == "mark" else (0, 0, 0, 255))
    canvas.alpha_composite(glyph, ((size - glyph.width) // 2, (size - glyph.height) // 2))
    return canvas if variant == "mark" else canvas.convert("RGB")


def write_ico(path: str | pathlib.Path) -> None:
    """An .ico holding a PNG per size, so 16 px and 256 px are both sharp.

    The mark, not the tile: a taskbar, a title bar and a desktop each supply
    their own background, and a black square of ours sitting on them is the one
    thing that would make the icon look pasted on.
    """
    images = []
    for size in ICO_SIZES:
        buffer = io.BytesIO()
        render(size, "mark").save(buffer, format="PNG")
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
