# Repository tooling

## Maintained entry points

| Tool | Purpose |
| --- | --- |
| `export_fdroid_source.py` | Export a separate source distribution without modifying the working tree |
| `build_fdroid_candidate.py` | Build source components and the F-Droid debug/release APK |
| `verify_fdroid_inputs.py` | Check complete-source and distribution inputs |
| `verify_upstream_apk.py` | Verify the recipe's production signer and reproduce the complete upstream APK from a rebuild |
| `verify_repository_docs.py` | Check local targets in maintained guides and export templates |
| `verify_dependency_policy.py`, `verify_dependency_trust.py` | Dependency mapping, locks and checksum admission |
| `verify_terminal_workstation.py` | Focused terminal lifecycle tests on the Gradle production classpath |
| `verify_browser_actions.py` | Production DOM actions against local Chromium fixtures |
| `verify_release_source.sh` | Regular distribution source gate; not the F-Droid export gate |
| `generate_legal_browser_catalog.py` | Packaged legal browser inventory |

Use [development](../docs/DEVELOPMENT.md), [testing](../docs/TESTING.md) and the [F-Droid procedure](../docs/distribution/fdroid.md) before selecting a build mode. `build-readiness/` contains required source tooling; it is not generated build output.

## Retained validation contracts

The `current-*`, `private-*`, versioned native/terminal recipes and corresponding tests/manifests remain because `app/build.gradle.kts` or other verified consumers still reference them. Their exact source/tool hashes define frozen validation modes. A filename containing “current” does not make such a receipt applicable to an arbitrary later working tree.

Do not use a historical receipt to admit new APKs, edit its hashes to bypass validation, or delete its fixtures without retiring all consumers together. The maintained source build wrappers are the entry points for new artifacts. Host test overlays and stubs are test-only and must not enter a distributed APK.

Local compiler output, logs and caches belong outside source exports. Upstream corresponding source, license text, build scripts and regression fixtures remain source inputs even when their directory names contain `build` or an older version identifier.
