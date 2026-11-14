# SQLite capture publication boundaries

The explicit raw rollback-evidence capture now revalidates the original database/journal and the captured copies before publishing its receipt and before returning a successful `Review`. The same captured file identities and content hashes must still match, and neither location may acquire an unsupported WAL/SHM sidecar. Copied files must remain private, unaliased ordinary files. The receipt must identify the inode that was written and forced while staged, remain private and unaliased, and still match its bytes after the publication callback. Its own publication rename may change ctime; subsequent checks use the committed observation including ctime. A same-byte replacement is not accepted as the forced receipt.

Previously, the last source checks preceded receipt staging, forcing and publication. A change during those operations could therefore return a successful review of a pair that no longer matched, or report success for a changed captured copy. This is reachable through the production `MirrorMetadataAuthority.captureSqliteRollbackEvidence` action, not only through a test helper.

A detected change before publication retains the staged receipt and captured bytes without publishing a completed receipt. A detected change after publication can leave a receipt on disk but does not return success. Receipt presence alone still is not a success or recovery authorization. Neither path deletes or rewrites original data or failed evidence. Once an otherwise eligible source pair is stable, retry can create a fresh capture while retaining the failed attempt. Existing capture capacity and interrupted-attempt accounting remain unchanged.

These are bounded observations, not atomic exclusion of arbitrary external or legacy writers. No SQLite engine is opened on the authoritative pair, no rollback is replayed, no candidate is activated and no history is released. WAL recovery, complete cross-runtime crash recovery and capacity reclamation remain separate work. The extra validation scans use the existing 64 MiB per-file bound and existing cancellation checks; this change is not a measured mobile-performance result.

## Verification scope

`MirrorSqliteCapturePublicationTest` covers mutations to each original and copied file, late source WAL/SHM, a late evidence WAL, same-byte copied inode replacement, receipt tampering, same-byte receipt replacement, receipt permission/alias changes, stable success and retry preserving a failed attempt. `MirrorSqliteCaptureAuthorityPublicationTest` reproduces late journal/WAL behavior through the actual metadata authority and verifies a fresh retry without opening authoritative SQLite. Tests operate only on isolated host fixtures.

Regression evidence must identify its production inputs and selected fixtures. Focused host tests do not replace a full source-set build, Android device testing or live crash-recovery validation.
