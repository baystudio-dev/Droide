# Browser automation

The on-device browser separates bounded DOM observation/preparation from Android input dispatch. Readiness, document identity, permission and cancellation remain checked across both stages.

## Tabs and viewport

The agent starts in its own tab at 1280 × 800 CSS pixels. The Web tab and HTML preview use the device layout. The two tabs keep separate documents, history and form state within the Android WebView profile. A site can recognize the same signed-in account in both tabs.

Use `browser(operation="viewport", mode="mobile")` for a 390 × 844 viewport, or supply integer `width` and `height` for a custom size. The preview scales the complete page viewport to fit the pane. Reads and screenshots report its measured width and height in CSS pixels. Screenshots wait for a rendered frame after resizing.

When viewing the agent’s desktop tab, pinch anywhere in the preview to zoom and drag to pan. Double-tap to enlarge a point or return to fit. The agent keeps its desktop viewport and can continue working while you look around. For single-touch zoom controls, open **Browser actions**. Choose **Interact with page** to send touches to the site, then **Return to preview** to resume viewing.

Changing size keeps the current document and form entries. A site that chooses its layout on the server receives the selected browser identity on the next navigation. If the viewport result returns `reload_for_site_version`, open the intended URL again when it is safe to leave the current page. WebView providers that support User-Agent Client Hints also receive the selected platform and mobile flag.

To use a page that the user already opened, request `request_user_tab` only when asked. After the user approves, pass `tab="user"` for those calls. Ordinary calls still target the agent tab. The user can switch previews and end the handoff in the existing Browser actions menu. Stopping or changing the chat also ends access.

## Supported interaction

- Click, check, key, hover, swipe and drag validate a visible, enabled, unobstructed target. Pointer targets must settle before dispatch; small SVG controls use prepared coordinates rather than relying on an HTML-only click method.
- Reads report each returned control’s pointer state in the current viewport. For covered targets, actions try reachable points and can center a target under a sticky header. A blocking dialog or fixed banner can return a short list of reachable controls. A full-screen layer can be handled without scrolling the page behind it. The agent can use these references to handle the dialog when authorized.
- Input is dispatched once. Cancellation ends a partial gesture with CANCEL; an uncertain action is not replayed. Below-slop swipes/drags are rejected to prevent accidental clicks.
- `type` validates field kind, readonly/disabled state, limits, normalization, options and the retained value, including after focus. Empty text clears a field. `submit=true` dispatches Enter; it does not establish website acceptance.
- `check` requests a state without toggling an already-correct control. Scroll supports containers and both axes. `wait_for` has a bounded timeout.
- Snapshot references belong to actual nodes and a document namespace. Replaced nodes or documents invalidate old references. Snapshot values omit text/password/file inputs and declare frame/closed-shadow coverage limits.

A pending JavaScript dialog is reported before waiting for page presentation. Actions that need coordinates still require the current visible, laid-out view. Navigation callbacks update the address model without overwriting an address the user is editing.

## Permission ownership

Allow once covers one action. Site-session consent is held in memory and bound to the agent session, permission scope, exact scheme/host/port and capture contract. A new origin needs its own grant. Concrete Deny rules remain authoritative before dispatch and publication; ordinary web fetches do not inherit browser grants.

Screenshots/console output retain document-bound publication consent. Dialog authority follows the requesting origin. Revocation applies to the original grant object; a later grant cannot revive an in-flight operation that lost authority. Stop, session replacement and owner retirement remove their stored grants.

## Video feeds and downloads

Enter a hostname or a full HTTPS address in the Web tab. Android follows site redirects and loads the page independently of agent DOM reads. The agent observes the resulting document in its selected tab. A read identifies video and audio elements and reports playback readiness, paused state and media errors. On a vertical feed, scroll down without a target to move the scrollable panel under the middle of the viewport. Check the returned offset. For a gesture-driven player, use a swipe on a visible target.

A download link opens a native confirmation in the originating tab. The filename, source host and available size identify the file. **Download** saves it to Android Downloads. A cancelled or interrupted transfer discards its partial file. Public HTTPS downloads follow validated redirects. Same-origin downloads can use the signed-in WebView session. The destination CDN receives its signed URL. Inline data and same-origin generated blob files can also be saved when the WebView offers them. The agent can report `download_waiting_for_user` and direct the user to that tab.

## Source map

| Owner | Responsibility |
| --- | --- |
| `AgentBrowserDom`, `AgentBrowserSnapshot` | Bounded discovery, references and coverage |
| `AgentBrowserInteraction`, `AgentBrowserActions` | Validation, preparation and single dispatch |
| `AgentBrowserNativeInput` | Android pointer/key transport with caller guards |
| `BrowserActionOptions` | Invocation-local options |
| `BrowserSiteSession`, `ApprovalManager`, `AgentTools` | Grants, revocation and policy/publication checks |

`tools/verify_browser_actions.py` executes production DOM generators in local Chromium fixtures. `tools/verify_agent_web_browser.py` checks bounded DOM/privacy behavior with mocked geometry. Neither proves Android WebView input/IME behavior. Keep physical-device checks for zoom/density, tiny controls, nested scrolling, renderer loss and Exit during gestures.

Frame/closed-shadow control, automatic file uploads or approval of device downloads and an external-CLI browser bridge must not be assumed from built-in browser availability. External CLIs can use their own tools.
