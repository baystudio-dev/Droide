# Droide documentation

Guides describe application behavior and supported workflows. Design contracts describe individual ownership boundaries, including explicitly dormant or diagnostic-only paths; their presence does not imply a new user-facing feature. Verification records apply only to their measured inputs.

## Repository map

| Directory | Responsibility |
| --- | --- |
| `app/src/main` | Application code, native bridges, resources and packaged assets |
| `app/src/test`, `app/src/androidTest` | Host regression tests and Android instrumentation |
| `backend` | Optional service components with their own deployment requirements |
| `third_party` | Corresponding upstream source, patches and original license notices |
| `tools` | Build, dependency, export and verification entry points |
| `docs/guides`, `docs/contracts` | Supported workflows and implementation ownership boundaries |
| `.github`, `fastlane` | Repository automation, contribution forms and distribution metadata |

Generated build output, backup archives and signing credentials are not repository inputs.

## Start here

- [Architecture and ownership](ARCHITECTURE.md)
- [Build lanes and dependencies](DEVELOPMENT.md)
- [Test strategy and verification](TESTING.md)
- [F-Droid source export, build and updates](distribution/fdroid.md)

## Workbench and application

- [Run, build, debug and action contracts](guides/workbench-actions.md)
- [Layout, input, motion and notifications](guides/workbench-ui.md)
- [Startup, recovery and the Exit deadline](guides/application-lifecycle.md)
- [Editor zoom reset](guides/editor-zoom.md)
- [Report submission and retained drafts](guides/bug-reporting.md)
- [Browser interaction and site-session consent](guides/browser-automation.md)

## Agents and integrations

- [Context budgets and compaction](guides/agent-context.md)
- [Skills](guides/agent-skills.md)
- [Workflows and session results](guides/agent-workflows.md)
- [Editor saves and external-session recovery](guides/agent-persistence.md)
- [Provider protocols and output limits](guides/provider-protocols.md)
- [Custom providers](guides/custom-providers.md)
- [Streaming heartbeat handling](guides/provider-streaming.md)
- [MCP settings and chat shortcuts](guides/mcp-management.md)
- [External CLI integration](guides/external-agents.md)
- [Extension update ownership](guides/extension-updates.md)
- [Language services and toolchains](guides/language-support.md)
- [Document, audio and image tools](guides/media-authoring.md)
- [Agent handbook routing](guides/agent-handbook.md)
- [Session titles](guides/session-titles.md)

## Workspace and recovery contracts

- [Reserved copy-recovery authority](contracts/explorer-recovery-authority.md)
- [Project relocation admission](contracts/project-relocation-admission.md)
- [Membership and selection](contracts/project-membership-selection.md)
- [Preparation continuation](contracts/membership-preparation-continuation.md)
- [Command completion receipts](contracts/membership-command-completion.md)
- [Interrupted command recovery](contracts/membership-command-recovery.md)
- [No-change result expiry](contracts/membership-no-change-expiry.md)
- [Membership operation compaction](contracts/membership-operation-compaction.md)
- [Restore receipts](contracts/restore-membership-command.md)
- [Saved catalog startup](contracts/saved-catalog-startup.md)
- [SQLite capture publication](contracts/sqlite-capture-publication.md)
- [SQLite forensic result reuse](contracts/sqlite-forensic-result-reuse.md)
- [Retained-folder recovery](contracts/native-deleted-folder-recovery.md)
- [Recovery progress and cancellation](contracts/native-recovery-progress.md)

## Catalog diagnostics and admission

- [Dormant request foundation](contracts/catalog-request-contract.md)
- [Mode-choice admission](contracts/catalog-mode-admission.md)
- [Read-only release preflight](contracts/catalog-release-preflight.md)
- [Trusted preflight collection](contracts/catalog-preflight-collector.md)
- [Participating processes and writers](contracts/catalog-process-observation.md)
- [Child-resource ownership](contracts/catalog-child-observation.md)
- [Editor ownership observations](contracts/catalog-editor-observation.md)
- [Native ownership observations](contracts/catalog-native-observation.md)
- [Fallback observations](contracts/catalog-fallback-observation.md)
- [Completed catalog custody](contracts/catalog-custody-boundary.md)
- [Completion reconciliation](contracts/catalog-completion-reconciliation.md)

## Release assets and evidence

- [Droide 1.03.5 release and upgrade guidance](releases/1.03.5.md)
- [Droide 1.03.4 published release](https://github.com/baystudio-dev/Droide/releases/tag/v1.03.4) and [candidate notes](releases/1.03.4.md)
- [Droide 1.03.3 release and upgrade guidance](releases/1.03.3.md)
- [Droide 1.03.2 release and upgrade guidance](releases/1.03.2.md)
- [Droide 1.03.1 release and upgrade guidance](releases/1.03.1.md)
- [Droide 1.03 release and upgrade guidance](releases/1.03.md)
- [Droide 1.02 release and upgrade guidance](releases/1.02.md)
- [Brand assets and provenance](branding/README.md)
- [Verification records and frozen upstream provenance](verification/README.md)

Source inputs that are cited by immutable validation manifests retain their filenames and exact historical receipts. They are not current release approval. Obsolete handoff reports and raw build outputs are archived outside the application source.
