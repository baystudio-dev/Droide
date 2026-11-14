# Workbench action contracts

## Run, build, and debug

The main Run button selects one target from the active file and detected project:

| Target | Behavior | Device bridge required |
| --- | --- | --- |
| HTML or Markdown | Open the corresponding preview | No |
| Supported runnable source file, including Kotlin and Java inside an Android project | Execute the current file with its language compiler/runtime in the project's local environment | No |
| Android manifest/build definition, or no active file in an Android project | Save pending buffers, build the debug APK, request installation on this device, then launch after successful installation | No |
| Run Android app on this device | Explicitly build and install the Android application, regardless of the selected file | No |
| Run on connected device | Build and deploy to the explicitly connected Android target | Yes |
| Android debugging or JDWP attach | Start through the shared execution coordinator and debugger configuration | Yes |

A build, successful installation, and launch are separate outcomes. A failed build must never install a stale APK. Multiple produced APKs require an explicit application-module task; Droide does not select an arbitrary split or module. Release packaging remains available through its separate APK and bundle commands.

Source-file Run never changes into an APK build because the parent folder contains Gradle files. Kotlin uses `kotlinc` followed by `java -jar`; Java uses the JDK source launcher. Missing compilers or compilation errors remain file-run errors. Android source requiring the Android SDK and project dependencies must use **Run Android app on this device** or `/android-run-local`. Target selection does not scan source text for an entry point.

`BuildRunDebugCoordinator` owns execution admission, foreground work, cancellation and the selected task. Android attach uses the same prepared-configuration path as debugger Start, including configured pre-launch tasks. UI handlers capture the selected source before a suspending save operation.

## Installation on this device

`AndroidLocalAppInstaller` stages the current build artifact through the existing bounded APK snapshot policy. It streams the sealed snapshot into an Android `PackageInstaller` session off the main thread and hands that session to `AndroidAppInstallActivity`.

The installer activity owns the permission and confirmation flow across rotation. It validates the session, target package and current application-work epoch. Installation requires Android's confirmation; Droide does not silently install packages. If Android has not granted install access, Run opens the per-app **Install unknown apps** permission screen. Denial, cancellation or failed installation must not launch an older installed version.

The manifest declares `REQUEST_INSTALL_PACKAGES`. This is Android special access, requested only when the user runs an Android app; it is separate from notification and Nearby devices runtime permissions. Launcher-intent visibility is limited to querying launchable activities; the application does not request `QUERY_ALL_PACKAGES` or broad storage access.

A session result uses an explicit mutable `PendingIntent`, as required by current Android installer APIs. Results for a retired application epoch cannot restart work after Exit. Unfinished sessions are abandoned when the user cancels the installer activity; a failed pre-handoff copy abandons its session and preserves the original failure.

## File changes and continuation

Explorer Move and Rename observe the repository's actual local outcome, including failures after publication. A verified completed rename remaps the selected path and editor before showing a synchronization warning. An uncertain outcome refreshes the tree and asks for review without guessing the new identity. A failure before publication leaves the original dialog available for correction. Dirty-buffer preparation and confined destination validation remain mandatory.

Rename preserves selection and focus. Move retains its existing destination selection and validated undo/redo tokens. Neither operation overwrites unrelated files to make recovery appear successful.

## Quick Fix ownership

The editor and Problems panel allow only one resolution for the selected action at a time. Closing or replacing a request invalidates its generation and cancels its work. A late reply cannot replace a newer list or apply an edit after dismissal. Language-server document and activation guards remain additional requirements.

Different code actions may have identical titles, kinds and preferred flags. Those presentation fields are not unique list identities. Duplicate titles must remain visible without crashing the action list.

## Other action boundaries

- Git read commands share the panel busy state and expose admission failures. Initial refresh cannot silently overwrite a newer user action. Closing an export conflict does not modify the worktree.
- Browser actions observe a pending JavaScript dialog before waiting for page presentation. They return the blocked-operation state promptly. Actual clicks and captures still require the current visible, measured browser surface; accepting a dialog does not replay an interrupted action.
- Terminal interrupt, close, detached work and application Exit retain distinct owners and cleanup behavior. A panel's visibility is not process lifetime.
- Extension installation, agent approvals, export destinations and task cancellation continue through their existing controllers. Menu labels and enabled states must match the operation actually dispatched.

## Verification and device checks

Regression coverage includes main Run dispatch, installer result ownership, rotation and denial, Explorer rename continuation, stale Quick Fix replies, duplicate actions, Git export conflicts, browser dialogs, editor gestures, terminal interaction, agent panels, settings and responsive layout.

`tools/verify_terminal_workstation.py` runs the terminal lifecycle suites through the real Gradle application classpath. The obsolete `--jars` argument is accepted for migration but no longer builds incomplete source fragments. Host-only environments may pass `--gradle`, `--test-task`, `--init-script` and repeated `--gradle-arg` values; all production compile and dependency-verification dependencies remain enabled.

Host tests do not certify Android's OEM installer or native WebView/PTY behavior. Before packaging this change, verify on a physical device: first install, update, denied install access, cancel confirmation, signature mismatch, insufficient space, rotation and Exit during the installer flow. Check portrait and landscape Run with no device bridge connected, plus the explicit connected-device/debug commands with an authorized target.
