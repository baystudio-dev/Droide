# Contributing to Droide

Read [architecture](docs/ARCHITECTURE.md), [development](docs/DEVELOPMENT.md) and [testing](docs/TESTING.md) before changing an ownership boundary or build recipe.

## Changes

Keep each change focused on a reproducible problem and its resulting behavior. UI changes must preserve existing document, session and process owners across recomposition, rotation and navigation. Handle cancellation, stale results and errors at the operation boundary; do not use delays, silent retries or discarded recovery data to hide failures.

Use existing controllers for Run/Build, editor persistence, file publication, process ownership, permissions and shutdown. Capture the selected target before asynchronous preparation, then revalidate authority before effects. Keep portrait/landscape behavior and accessibility intact.

Comments should explain a non-obvious invariant or constraint in at most two lines. Put longer architecture and operational guidance in maintained documents. Avoid comparative product narratives, task progress, temporary workspace paths and execution claims in comments or test descriptions. Preserve required upstream notices, frozen integrity inputs and real integration identifiers.

## Verification

Add a regression that exercises observable behavior when fixing a meaningful defect. Use local fixtures for provider and browser protocols; automated tests must not send real support messages, publish repositories or access a user's accounts. Fixtures own their temporary files, processes, coroutines and global state, and must clean up or explicitly report uncertain cleanup.

Run the affected suites and source/dependency checks. Record the command, exact source revision, build lane, toolchain, failures/skips and test scope. Do not weaken production guards, replace assertions with unconditional success, delete a failing test, or rewrite checksums to force a green build. Full-suite, targeted-suite, APK and device results must remain distinguishable.

## Repository maintenance

Before deleting or moving a file, check Gradle/CI imports, scripts, test fixtures, documentation links and frozen provenance manifests. Retire coverage only after an equivalent replacement is verified. Test names containing an older revision can still protect a current invariant. Do not modify upstream license bytes or sealed historical receipts.

Use the source exporter for distribution. Never run blanket deletion by filename prefixes such as `build-*`: build tooling can use those names. Keep backups and cleanup manifests outside the application source. Do not commit APKs, caches, credentials, private signing keys or provider responses containing private information.

## Reports and pull requests

Report the installed version, Android/device details, build channel, minimal reproduction, expected behavior and actual error. Remove passwords, tokens and private project content from attachments. For a pull request, describe the final behavior and relevant checks, including remaining device or integration limits. Changes to package identity, public APIs, dependencies or permissions need explicit rationale and migration notes.
