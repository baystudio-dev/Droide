# Release signing and reproducible APKs

Droide's proposed F-Droid recipe uses the upstream production signature. F-Droid builds from source and publishes the upstream APK only after verifying that it matches the rebuilt APK. Failed verification must stop publication; it must not fall back to a different signing key.

This configuration does not establish F-Droid acceptance. The official application ID is `com.baystudio.droide`. The original production certificate SHA-256 is:

```text
0cc1c5885fea5c5779eb4b3ddcce2a8518b7c207cab7dd7606e94c4d27ccb7ac
```

## Published release and packaging recipe

The GitHub release tag `v1.03.3` and packaging tag `v1.03.3-fdroid` identify the same application source. The signed APK is `Droide-1.03.3-release.apk`, version 1.03.3 (50). [GitHub release](https://github.com/baystudio-dev/Droide/releases/tag/v1.03.3)

For the historical 1.02 release, `v1.02` and `v1.02-fdroid` identify commit `ad75df7763aa6458b0c102818b891e0898af3a58`.

The [current recipe](../../tools/fdroid/com.baystudio.droide.yml) is maintained independently of those immutable tags. Use that recipe for the packaging submission; do not move an existing release tag to update metadata. Its `Binaries` URL identifies each version's signed release, while `AllowedAPKSigningKeys` restricts the accepted certificate. The recipe covers the latest release. Previous releases and their inputs remain available at their original tags.

Local and independent GitHub CI builds of the 1.02 commit produced the same raw unsigned APK. That alone does not prove upstream-signature compatibility. Android Build Tools 36 changed ZIP alignment padding during signing, so the recipe normalizes the rebuilt APK before F-Droid copies and verifies the existing signature. No application entry is replaced with data from the upstream APK.

The normalization tool is fetched by F-Droid as `reproducible-apk-tools@ca728486d42a79f1c9ec0c6ec755c39f251911a8` from its [source-library repository](https://gitlab.com/fdroid/reproducible-apk-tools). It is build tooling, not an application dependency or bundled runtime. The pinned `zipalign.py` SHA-256 is `7f8820cbe8049083d9b7a3088a41fda7bd895bb5ad5e6bb78b135a00021773a9`.

## Verify a rebuild

Build the exact application tag using the [source-build procedure](fdroid.md#build). Obtain the signed APK from the corresponding public release. In a separate host environment, install `fdroidserver==2.4.5`; the verifier also requires Android SDK `apksigner` and Java. Keep these tools outside the source export.

Apply the recipe's normalization to a new file, preserving the original build output:

```sh
python3 /absolute/path/reproducible-apk-tools/zipalign.py \
  --page-size 16 --pad-like-apksigner --replace \
  /absolute/path/app-release-unsigned.apk /absolute/path/normalized-unsigned.apk
```

Use the current repository's verifier with the rebuilt artifact:

```sh
python3 tools/verify_upstream_apk.py \
  --signed-apk /absolute/path/Droide-1.03.3-release.apk \
  --unsigned-apk /absolute/path/normalized-unsigned.apk \
  --apksigner /absolute/path/android-sdk/build-tools/36.0.0/apksigner \
  --version-code 50 \
  --output /absolute/path/new-verification.json
```

The verifier checks the enabled recipe, package/version, allowed certificate, a valid v2/v3 signature, and full byte equality after copying the signature with F-Droid's implementation. A payload or certificate mismatch fails. It never signs an APK, needs no private key, and leaves both inputs unchanged. Run `zipalign -c -P 16 4` on the normalized and signed APKs as an additional alignment check.

Real-artifact regressions require explicit inputs:

```sh
DROIDE_UPSTREAM_SIGNED_APK=/absolute/path/Droide-1.03.3-release.apk \
DROIDE_UPSTREAM_UNSIGNED_APK=/absolute/path/normalized-unsigned.apk \
DROIDE_APKSIGNER=/absolute/path/android-sdk/build-tools/36.0.0/apksigner \
python3 -B -m unittest discover -s tools/tests -p 'test_upstream_apk_verification.py' -v
```

Without those inputs, the real-artifact tests are skipped; policy tests still run. A successful local check does not claim an F-Droid buildserver run.

## Future releases

1. Assign a new application version/code, freeze the tested application source and publish new tags. Never overwrite an existing release or tag to repair a packaging recipe.
2. Build unsigned, normalize with the pinned tool and verify alignment. Preserve the raw and normalized unsigned hashes separately.
3. Sign the normalized APK in the private release environment with the original production key. With Build Tools 36, use `--alignment-preserved true` so signing preserves the canonical padding. Keep v2/v3 signing enabled. Do not commit passwords, private keys or private signing configuration.
4. Verify the certificate, reconstruct the signed APK from an independent rebuild, and require the full hash comparison to pass before publication. Signing success alone is insufficient.
5. Publish `Droide-<version>-release.apk` under `v<version>`, matching the recipe's URL pattern. Keep the APK, source, public certificate, checksums and verification evidence together. Submit the tested recipe and retain the original signing identity across channels.

If a toolchain update changes the required normalization, update and test the recipe explicitly. Do not bypass comparison, remove the certificate allowlist or silently publish a differently signed fallback.

## Android developer verification

Reproducibility and Google developer registration are separate requirements. For ordinary distribution on certified Android devices where verification applies, the account owner must verify their identity and register `com.baystudio.droide` with the production signing key. Use an existing Play Console account when distributing on Play, or Android Developer Console for distribution only outside Play. Follow the console's ownership challenge; a public certificate fingerprint by itself does not prove possession of the key.

The account's legal identity must be accurate. BayStudio may remain the product's developer label; choose an individual or organization account according to the actual legal entity. The console may request identity documents or a temporary signed ownership-proof APK. Those steps belong to the account owner and the private signing environment. A registration receipt must be checked before claiming completion. No private key is required by F-Droid.

Google currently documents a phased rollout, expansion in 2027, and advanced-flow/ADB paths for unregistered applications. Check the [current verification guide](https://developer.android.com/developer-verification/guides) and [FAQ](https://developer.android.com/developer-verification/guides/faq) before registration; this repository does not establish that the owner or package has been verified.

## References

- [F-Droid upstream-signed publication](https://f-droid.org/en/docs/Reproducible_Builds/#exclusively-publishing-upstream-developer-signed-apks)
- [F-Droid metadata fields](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [Build Tools alignment compatibility](https://github.com/obfusk/apksigcopier/issues/105#issuecomment-2206799316)
