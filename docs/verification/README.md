# Verification records

A result applies to the exact source, selected tests, toolchain and artifact it measured. Host compilation, APK assembly and physical-device execution are separate evidence. The current [testing guide](../TESTING.md) defines how to record them.

## License inventories

The notice review retains the [embedded notices](release-legal-20261110/embedded-notice-sources.json), [upstream license sources](release-legal-20261110/upstream-license-sources.json) and [integrity inventory](release-legal-20261110/integrity-inventory-refresh.json). Recheck the affected entries when a dependency changes.

## Required provenance

The following frozen records are cited by packaged third-party notices and must remain available:

- [Batch grammar research](r106-batch/research.json)
- [INI grammar research](r107-ini/research.json)
- [Explorer export dependency provenance](r120-explorer-export/dependency-provenance.json)

Branding and third-party source manifests also retain their own identities. Do not rewrite historical hashes to claim they validate modified inputs. Raw test logs, temporary harness outputs, backup archives and development handoffs belong outside the application source.
