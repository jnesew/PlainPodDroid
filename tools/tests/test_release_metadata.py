import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "listing", Path(__file__).resolve().parents[1] / "verify-metadata.py")
listing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(listing)


class ListingBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("fastlane/metadata", "distribution"):
            shutil.copytree(listing.ROOT / name, self.root / name)
        shutil.copyfile(listing.ROOT / "gradle.properties", self.root / "gradle.properties")
        self.locale = self.root / "fastlane/metadata/android/en-US"

    def test_shared_listing_and_real_capture_dimensions_pass(self):
        self.assertEqual(6, listing.check(self.root))

    def test_overlong_description_is_rejected(self):
        (self.locale / "short_description.txt").write_text("x" * 81)
        with self.assertRaisesRegex(ValueError, "1..80"): listing.check(self.root)

    def test_missing_version_code_changelog_is_rejected(self):
        (self.locale / "changelogs/2.txt").unlink()
        with self.assertRaisesRegex(ValueError, "changelog"): listing.check(self.root)

    def test_corrupt_png_is_rejected(self):
        path = self.locale / "images/icon.png"
        data = bytearray(path.read_bytes()); data[-5] ^= 1; path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "CRC"): listing.check(self.root)

    def test_wrong_apk_filter_is_rejected(self):
        import json
        path = self.root / "distribution/obtainium.json"
        value = json.loads(path.read_text()); settings = json.loads(value[0]["additionalSettings"])
        settings["apkFilterRegEx"] = ".*"
        value[0]["additionalSettings"] = json.dumps(settings); path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, "filter"): listing.check(self.root)

    def test_unsubmitted_fdroid_status_is_not_silently_removed(self):
        path = self.root / "distribution/fdroid/io.github.jnesew.plainpoddroid.yml"
        path.write_text(path.read_text().replace(
            "Disabled: Awaiting the first public release and F-Droid maintainer review\n", ""))
        with self.assertRaisesRegex(ValueError, "pending release review"): listing.check(self.root)


if __name__ == "__main__":
    unittest.main()
