# Workbench action audit

Scope: the current local F-Droid working source, audited on 2026-11-14. The previously published source/tag is unchanged. Existing unrelated work was retained. This audit does not produce or publish a new APK.

## Findings repaired

| Finding | Behavior after repair | Implementation |
| --- | --- | --- |
| Main Android Run required a device bridge before building | Saves and builds, then opens Android's local install flow; only explicit connected-device/debug actions require a bridge | `IdeScreen.kt`, `IdeHeader.kt`, `CommandManager.kt`, `BuildRunDebugCoordinator.kt`, new `AndroidLocalAppInstaller.kt` and `AndroidAppInstallActivity.kt`, manifest |
| Android Attach bypassed the prepared-debug coordinator | Captures the selected source before save, and dispatches through the same execution gate and pre-launch-task path as Start | `DebugPanel.kt` |
| Rename could commit locally but leave the UI at the old path after synchronization failed | Uses repository outcome observation; verified completion remaps the editor/selection and displays a warning; uncertain outcomes preserve data for review | `FileExplorer.kt` |
| Quick Fix requests outlived their dialogs; repeat selection could resolve twice | Closing/replacing a request cancels and invalidates it; late replies cannot apply edits or overwrite newer results | `ProblemsSheet.kt`, `EditorPane.kt` |
| Code-action lists used title/kind/preferred as a unique key | Distinct actions with identical display fields render safely | `ProblemsSheet.kt`, `EditorPane.kt` |
| Git read commands did not share busy/error handling; initial refresh could race a user action | Reads and startup refresh participate in busy state, and admission errors appear in the panel | `GitPanel.kt` |
| A browser action waited for page layout before reporting an already pending dialog | Dialog observation encloses the presentation wait; blocked actions return promptly without replay | `AgentBrowserController.kt` |
| Terminal verification compiled an obsolete partial source list | Uses Gradle's real production and test classpaths; retains exact terminal suite selection and strict dependency verification | `tools/verify_terminal_workstation.py` |

The handbook describes the local Run path and its installation permission. Existing portrait/landscape placement, dimensions and navigation layout were not redesigned.

## Inventory and coverage

`control-index.csv` records source locations for UI control calls and gesture/keyboard handlers. The Kotlin 2.4 compiler PSI parser covered 1,352 Kotlin production files with zero parse errors. After excluding constructors that only resemble control names, it identified 726 control call sites and 81 gesture/key-handler sites. Wrappers and rendered instances are not one-to-one: these numbers are a source inventory, not a count of independently tested physical buttons. Callback bindings were also inventoried in the external audit workbench.

Reviewed areas include Run/build/debug and palette destinations; Explorer selection/open/create/rename/move/delete/export/recovery; editor tabs, save/reload/zoom and intelligence; terminal tabs, interrupt/close, input/history/recording; browser navigation and dialogs; agent send/stop/approvals/history and external CLI controls; providers/models/MCP/skills/extensions; settings/bug report/legal; project recovery; startup/Exit; portrait/landscape controls.

The local regression selection is explicit, with no wildcard suites or excluded correctness tasks. It exercises real Compose interaction and Robolectric lifecycle boundaries against all compiled production sources. Initial failures were investigated individually. Stale expectations for renamed MCP labels, bundled skills, Goose's command, session-scroll selection and previously authorized browser-site session consent were updated. Browser presentation fixtures now attach and measure a native Android test surface. No production guard was weakened to satisfy a fixture.

Validation results are recorded in `RESULTS.json` after the corresponding runs complete. The external workbench retains original logs, XML reports, input hashes, source snapshots and a portable patch.

## Carrying the repair to another source edition

1. Start with that edition's source and preserve its local changes. Match the methods above rather than replacing complete UI files from another edition.
2. Add the local installer classes and manifest entries together. Keep install permission, session ownership, application epoch checks, Android confirmation and launch-after-success as one change.
3. Connect only the main Android Run route to `runAndroidOnThisDevice`. Retain explicit connected-device and debug commands. Update the handbook/menu descriptions together.
4. Port the observed rename branch without changing Move history or rename focus behavior. Port both Quick Fix surfaces and their tests together.
5. Run the focused regression suites, dependency/F-Droid input checks and a physical-device install/update check. Do not reuse an older APK's build receipt as verification for the repaired source.

## Physical-device verification still required

Host tests cannot certify every device or every external service. On the candidate APK, check local Android Run without Wireless Debugging, first-install access, update, cancellation, denied access, rotation, signature mismatch and Exit while waiting for confirmation. Also check the explicit connected-device/debug commands, SSH and long-lived PTY input, live external CLI providers, Android document pickers/export, native WebView rendering and foreground-service notification actions. No remote account login, SSH server, real external form submission or OEM package installation was performed by these host tests.
