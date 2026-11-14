# Verification records

A result applies to the exact source, selected tests, toolchain and artifact it measured. Host compilation, APK assembly and physical-device execution are separate evidence. The current [testing guide](../TESTING.md) defines how to record them.

## Scoped application records

- [Workbench action audit](workbench-actions-20261114/AUDIT.md), [results](workbench-actions-20261114/RESULTS.json) and [control inventory](workbench-actions-20261114/control-index.csv).
- [Release legal audit](release-legal-20261110/AUDIT.md) and its adjacent source inventories. These establish the recorded notice review; later dependency changes need another review.

## Required provenance

The following frozen records are cited by packaged third-party notices and must remain available:

- [Batch grammar research](r106-batch/research.json)
- [INI grammar research](r107-ini/research.json)
- [Explorer export dependency provenance](r120-explorer-export/dependency-provenance.json)

Branding and third-party source manifests also retain their own identities. Do not rewrite historical hashes to claim they validate modified inputs. Raw test logs, temporary harness outputs, backup archives and development handoffs belong outside the application source.
