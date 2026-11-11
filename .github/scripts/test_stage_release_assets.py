"""Tests for native release artifact collection."""

import tempfile
import unittest
from pathlib import Path

from stage_release_assets import stage_release_assets


class StageReleaseAssetsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "downloaded"
        self.target = self.root / "staged"
        self.source.mkdir()

    def add(self, relative_path: str):
        file = self.source / relative_path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b"test installer")
        return file

    def test_flatten_linux_subdirectories_and_windows_installer(self):
        self.add("deb/grbl-commander_0.1.1_amd64.deb")
        self.add("rpm/grbl-commander-0.1.1-1.x86_64.rpm")
        self.add("grbl-commander-0.1.1.msi")

        staged = stage_release_assets(self.source, self.target)
        self.assertEqual(len(staged), 3)
        self.assertEqual({p.suffix for p in staged}, {".deb", ".rpm", ".msi"})
        self.assertTrue(all(p.parent == self.target for p in staged))

    def test_nested_artifact_downloads_are_supported(self):
        for name, suffix in [
            ("linux/deb/a", ".deb"), ("linux/rpm/b", ".rpm"),
            ("windows/c", ".msi"),
        ]:
            self.add(name + suffix)
        self.assertEqual(len(stage_release_assets(self.source, self.target)), 3)

    def test_missing_package_fails_before_copy(self):
        self.add("deb/a.deb")
        self.add("rpm/b.rpm")
        with self.assertRaisesRegex(ValueError, "Missing installer format"):
            stage_release_assets(self.source, self.target)
        self.assertFalse(self.target.exists())

    def test_unsupported_macos_file_is_not_published(self):
        self.add("linux/deb/app.deb")
        self.add("linux/rpm/app.rpm")
        self.add("windows/app.msi")
        self.add("macos/legacy.dmg")
        staged = stage_release_assets(self.source, self.target)
        self.assertEqual({p.suffix for p in staged}, {".deb", ".rpm", ".msi"})
        self.assertFalse((self.target / "legacy.dmg").exists())

    def test_duplicate_filenames_fail_before_copy(self):
        self.add("linux/deb/package.deb")
        self.add("another-linux/deb/package.deb")
        self.add("rpm/a.rpm")
        self.add("b.msi")
        with self.assertRaisesRegex(ValueError, "unique"):
            stage_release_assets(self.source, self.target)
        self.assertFalse(self.target.exists())


if __name__ == "__main__":
    unittest.main()
