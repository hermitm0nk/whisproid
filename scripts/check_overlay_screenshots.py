"""Assert the bubble appears at the right edge of a focused external editor."""
import sys
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

for path, expected in ((sys.argv[3], "false"), (sys.argv[4], "true")):
    root = ET.parse(path).getroot()
    editor = next(n for n in root.iter("node") if n.get("text") == "Write a message")
    if editor.get("focused") != expected:
        raise AssertionError(f"External editor focus mismatch in {path}")

unfocused = Image.open(sys.argv[1]).convert("RGB")
focused = Image.open(sys.argv[2]).convert("RGB")
if unfocused.size != focused.size:
    raise AssertionError("Emulator screen size changed between focus states")
w, h = focused.size
# The default bubble sits at the right edge around 105 dp below the status bar.
# The IME changes the lower half of the screen, outside this comparison region.
region = (int(w * .70), int(h * .10), w, int(h * .35))
diff = ImageChops.difference(unfocused.crop(region), focused.crop(region))
changed = sum(1 for pixel in diff.getdata() if max(pixel) > 30)
if changed < 5_000:
    raise AssertionError(f"Floating bubble absent in focused editor: {changed} changed pixels")
print(f"Focused editor overlay visible: {changed} changed pixels in bubble region")
