"""Synthetic regression tests for overlay screenshot assertions."""
import unittest
from pathlib import Path
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_overlay_screenshots import bubble_evidence


class OverlayScreenshotTests(unittest.TestCase):
    def setUp(self):
        self.size = (1080, 2400)  # 3 px/dp emulator screenshot
        self.before = Image.new("RGB", self.size, (180, 180, 180))
        self.after = self.before.copy()

    def test_focused_purple_bubble_passes(self):
        draw = ImageDraw.Draw(self.after)
        # default left/top = (width - 80dp, 105dp) at 3 px/dp
        draw.ellipse((840, 315, 1013, 488), fill=(120, 85, 150))
        score, details = bubble_evidence(self.before, self.after)
        self.assertGreater(score, 1000)
        self.assertEqual(details[0], 3.0)

    def test_alpha_blended_muted_lavender_passes(self):
        # Representative of the actual emulator capture: purple rendered over
        # a light background is close to neutral lavender, not saturated purple.
        before = Image.new("RGB", (1080, 1920), (241, 240, 247))
        after = before.copy()
        draw = ImageDraw.Draw(after)
        # 2.5 px/dp default placement: x=880, y=262.5, size=145 px.
        draw.ellipse((880, 263, 1025, 408), fill=(175, 168, 182))
        score, details = bubble_evidence(before, after)
        self.assertGreater(score, 700)
        self.assertEqual(details[0], 2.5)

    def test_large_layout_change_and_ime_underline_do_not_pass(self):
        draw = ImageDraw.Draw(self.after)
        # Broad layout-wide change and a thin keyboard/underline artifact well
        # away from the bubble must not satisfy the local shape assertion.
        draw.rectangle((0, 0, 700, 1000), fill=(80, 100, 130))
        draw.line((250, 1500, 1000, 1500), fill=(30, 50, 150), width=3)
        self.assertEqual(bubble_evidence(self.before, self.after)[0], 0)

    def test_wrong_location_or_line_only_change_fails(self):
        draw = ImageDraw.Draw(self.after)
        draw.ellipse((100, 315, 273, 488), fill=(120, 85, 150))
        draw.line((840, 400, 1013, 400), fill=(120, 85, 150), width=4)
        self.assertEqual(bubble_evidence(self.before, self.after)[0], 0)

    def test_neutral_rectangle_with_small_lavender_patch_fails(self):
        before = Image.new("RGB", (1080, 2400), (180, 180, 180))
        after = before.copy()
        draw = ImageDraw.Draw(after)
        draw.rectangle((850, 325, 1000, 475), fill=(151, 151, 151))
        draw.rectangle((900, 375, 951, 426), fill=(175, 168, 182))
        self.assertEqual(bubble_evidence(before, after)[0], 0)


if __name__ == "__main__":
    unittest.main()
