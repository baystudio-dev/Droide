#!/usr/bin/env python3
"""Verify storage capabilities in an AGP merged manifest and generated BuildConfig."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"


def verify(manifest: Path, config: Path, enabled: bool, source_debug: bool = False):
    root = ET.parse(manifest).getroot()
    sdk = root.find("uses-sdk")
    if sdk is None or sdk.get(A + "minSdkVersion") != "29" or int(sdk.get(A + "targetSdkVersion", "0")) < 36:
        raise ValueError("Storage changes must preserve minSdk 29 and targetSdk >= 36")
    permissions = [element.get(A + "name") for element in root.findall("uses-permission")]
    count = permissions.count("android.permission.MANAGE_EXTERNAL_STORAGE")
    if count != int(enabled):
        raise ValueError("Merged all-files permission does not match the selected build capability")
    if "android.permission.WRITE_EXTERNAL_STORAGE" in permissions:
        raise ValueError("Legacy storage permission is not part of this capability")
    match = re.search(r"boolean\s+DEVICE_STORAGE\s*=\s*(true|false)\s*;", config.read_text())
    if match is None or (match[1] == "true") != enabled:
        raise ValueError("BuildConfig and manifest storage capability disagree")
    application = root.find("application")
    if application is None or application.get(A + "requestLegacyExternalStorage") == "true":
        raise ValueError("Missing application or unsupported legacy-storage workaround")
    providers = [node for node in application.findall("provider")
                 if node.get(A + "name", "").endswith(".storage.DroideDocumentsProvider")]
    if len(providers) != 1:
        raise ValueError("Expected exactly one project DocumentsProvider")
    provider = providers[0]
    if (provider.get(A + "permission") != "android.permission.MANAGE_DOCUMENTS"
            or provider.get(A + "grantUriPermissions") != "true"
            or provider.get(A + "exported") != "true"):
        raise ValueError("Project sharing must retain Android document-picker permission control")
    if source_debug and not any(node.get(A + "name") == "com.baystudio.droide.SOURCE_DEBUG_STATUS"
                                for node in application.findall("meta-data")):
        raise ValueError("Source debug metadata overlay was lost")
    return {"deviceStorage": enabled, "minSdk": 29, "targetSdk": int(sdk.get(A + "targetSdkVersion")),
            "documentProviderProtected": True, "manifest": str(manifest), "buildConfig": str(config)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--build-config", type=Path, required=True)
    parser.add_argument("--device-storage", action="store_true")
    parser.add_argument("--source-debug", action="store_true")
    args = parser.parse_args()
    print(json.dumps(verify(args.manifest, args.build_config, args.device_storage, args.source_debug), indent=2))


if __name__ == "__main__":
    main()
