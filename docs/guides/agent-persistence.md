# Agent edits and external session recovery

## Persisting editor changes

Completed ordinary writes/edits, live source writes, patch batches and supported LSP edits request persistence. Buffer replacement primitives remain buffer-only; rollback and cancellation do not initiate Save. New/unopened files use the existing disk write route.

`WorkspaceDocumentSaveRequest` identifies an exact document instance, revision and change version. Automatic requests carry before/after snapshots. Previously unsaved user text requires explicit reviewed persistence. `save_files` accepts a bounded set of path/version tuples under existing edit permission and mode checks; it is not an unrestricted workspace Save All operation.

`EditorSaveCoordinator` serializes saves through the retained editor owner. It validates document/native ownership and disk versions, stages bytes, and rechecks publication authority. A clean-buffer no-op still verifies the saved disk version. Later typing stays dirty; replacing the editor cannot redirect an admitted save.

An edit enters Undo history before persistence. Save failure does not replay or roll back an already applied edit. Pending saves are retained and checked before new commands or disk mutations; status, input and cancellation for an existing command remain available. Synchronization errors are reported separately from successful local persistence.

## External CLI workspaces

An external chat session can edit an application-owned review copy. Permission to run a CLI and approval to publish its reviewed changes have different scopes. Publication reconciles canonical disk/editor state and preserves both sides of uncertain conflicts. Terminal-only CLI sessions have a separate lifecycle and normally write persisted bytes directly.

The message that earlier CLI cleanup is unconfirmed refers to retained process custody, not a model-provider error. A retry, missing PID or application restart is not proof that the old writer stopped. Do not erase `.droide`, retained working copies or ownership rows to bypass recovery.

Recovery uses exact cleanup receipts, unentered launch reservations or comparable device reboot evidence. Android's boot counter can supply identity when the kernel boot UUID is unavailable; the two identity domains are not interchangeable. An observation of stranded owners permits no launch on its own. A later comparable reboot can release only the exact observed owners.

When the UI explicitly requests a device restart, reopen the affected project after restarting Android and retry. Restarting only Droide does not establish that old processes stopped. Missing, changed or untrusted identity remains a diagnostic requiring review, not permission to discard retained work.

## Verification

Maintain regressions for dirty-buffer preservation, stale save/version rejection, partial multi-file persistence, exact finish receipts, cancellation, boot-identity fallback and retained external writers. Test real external CLI reconnect/publication and device process death separately from host protocol fixtures. See [architecture](../ARCHITECTURE.md) and [agent workflows](agent-workflows.md).
