# Testing and verification

Tests are retained by the behavior they protect, not their age or checkpoint-shaped filename. Remove coverage only when it is obsolete or a verified replacement covers the same failure boundary. A failure is evidence to investigate, not a reason to delete a test.

## Test organization

| Location | Purpose |
| --- | --- |
| `app/src/test` | Unit, protocol, filesystem, lifecycle and Compose/Robolectric regressions |
| `app/src/androidTest` | Instrumented Android behavior |
| `tools/tests` | Build/export, dependency trust, artifact parsing and native contract checks |
| `tools/host-regressions` | Focused host fixtures with explicit Android/native boundaries |
| `tools/agent-behavior-tests.txt` | Focused built-in/external-agent suite selection |
| `docs/verification` | Scoped result summaries and required source/license provenance |

Maintain separate evidence for source integrity, host tests, APK packaging, physical Android workflows and abrupt process/power loss. Do not sum repeated executions as distinct tests. A source inventory of buttons is not proof that every rendered control has been tapped on every device.

## Running relevant checks

The fast repository commands are in [development](DEVELOPMENT.md). Additional independently runnable tooling tests include:

```sh
python3 -B -m unittest discover -s tools/tests -p 'test_source_hygiene.py' -v
python3 -B -m unittest discover -s tools/tests -p 'test_document_file_icons.py' -v
python3 -B -m unittest discover -s tools/tests -p 'test_goose_editor_data.py' -v
```

Use the selected build lane's verified classpath to run `:app:testDebugUnitTest --tests fully.qualified.TestClass`. The explicit `DROIDE_HOST_JVM_TESTS_ONLY` mode admits exact reviewed test selectors and rejects APK/native/device work; it is not a substitute for a release build. Its task graph, failure handling and strict dependency verification must remain enabled. Supply the applicable terminal/JGit inputs and test JVM through the reviewed host configuration; do not assemble a partial production classpath from stale JARs.

`tools/verify_terminal_workstation.py` drives seven terminal suites through Gradle. It accepts an external `--gradle`, a supported `--test-task`, an optional host `--init-script` and repeated `--gradle-arg` values. Its `--help` describes the interface. Browser DOM fixtures use `tools/verify_browser_actions.py`; Chromium results do not establish Android WebView input behavior.

An environment that can compile only selected test sources must report that selection. It must still compile unchanged production sources and fail if a selected suite is missing. Restore global registries/admission state, dispose Compose views and join owned work between tests. Never disable production lifecycle guards to repair a fixture.

## Workbench regression checks

Exercise the action through its normal entry point and check its destination, file selection and cancellation behavior. For Extensions, include navigation back to the catalog, version selection and reopening an installation in progress. Record the source revision and APK tested. [Workbench actions](guides/workbench-actions.md)

## Android acceptance

Test the exact APK hash on supported Android versions and representative phones/tablets. Include:

- Startup, last-file restoration, binary input rejection, drafts, process death and the 5-second Exit deadline.
- Portrait/landscape navigation, editor selection/IME/zoom, restored expansion and rapid panel reversals.
- Kotlin/Java file Run inside an Android Gradle project must use the language compiler/runtime. Verify the explicit local Android app action with no wireless bridge, install permission denial/cancellation/update, connected-device Run and debug attach.
- Explorer rename/move/delete/undo/export, dirty conflicts and document-provider grant loss.
- SSH/VPN, long-running PTY, Unicode/TUI, input modifiers, resize, interrupt/tab close and detached jobs.
- Built-in/external agents, approvals, streaming, pending saves, browser forms/dialogs, MCP and extensions.

Record failures, skips, actual device/OEM and test prerequisites. A successful targeted rerun does not erase an unsuccessful full-suite run. Network accounts, live services and package installation need their own evidence; host fixtures do not claim them.
