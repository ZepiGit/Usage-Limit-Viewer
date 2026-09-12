import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("screenshots", Path(__file__).with_name("export-screenshots.py"))
screenshots = importlib.util.module_from_spec(spec)
spec.loader.exec_module(screenshots)


class ScreenshotExportTests(unittest.TestCase):
    def test_similar_capture_names_export_their_own_attachments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = []
            names = ["iphone-custom-layout", "iphone-custom-layout-restored"]
            for index, name in enumerate(names):
                filename = f"attachment-{index}.png"
                (root / filename).write_bytes(name.encode())
                records.append({"suggestedHumanReadableName": f"{name}_0_UUID.png", "exportedFileName": filename})
            (root / "manifest.json").write_text(json.dumps(records))
            screenshots.export(root, root / "output", names)
            for name in names:
                self.assertEqual((root / "output" / f"{name}.png").read_bytes(), name.encode())

    def test_a_missing_capture_does_not_use_a_longer_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "restored.png").write_bytes(b"restored")
            (root / "manifest.json").write_text(json.dumps([{
                "suggestedHumanReadableName": "iphone-custom-layout-restored_0_UUID.png",
                "exportedFileName": "restored.png",
            }]))
            with self.assertRaisesRegex(ValueError, "found 0"):
                screenshots.export(root, root / "output", ["iphone-custom-layout"])


if __name__ == "__main__":
    unittest.main()
