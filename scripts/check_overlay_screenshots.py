"""Assert the default floating bubble is visible over a focused external editor."""
import sys
import xml.etree.ElementTree as ET
from collections import deque

from PIL import Image


def _largest_component(mask, width, height):
    """Return (pixel count, bounds) for the largest 8-connected true component."""
    seen = bytearray(width * height)
    largest = (0, None)
    for start, active in enumerate(mask):
        if not active or seen[start]:
            continue
        queue = deque([start])
        seen[start] = 1
        count = 0
        x0 = x1 = start % width
        y0 = y1 = start // width
        while queue:
            pos = queue.popleft()
            x, y = pos % width, pos // width
            count += 1
            x0, x1 = min(x0, x), max(x1, x)
            y0, y1 = min(y0, y), max(y1, y)
            for ny in range(max(0, y - 1), min(height, y + 2)):
                for nx in range(max(0, x - 1), min(width, x + 2)):
                    neighbor = ny * width + nx
                    if mask[neighbor] and not seen[neighbor]:
                        seen[neighbor] = 1
                        queue.append(neighbor)
        if count > largest[0]:
            largest = count, (x0, y0, x1 + 1, y1 + 1)
    return largest


def bubble_evidence(unfocused, focused):
    """Return strongest plausible bubble evidence; screen coordinates are pixels."""
    if unfocused.size != focused.size:
        raise AssertionError("Emulator screen size changed between focus states")
    width, height = focused.size
    before, after = unfocused.convert("RGB"), focused.convert("RGB")
    # Android emulator densities vary. The screenshot itself does not encode dp,
    # so test standard density buckets; each candidate is only a 58 dp footprint.
    best = (0, None)
    for density in (1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75, 3.0, 3.5, 4.0):
        size = round(58 * density)
        left = round(width - (22 + 58) * density)
        top = round(105 * density)
        # Allow a few dp for rounding / window insets, but not a broad screen scan.
        margin = max(2, round(4 * density))
        box = (max(0, left - margin), max(0, top - margin),
               min(width, left + size + margin), min(height, top + size + margin))
        if box[2] <= box[0] or box[3] <= box[1]:
            continue
        a, b = before.crop(box), after.crop(box)
        rw, rh = a.size
        pixels_a, pixels_b = list(a.getdata()), list(b.getdata())
        changed = []
        bubble_tone = []
        for p, q in zip(pixels_a, pixels_b):
            delta = max(abs(p[i] - q[i]) for i in range(3))
            changed.append(delta >= 24)
            # Alpha blending against a light editor background can turn the
            # default purple into a very light, low-saturation lavender. Check
            # its relative blue/purple cast, not an absolute darkness cutoff.
            r, g, blue = q
            bubble_tone.append(blue >= g + 5 and r >= g - 4 and
                               blue >= r - 2 and max(r, g, blue) < 240)
        count, bounds = _largest_component(changed, rw, rh)
        if bounds is None:
            continue
        x0, y0, x1, y1 = bounds
        component_pixels = [i for i, active in enumerate(changed) if active and
                            x0 <= i % rw < x1 and y0 <= i // rw < y1]
        colored = sum(bubble_tone[i] for i in component_pixels)
        box_w, box_h = x1 - x0, y1 - y0
        # Compact and not line-like; scales with the expected 58 dp circle.
        min_area = max(120, round((size * size) * .045))
        shape_ok = (count >= min_area and box_w >= size * .35 and box_h >= size * .35
                    and max(box_w, box_h) <= size * 1.1
                    and min(box_w, box_h) / max(box_w, box_h) >= .55)
        score = count if shape_ok and colored >= max(80, count * .10) else 0
        if score > best[0]:
            best = score, (density, count, colored, box)
    return best


def main(argv):
    unfocused_png, focused_png, unfocused_xml, focused_xml = argv[1:5]
    for path, expected in ((unfocused_xml, "false"), (focused_xml, "true")):
        root = ET.parse(path).getroot()
        editor = next(n for n in root.iter("node") if n.get("text") == "Write a message")
        if editor.get("focused") != expected:
            raise AssertionError(f"External editor focus mismatch in {path}")
    evidence = bubble_evidence(Image.open(unfocused_png), Image.open(focused_png))
    score, details = evidence
    if not score:
        raise AssertionError(
            "Default floating bubble absent from its expected 58 dp upper-right region; "
            "a wide-layout or IME-only change does not satisfy this check")
    density, changed, tinted, _ = details
    print(f"Focused editor overlay visible: {changed} compact changed pixels "
          f"({tinted} lavender-tinted) near {density:g} px/dp candidate")


if __name__ == "__main__":
    main(sys.argv)
