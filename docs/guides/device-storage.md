# Device storage

Device storage extends access to Android shared files without relocating private projects. The editor, repository, recovery records, CLI review copies, toolchains and caches keep their existing ownership and paths.

## Build and enable

The capability is explicit and independent of the distribution setting:

```
-PDROIDE_DEVICE_STORAGE=true
```

Use it with the existing current-source debug/release lanes. The source-only build helper accepts `--device-storage`. Default builds do not request `MANAGE_EXTERNAL_STORAGE`. Device-storage source debug builds preserve the existing debug metadata overlay. The minimum SDK remains 29 and the target SDK remains 36.

On Android 11 or newer, select **Settings → Files & Workspace → User → Access shared files**, then use **Android file access** to grant access. The preference and Android grant are separate. On Android 10, use the existing file picker, linked folders, import and export; no legacy-target workaround is used. Terminal **Commands** includes step-by-step guidance for both storage options.

## Ownership and launch behavior

`StorageAccessController` observes the explicit preference, Android grant and available `StorageVolume` metadata. It does not enumerate file trees, install anything, start a service or poll in the background. Generations change when observed access or volume metadata changes. During an explicit transfer, access observations are throttled to 250 ms; operating-system failures still terminate I/O immediately.

`ExternalMountPlan` adds validated PRoot arguments to new user terminal/task and external CLI launches. Primary storage uses its Android-provided path and the `/sdcard` guest alias; removable volumes retain their actual paths. It never binds `/data`, changes the shell, HOME, PATH or initial cwd, or restarts existing sessions. Restricted analysis, health and certification launch routes stay private by default. `TerminalLaunchSpec.storageAccess` and execution diagnostics retain the actual launch snapshot.

After changing access, open a new terminal or reconnect the CLI. Disabling the preference closes new admission; it cannot unmount a running process or revoke an already-open descriptor. Stop those sessions or use Android settings to revoke permission. PRoot runs under the app UID; it is not a separate kernel sandbox. Other apps' private data and Android-restricted directories remain protected by Android.

## Agent tools

The native `storage` and external `droide_storage` adapters use the same `StorageOperationService`. Legacy workspace paths remain confined to the project. Discovery returns a compact schema and status on demand, not a phone-wide file index.

| Operation | Behavior |
| --- | --- |
| status / locations | Report build support, user choice, Android grant, generation and available volumes |
| list | Immediate children only, capped at 150 entries; explicit truncation |
| stat | Type, size and content version; regular-file versions stream SHA-256 |
| read | UTF-8 files up to 64 KiB; output pages up to 4,000 characters |
| write | New text file only, up to 64 KiB; never replaces an existing destination |
| mkdir | Create one directory; reports namespace durability separately |
| copy / export | Stream an exact regular file to a new shared destination, up to 4 GiB |
| import | Stream into a new project file through the existing repository and document guard |
| move | Rename without replacement within one volume; requires the observed version |
| delete | Remove a regular file to an adjacent retained recovery name; requires its version |

The external bridge retains its 4,096-byte request limit and accepts at most 1,500 content characters. Its terminal remains available for larger writes, overwrites, directory operations and cross-volume workflows. Tool limits are not extension whitelists or restrictions on the authorized terminal.

The existing permission engine checks concrete targets before reusable volume-scoped session approval. DENY always wins. Changing storage generation invalidates reuse, and closing a bridge clears its grants. Read-only agent modes cannot mutate shared files. Arbitrary shell commands retain the existing shell and ambiguous-command permission gates; no CLI skip-approval flags are added.

Shared-storage changes are direct. They do not enter project review, project Undo or an implicit SAF synchronization. Import/export explicitly uses the saved project repository. A changed linked folder remains subject to the existing conflict checks on its next synchronization.

CLI import results identify the saved editor workspace as their target. An active CLI review copy is not overwritten mid-turn; the next mirror refresh brings in imported files through the existing conflict-aware merge. Import continues to respect unsaved-document admission. Status/locations refresh after approval and refuse to complete for a closed owner.

## Transfers and failure handling

File access uses existing anchored directory descriptors and rejects symlink escapes. Structured writes use a sibling staging name, flush contents, then publish without replacing another file. If interrupted, the final destination is not silently retried and the `.droide-transfer-*` stage remains available for inspection. A published file whose directory sync is unsupported reports that separately.

Deletion retains `.droide-removed-*` data rather than claiming that it freed space. Review that copy before explicitly purging it. Cross-volume move has no implicit copy-and-delete fallback. Read-only media, unmounted media, changed sources, lost permission and cancellation do not become empty successful results.

Move pins the observed source, checks its current path identity immediately before rename, and verifies the destination afterward. If a concurrent writer changes the entry, it reports that review is required and does not attempt a destructive rollback. These checks do not lock other applications out of shared storage.

Shared Android storage does not guarantee POSIX permissions, executable files, symlinks or locking. Use the private project/workstation for native builds and install runtimes on demand as before.

## Android Files integration

The separate **Show project in Android Files** switch publishes one project through a read-only `DocumentsProvider`. Android grants access through its picker. Opaque document IDs are bound to persisted project directory identity; unsharing and resharing produces new IDs. No runtime, workspace restoration, foreground service or Ubuntu setup is started by provider queries.

Only saved file bytes are exposed. Internal state, sensitive paths and symlinks are excluded. There is no writable private-project descriptor. Reads use anchored inputs and reliable pipes with cancellation/error reporting; clients needing seekable files should use Explorer export. A maximum of two simultaneous reads and 2,000 entries per listing keeps requests bounded. Existing export handles larger folder transfers.

## Validation

Focused tests live in `app/src/test/java/com/baystudio/droide/core/storage`. They cover mount scope, preservation of cwd/environment, unavailable grants, path escape, publication conflicts, exact binary transfer, version checks, read-only modes, cancellation of authority, session grants and shared-document identity. Existing Run/compiler and terminal-launch regressions must continue to pass.

Device acceptance additionally requires Android all-files grant/revoke, a secondary Android user, available removable media, physical Files-app copy-out, existing versus newly opened PTY/CLI sessions, and foreground/background Exit. Host JVM tests do not establish those device results. Do not change release/signing identity or publish a build merely because these tests pass.
