# Development and build lanes

Build inputs are pinned in Gradle, dependency locks, verification metadata and source-component manifests. Do not update a hash solely to bypass a rejected dependency or native artifact.

## Choose a lane

| Lane | Entry point | Boundary |
| --- | --- | --- |
| F-Droid source distribution | [Export and build](distribution/fdroid.md) | Plaintext component sources, trusted repositories, separate dependency lock and unsigned release |
| Regular source release | [Release build](../tools/source-release.md) | Regular distribution's native/provider inputs and optional private signing |
| Regular source debug | [Debug build](../tools/source-debug.md) | Debug certificate; not a production update |
| Focused host tests | [Testing](TESTING.md) | JVM/Compose fixtures; no installable APK or native-device certification |
| Frozen/private validation modes | [Tooling index](../tools/README.md) | Exact historical inputs only; not a shortcut around current build checks |

The F-Droid source exporter installs its own settings, README and CI workflow in a new directory. Build there. Do not combine `DROIDE_FDROID_BUILD` with the regular binary-backed recipe or reuse receipts from a different lane. The exported source intentionally has no Gradle wrapper JAR; provide Gradle externally.

The F-Droid recipe explicitly opts into [device storage](guides/device-storage.md) with `--device-storage`. This adds the Android all-files capability while keeping user activation off by default. Other current-source builds can opt in with `-PDROIDE_DEVICE_STORAGE=true`; omitting it preserves the default permission set. The build helper verifies the resulting merged manifest and BuildConfig.

## Toolchains

Current Android builds use JDK 17, Gradle 8.13, Android platform 36, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1. The F-Droid builders also require Python 3.11+, make and patch. Follow the selected lane's exact compiler/source manifest checks. Host tests can require a separate JVM runtime from the application compiler.

Tools used to compile Droide are distinct from toolchains users install inside its Ubuntu workstation. Never clean user projects, installed packages or recovery stores as part of repository build cleanup.

## Fast source checks

Run from the source root with Python 3.11 or newer:

```sh
python3 -B tools/verify_repository_docs.py
python3 -B -m unittest discover -s tools/tests -p 'test_fdroid_source_export.py' -v
python3 -B -m unittest discover -s tools/tests -p 'test_fdroid_input_integrity.py' -v
python3 -B -m unittest discover -s tools/tests -p 'test_repository_docs.py' -v
python3 -B -m unittest discover -s tools/tests -p 'test_dependency_trust.py' -v
```

For an exported F-Droid tree also run `python3 -B tools/verify_fdroid_inputs.py --root "$PWD"`; it checks the F-Droid lock against dependency-verification metadata. In the complete working tree, `python3 -B tools/verify_dependency_policy.py` checks the regular distribution's repository and binary-compatibility mappings. That regular policy and `verify_release_source.sh` expect regular bundled artifacts; they are not interchangeable with the F-Droid gate. Do not add a local binary repository to an F-Droid export to satisfy the wrong lane's verifier.

## Outputs and updates

Put source-product directories, release evidence and signing material outside the working source. Reuse products only when their verifier accepts the exact source/toolchain identity; preserve failed outputs for diagnosis. Record APK hashes and matching mapping files with build results.

Before publishing an update, assign a new version code/tag, build the selected lane, run its gates and test the resulting APK on Android. Do not retag an existing release or transfer an older APK's device result to a modified source. Private keys/passwords never belong in a source archive or public CI artifact.
