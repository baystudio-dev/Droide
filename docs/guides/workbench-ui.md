# Workbench presentation

Portrait and landscape share workspace services and document state. Presentation changes must not recreate a terminal, replace an editor's document authority, restart an agent, or discard a draft.

## Layout and input

- Portrait keeps the existing navigation and full-screen chat presentation.
- Landscape can show the sidebar, editor and agent together. The editor uses remaining space when either side panel closes.
- Editor, terminal and chat have separate input ownership. Landscape expansion follows the focused editor or terminal; typing in chat does not expand either one.
- Hiding the keyboard retains an expanded editor or terminal. The restore-size control returns it to its previous layout. Workspace/orientation changes reset the applicable presentation state.
- The collapsed landscape bottom-panel strip uses 25 dp. Expanded controls retain their normal spacing and input targets.
- Constraints, system insets and font scaling determine available space. A title receives a maximum width; unused space must not become a forced minimum that displaces adjacent controls.

The layout owner is [IdeScreen](../../app/src/main/java/com/baystudio/droide/ui/IdeScreen.kt). The active editor/native view retains ownership across presentation changes. Review landscape resize, keyboard dismissal, panel transitions and rotation on a physical device after changing this boundary.

## Motion

Opening and closing panels retain their content until the transition finishes. Width/height, alpha and position follow the current target, including rapid reversals. Motion must not become a readiness timer, delay completed work, or enable an outgoing control after its owner has retired.

Streaming updates keep stable message/activity identity. Replacing an entire list or restarting an animation on each token causes jumps and loses scroll intent. Respect system animation settings and keep functional state independent of animation completion.

## Startup and stopped states

The welcome screen exists only while workspace startup is pending. Its captions reflect real startup stages; it has no minimum display duration and no simulated progress. Errors expose the failed operation and available recovery action.

The stopped screen uses the same session branding, insets and accessible layout. Opening it requires an explicit user action and the work-admission guard. It does not automatically restart old work or a manual CPU lock. See [application lifecycle](application-lifecycle.md).

## Notifications

Notification builders use the shared `withDroideIcons` helper: a transparent monochrome small icon for system tinting and a resource-backed launcher icon for the large-icon slot. Keep the resource references valid for adaptive and legacy launchers. Do not add manufacturer-specific fallbacks to the generic Android logo.

SystemUI controls final placement. Verify fresh notifications in collapsed/expanded states on supported Android versions and more than one OEM; resource tests alone cannot certify notification-shade rendering.

## Acceptance checks

Exercise portrait/landscape, a narrow phone and a larger tablet, enlarged text, RTL, system bars and keyboard transitions. Verify both side panels independently and together, rapid open/close reversals, editor scroll/cursor retention, terminal input and chat draft retention. Host layout tests complement these checks; they do not measure physical-device frame pacing.
