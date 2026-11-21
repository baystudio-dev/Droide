"""Signing-policy regressions and optional checks against real release APKs."""
import copy
import os
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_upstream_apk import publication_policy, verify


class UpstreamPublicationPolicyTests(unittest.TestCase):
    def setUp(self):
        self.app = {"Builds": [{"versionCode": 46, "versionName": "1.02"}],
                    "Binaries": "https://example.org/releases/%v/app-%c.apk",
                    "AllowedAPKSigningKeys": ["a" * 64]}

    def test_missing_binary_url_cannot_fall_back_to_distribution_signing(self):
        del self.app["Binaries"]
        with self.assertRaisesRegex(ValueError, "versioned upstream"):
            publication_policy(self.app, 46)

    def test_unpinned_signer_is_rejected(self):
        self.app["AllowedAPKSigningKeys"] = []
        with self.assertRaisesRegex(ValueError, "AllowedAPKSigningKeys"):
            publication_policy(self.app, 46)

    def test_disabled_historical_build_is_rejected(self):
        self.app["Builds"][0]["disable"] = "Superseded"
        with self.assertRaisesRegex(ValueError, "enabled versionCode"):
            publication_policy(self.app, 46)

    def test_ambiguous_build_identity_is_rejected(self):
        self.app["Builds"].append(copy.deepcopy(self.app["Builds"][0]))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            publication_policy(self.app, 46)

    def test_nonversioned_or_insecure_reference_is_rejected(self):
        for url in ("https://example.org/latest.apk", "http://example.org/%v.apk",
                    "https://user:password@example.org/%v.apk"):
            with self.subTest(url=url):
                self.app["Binaries"] = url
                with self.assertRaises(ValueError):
                    publication_policy(self.app, 46)


class UpstreamApkVerificationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        names = ("DROIDE_UPSTREAM_SIGNED_APK", "DROIDE_UPSTREAM_UNSIGNED_APK", "DROIDE_APKSIGNER")
        if not all(os.environ.get(name) for name in names):
            raise unittest.SkipTest("Set real signed/normalized unsigned APKs and apksigner paths")
        cls.signed, cls.unsigned, cls.apksigner = (Path(os.environ[name]) for name in names)
        cls.metadata = Path(__file__).resolve().parents[1] / "fdroid/com.baystudio.droide.yml"
        from fdroidserver import metadata
        current = metadata.parse_metadata(cls.metadata)["CurrentVersionCode"]
        cls.version_code = int(os.environ.get("DROIDE_UPSTREAM_VERSION_CODE", current))

    def test_real_rebuild_preserves_production_signature_and_every_byte(self):
        receipt = verify(self.signed, self.unsigned, self.metadata, self.version_code, self.apksigner)
        self.assertTrue(receipt["byteIdenticalToUpstream"])

    def test_changed_rebuild_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / "changed.apk"
            shutil.copy2(self.unsigned, changed)
            with zipfile.ZipFile(changed) as archive:
                entry = archive.getinfo("classes.dex")
            with changed.open("r+b") as stream:
                stream.seek(entry.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", stream.read(4))
                stream.seek(entry.header_offset + 30 + name_size + extra_size + 100)
                original = stream.read(1)
                stream.seek(-1, 1)
                stream.write(bytes([original[0] ^ 1]))
            with self.assertRaisesRegex(ValueError, "signature verification failed"):
                verify(self.signed, changed, self.metadata, self.version_code, self.apksigner)

    def test_different_certificate_allowlist_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / self.metadata.name
            changed.write_text(self.metadata.read_text().replace(
                "0cc1c5885fea5c5779eb4b3ddcce2a8518b7c207cab7dd7606e94c4d27ccb7ac", "a" * 64))
            with self.assertRaisesRegex(ValueError, "not in AllowedAPKSigningKeys"):
                verify(self.signed, self.unsigned, changed, self.version_code, self.apksigner)


if __name__ == "__main__":
    unittest.main()
