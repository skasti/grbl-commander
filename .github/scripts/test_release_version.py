"""Regression tests for release version selection and label validation."""

import unittest
from release_version import bump_type, next_version


class ReleaseVersionTests(unittest.TestCase):
    def test_initial_patch_bumps_from_0_1_0(self):
        self.assertEqual(next_version([], []), "0.1.1")

    def test_initial_minor_bumps_from_0_1_0(self):
        self.assertEqual(next_version([], ["minor"]), "0.2.0")

    def test_initial_major_bumps_from_0_1_0(self):
        self.assertEqual(next_version([], ["major"]), "1.0.0")

    def test_patch_is_default(self):
        self.assertEqual(next_version(["v0.4.9"], ["documentation"]), "0.4.10")

    def test_minor_resets_patch(self):
        self.assertEqual(next_version(["v0.4.9"], ["minor"]), "0.5.0")

    def test_major_resets_minor_and_patch(self):
        self.assertEqual(next_version(["v0.4.9"], ["major"]), "1.0.0")

    def test_semantic_order_is_numeric(self):
        self.assertEqual(next_version(["v0.9.9", "v0.10.0", "v0.2.99"], []), "0.10.1")

    def test_non_semver_tags_are_ignored(self):
        self.assertEqual(next_version(["v0.1.0-rc.1", "latest", "v1.02.0"], []), "0.1.1")

    def test_major_minor_combination_fails(self):
        with self.assertRaisesRegex(ValueError, "both 'major' and 'minor'"):
            next_version(["v0.1.0"], ["major", "minor"])

    def test_irrelevant_labels_do_not_change_bump(self):
        self.assertEqual(bump_type(["bug", "needs-review"]), "patch")


if __name__ == "__main__":
    unittest.main()
