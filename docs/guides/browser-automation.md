# Browser automation

The on-device browser separates bounded DOM observation/preparation from Android input dispatch. Readiness, document identity, permission and cancellation remain checked across both stages.

## Supported interaction

- Click, check, key, hover, swipe and drag validate a visible, enabled, unobstructed target. Pointer targets must settle before dispatch; small SVG controls use prepared coordinates rather than relying on an HTML-only click method.
- Input is dispatched once. Cancellation ends a partial gesture with CANCEL; an uncertain action is not replayed. Below-slop swipes/drags are rejected to prevent accidental clicks.
- `type` validates field kind, readonly/disabled state, limits, normalization, options and the retained value, including after focus. Empty text clears a field. `submit=true` dispatches Enter; it does not establish website acceptance.
- `check` requests a state without toggling an already-correct control. Scroll supports containers and both axes. `wait_for` has a bounded timeout.
- Snapshot references belong to actual nodes and a document namespace. Replaced nodes or documents invalidate old references. Snapshot values omit text/password/file inputs and declare frame/closed-shadow coverage limits.

A pending JavaScript dialog is reported before waiting for page presentation. Actions that need coordinates still require the current visible, laid-out view. Navigation callbacks update the address model without overwriting an address the user is editing.

## Permission ownership

Allow once covers one action. Site-session consent is held in memory and bound to the agent session, permission scope, exact scheme/host/port and capture contract. A new origin needs its own grant. Concrete Deny rules remain authoritative before dispatch and publication; ordinary web fetches do not inherit browser grants.

Screenshots/console output retain document-bound publication consent. Dialog authority follows the requesting origin. Revocation applies to the original grant object; a later grant cannot revive an in-flight operation that lost authority. Stop, session replacement and owner retirement remove their stored grants.

## Source map

| Owner | Responsibility |
| --- | --- |
| `AgentBrowserDom`, `AgentBrowserSnapshot` | Bounded discovery, references and coverage |
| `AgentBrowserInteraction`, `AgentBrowserActions` | Validation, preparation and single dispatch |
| `AgentBrowserNativeInput` | Android pointer/key transport with caller guards |
| `BrowserActionOptions` | Invocation-local options |
| `BrowserSiteSession`, `ApprovalManager`, `AgentTools` | Grants, revocation and policy/publication checks |

`tools/verify_browser_actions.py` executes production DOM generators in local Chromium fixtures. `tools/verify_agent_web_browser.py` checks bounded DOM/privacy behavior with mocked geometry. Neither proves Android WebView input/IME behavior. Keep physical-device checks for zoom/density, tiny controls, nested scrolling, renderer loss and Exit during gestures.

Frame/closed-shadow control, browser file-chooser/download authority and an external-CLI browser bridge must not be assumed from built-in browser availability. External CLIs can use their own tools.
