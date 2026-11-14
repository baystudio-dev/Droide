import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "fdroid_export", Path(__file__).resolve().parents[1] / "export_fdroid_source.py")
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class FdroidSourceExportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        self.output = self.root.parent / "export"
        self.root.mkdir()
        for path in ("settings.gradle.kts", ".github/workflows/android-ci.yml", "README.md"):
            self.write(path, "regular distribution\n")
        for path in ("settings.gradle.kts", "android-ci.yml", "README.md"):
            self.write("tools/fdroid/" + path, "source distribution\n")

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def export(self):
        with contextlib.redirect_stdout(io.StringIO()):
            exporter.export(self.root, self.output)
        return json.loads((self.output / "FDROID-SOURCE-MANIFEST.json").read_text())

    def test_build_source_and_test_fixtures_are_preserved(self):
        paths = ("tools/build-readiness/prepare_build.py", "tools/build-logic/check.py",
                 "third_party/example/build/config.txt", "tools/tests/fixtures/build/input.txt")
        for path in paths:
            self.write(path, "required source\n")
        manifest = self.export()
        for path in paths:
            with self.subTest(path=path):
                self.assertEqual((self.root / path).read_bytes(), (self.output / path).read_bytes())
                self.assertEqual(hashlib.sha256((self.root / path).read_bytes()).hexdigest(),
                                 manifest["files"][path])

    def test_only_known_generated_roots_are_omitted(self):
        paths = ("build/report.txt", "app/build/report.txt", "app/build-host-jvm-tests/report.txt",
                 "app/build-source-release-fdroid-candidate/report.txt", ".gradle/state.txt",
                 "app/.cxx/state.txt", "tools/__pycache__/module.pyc", ".fdroid-source-products/receipt.json")
        for path in paths:
            self.write(path, "generated\n")
        self.export()
        for path in paths:
            self.assertFalse((self.output / path).exists(), path)
            self.assertTrue((self.root / path).exists(), path)

    def test_local_secrets_are_excluded_but_example_is_retained(self):
        paths = (".env", ".env.local", "signing.properties", "private/release.keystore")
        for path in paths:
            self.write(path, "synthetic fixture; not a credential\n")
        self.write(".env.example", "KEY=\n")
        manifest = self.export()
        for path in paths:
            self.assertFalse((self.output / path).exists(), path)
            self.assertIn(path, manifest["excluded"])
        self.assertEqual("KEY=\n", (self.output / ".env.example").read_text())

    def test_transformed_inputs_are_hashed_without_mutating_originals(self):
        self.write("FDROID-SOURCE-MANIFEST.json", '{"previous":"export"}\n')
        self.write("fdroid-build.log", "old build log\n")
        manifest = self.export()
        self.assertNotIn("FDROID-SOURCE-MANIFEST.json", manifest["files"])
        self.assertFalse((self.output / "fdroid-build.log").exists())
        for path in ("settings.gradle.kts", ".github/workflows/android-ci.yml", "README.md"):
            self.assertEqual("regular distribution\n", (self.root / path).read_text())
            self.assertEqual("source distribution\n", (self.output / path).read_text())
            self.assertEqual(hashlib.sha256((self.output / path).read_bytes()).hexdigest(),
                             manifest["files"][path])

    def test_existing_and_nested_output_are_rejected(self):
        for output in (self.root, self.root / "nested", self.root.parent):
            with self.subTest(output=output), self.assertRaises(ValueError):
                exporter.export(self.root, output)

    def test_source_symlink_cannot_copy_external_content(self):
        external = self.root.parent / "outside.txt"
        external.write_text("private fixture")
        (self.root / "linked.txt").symlink_to(external)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.export()
        self.assertFalse((self.output / "FDROID-SOURCE-MANIFEST.json").exists())
        self.assertEqual("private fixture", external.read_text())


if __name__ == "__main__":
    unittest.main()
