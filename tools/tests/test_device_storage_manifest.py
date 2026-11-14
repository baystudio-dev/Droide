import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("storage_manifest", Path(__file__).resolve().parents[1] / "verify_device_storage_manifest.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DeviceStorageManifestTest(unittest.TestCase):
    def check_manifest(self, enabled, permission, config_enabled, protected=True, debug=False):
        with tempfile.TemporaryDirectory() as directory:
            manifest, config = Path(directory) / "AndroidManifest.xml", Path(directory) / "BuildConfig.java"
            manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/>
                ''' + ('<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/>' if permission else '') + '''
                <application><provider android:name="com.baystudio.droide.core.storage.DroideDocumentsProvider"
                    android:permission="''' + ('android.permission.MANAGE_DOCUMENTS' if protected else '') + '''"
                    android:grantUriPermissions="true" android:exported="true"/></application></manifest>''')
            config.write_text("public static final boolean DEVICE_STORAGE = " + str(config_enabled).lower() + ";")
            return MODULE.verify(manifest, config, enabled, debug)

    def test_default_build_excludes_permission_and_opt_in_build_includes_it(self):
        self.assertFalse(self.check_manifest(False, False, False)["deviceStorage"])
        self.assertTrue(self.check_manifest(True, True, True)["deviceStorage"])

    def test_configuration_manifest_disagreement_is_rejected(self):
        for enabled, permission, config in ((False, True, False), (True, False, True), (True, True, False)):
            with self.subTest(enabled=enabled, permission=permission, config=config), self.assertRaises(ValueError):
                self.check_manifest(enabled, permission, config)

    def test_unprotected_provider_and_missing_debug_metadata_are_rejected(self):
        with self.assertRaises(ValueError):
            self.check_manifest(True, True, True, protected=False)
        with self.assertRaises(ValueError):
            self.check_manifest(True, True, True, debug=True)


if __name__ == "__main__":
    unittest.main()
