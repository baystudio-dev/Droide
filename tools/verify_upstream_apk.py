#!/usr/bin/env python3
"""Verify an upstream APK against the recipe and a locally rebuilt unsigned APK."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def publication_policy(app, version_code):
    builds = [build for build in app["Builds"] if build["versionCode"] == version_code]
    if len(builds) != 1 or builds[0].get("disable"):
        raise ValueError("Select exactly one enabled versionCode from the packaging recipe")
    build = builds[0]
    keys = app.get("AllowedAPKSigningKeys", [])
    if not keys or any(not re.fullmatch(r"[0-9a-f]{64}", key) for key in keys):
        raise ValueError("The recipe must declare valid AllowedAPKSigningKeys")
    template = build.get("binary") or app.get("Binaries", "")
    if not template or not any(marker in template for marker in ("%v", "%c")):
        raise ValueError("The recipe must declare a versioned upstream APK URL")
    url = template.replace("%v", build["versionName"]).replace("%c", str(version_code))
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("The upstream APK URL must use HTTPS without credentials")
    return build, keys, url


def verified_signer(apksigner, apk):
    result = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                            capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise ValueError("APK signature verification failed: " + result.stderr.strip())
    keys = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                      result.stdout, re.MULTILINE)
    if len(keys) != 1:
        raise ValueError("Expected exactly one APK signer")
    if not re.search(r"^Verified using v[23](?:\.\d+)? scheme .*: true$", result.stdout, re.MULTILINE):
        raise ValueError("A v2 or v3 signature is required to verify complete APK bytes")
    return keys[0].lower()


def verify(signed_apk, unsigned_apk, metadata_path, version_code, apksigner):
    from fdroidserver import apksigcopier, common, metadata

    app = metadata.parse_metadata(metadata_path)
    build, keys, url = publication_policy(app, version_code)
    signed_hash, unsigned_hash = sha256(signed_apk), sha256(unsigned_apk)
    signer = verified_signer(apksigner, signed_apk)
    if signer not in keys:
        raise ValueError("The APK signer is not in AllowedAPKSigningKeys")
    expected = (app.id, version_code, build["versionName"])
    for apk in (signed_apk, unsigned_apk):
        if common.get_apk_id(str(apk)) != expected:
            raise ValueError("APK package/version does not match the selected recipe")
    if (apksigcopier.extract_v2_sig(str(unsigned_apk), expected=False) is not None or
            list(apksigcopier.extract_meta(str(unsigned_apk)))):
        raise ValueError("The rebuild must be unsigned")
    with tempfile.TemporaryDirectory(prefix="droide-upstream-verify-") as temporary:
        replica = Path(temporary) / "signature-copied.apk"
        apksigcopier.do_copy(str(signed_apk), str(unsigned_apk), str(replica),
                             v1_only=None, exclude=apksigcopier.exclude_meta)
        if verified_signer(apksigner, replica) != signer or sha256(replica) != signed_hash:
            raise ValueError("Rebuilt APK with copied signature is not byte-identical to upstream")
    if sha256(signed_apk) != signed_hash or sha256(unsigned_apk) != unsigned_hash:
        raise ValueError("An input APK changed during verification")
    return {"schema": 1, "packageName": app.id, "versionName": build["versionName"],
            "versionCode": version_code, "upstreamUrl": url, "certificateSha256": signer,
            "signedApkSha256": signed_hash, "unsignedApkSha256": unsigned_hash,
            "metadataSha256": sha256(metadata_path),
            "fdroidserverVersion": importlib.metadata.version("fdroidserver"),
            "method": "fdroidserver.apksigcopier + apksigner + complete SHA-256 comparison",
            "signatureVerified": True, "byteIdenticalToUpstream": True,
            "fdroidBuildserverValidated": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signed-apk", type=Path, required=True)
    parser.add_argument("--unsigned-apk", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--metadata", type=Path, default=ROOT / "tools/fdroid/com.baystudio.droide.yml")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output and args.output.exists():
        parser.error("Use a new output path; existing verification evidence is preserved")
    try:
        receipt = verify(args.signed_apk.resolve(), args.unsigned_apk.resolve(),
                         args.metadata.resolve(), args.version_code, args.apksigner.resolve())
    except ImportError as error:
        raise SystemExit("Install fdroidserver==2.4.5 in a separate verification environment") from error
    result = json.dumps(receipt, indent=2) + "\n"
    if args.output:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(result)
    print(result, end="")


if __name__ == "__main__":
    main()
