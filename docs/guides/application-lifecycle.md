# Startup, recovery and Exit

[DroideApplication](../../app/src/main/java/com/baystudio/droide/DroideApplication.kt) owns the retained workspace and shutdown sequence. Activities and Compose observers present that state; they do not independently own a replacement runtime.

## Startup

Startup admission, environment readiness, editor restoration and visible workspace readiness are separate observations. Deferring Ubuntu installation keeps editing available. A completed environment is reused; initialization must not reinstall packages on every launch.

Abnormal prior exits can defer automatic editor reopening while preserving recovery data. File loading must reject binary/NUL-bearing input before native text adoption, preserve the prior document on rejection, and ignore stale completions after the document/workspace changes. Platform callback guards cannot catch native process aborts, memory kills or every ANR.

When investigating a startup failure, record the exact APK/version, Android version, last visible stage and affected file type. Distinguish Java exceptions, native tombstones, ANRs and OS memory kills before changing editor dependencies. Do not delete recovery data as a diagnostic shortcut.

## Explicit Exit

Exit closes new-work admission, starts independent shutdown owners, waits for admitted saves, checkpoints editor/chat drafts, then stops owned runtimes, transfers, notifications and CPU leases. A verified checkpoint can be reused when its exact content/version still matches; new native edits require another durable checkpoint. Recovery that has not loaded must remain preserved.

Successful shutdown immediately finishes/removes the task and terminates the Droide process. There is no mandatory wait or animation delay. [ApplicationExitDeadline](../../app/src/main/java/com/baystudio/droide/core/ApplicationExitDeadline.kt) independently enforces a **15-second** limit, including when Main or a cleanup owner stalls. Repeated Exit/Retry cannot extend that deadline. Android task/service closure is requested separately so blocked IPC cannot prevent the process deadline.

Forced termination is not a successful-save receipt. If the deadline wins, only already persisted work is guaranteed available; a late in-memory edit may not have reached recovery storage. Startup recovery must retain and inspect existing data rather than report unfinished shutdown as complete. Tests cover the deadline independently from successful preservation and owned-process cleanup.

## Control scopes

| Control | Scope |
| --- | --- |
| Navigate or collapse a panel | Change presentation; retain the session |
| Interrupt terminal foreground work | Send the supported interrupt to that owned session |
| Stop agent/task | Cancel and settle that task's owned operations |
| Close terminal tab | Retire the selected terminal owner; preserve/report uncertain cleanup |
| Stop detached job | Stop the explicitly selected background owner |
| Exit | Close application admission and all captured application owners |

Never infer cleanup from an empty screen, a missing prompt, an elapsed timer or a reused PID. Keep exact ownership and cleanup receipts. A timed-out operation must not be blindly replayed on restart.

See [testing](../TESTING.md) for host/device boundaries and [workbench actions](workbench-actions.md) for shared command admission.
