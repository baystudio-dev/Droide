import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_fdroid_inputs as verifier


class FdroidInputIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.write("third_party/component/source.c", "retained source\n")
        self.write("third_party/component/legacy.jar", "synthetic binary fixture\n")
        self.write("third_party/kadb/mdns-source.json", '{"sourceSha256":{}}\n')
        self.write("app/gradle-fdroid.lockfile", "empty=releaseRuntimeClasspath\n")
        self.hashes = {p.relative_to(self.root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                       for p in (self.root / "third_party").rglob("*") if p.is_file()}
        self.write("third_party/ARTIFACTS_SHA256.txt", "".join(
            digest + "  " + name + "\n" for name, digest in sorted(self.hashes.items())))

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def verify(self):
        with patch.object(verifier, "verify_license") as license_check, \
                patch.object(verifier, "native_contract") as native_check, \
                patch.object(verifier, "verify_dependency_trust") as dependency_check, \
                contextlib.redirect_stdout(io.StringIO()):
            verifier.verify(self.root)
        license_check.assert_called_once_with(self.root)
        native_check.assert_called_once_with(self.root)
        dependency_check.assert_called_once_with(self.root, lock_name="gradle-fdroid.lockfile")

    def exclude(self, name, reason):
        (self.root / name).unlink()
        self.write("FDROID-SOURCE-MANIFEST.json", json.dumps({"excluded": {
            name: {"sha256": self.hashes[name], "reason": reason}}}))

    def test_checks_the_requested_tree_outside_the_tool_checkout(self):
        self.verify()
        self.write("third_party/component/source.c", "modified source\n")
        with self.assertRaisesRegex(ValueError, "identity missing or changed"):
            self.verify()

    def test_approved_legacy_binary_exclusion_is_accepted(self):
        name = "third_party/component/legacy.jar"
        self.exclude(name, verifier.export_exclusion_reason(Path(name)))
        self.verify()

    def test_export_receipt_cannot_authorize_removing_plaintext_source(self):
        name = "third_party/component/source.c"
        self.exclude(name, "unused source")
        with self.assertRaisesRegex(ValueError, "identity missing or changed"):
            self.verify()

    def test_missing_binary_needs_the_exact_export_reason(self):
        self.exclude("third_party/component/legacy.jar", "unreviewed omission")
        with self.assertRaisesRegex(ValueError, "identity missing or changed"):
            self.verify()

    def test_unregistered_third_party_file_is_rejected(self):
        self.write("third_party/component/unregistered.c", "unreviewed source")
        with self.assertRaisesRegex(ValueError, "Unregistered"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
