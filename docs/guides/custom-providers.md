# Meta and custom providers

Settings → Providers & Models → Manage providers includes **Meta** even before the downloadable catalog is available. Meta Model API uses `https://api.meta.ai/v1`, bearer authentication and OpenAI Responses. Models are discovered and validated; their availability depends on the account. This is the current Meta API, not a redirect to another vendor hosting Llama weights.

## Add, connect, edit and remove

1. Choose **Add custom provider** in the connection menu.
2. Enter a display name, unique ID and API base URL, including the required version/path prefix. Do not append `/chat/completions`, `/responses` or `/messages`.
3. Select the documented API protocol. Supported choices are OpenAI Chat Completions, OpenAI Responses, Anthropic Messages and Google GenerateContent. Select whether the endpoint requires an API key.
4. Leave **Model IDs** empty for discovery, or enter exact model IDs, one per line, for servers without a compatible catalog. Up to 16 manual models can be configured.
5. **Save & connect** stores the definition. The next screen accepts the credential using the existing encrypted credential store. **Connect & validate** checks the server; **Use selected model** selects a validated model.
6. The edit icon on a custom provider opens its settings. **Remove custom provider** disconnects it and removes its definition/credential after confirmation; conversation history remains.

Definitions are user settings shared across projects, stored separately from `.droide/providers.json`. The UI ID `company` becomes `user.company` internally. Existing workspace definitions keep their `custom.*` namespace and configuration behavior. No external CLI account/configuration is rewritten.

## Validation and security boundaries

- Remote URLs require public HTTPS. HTTP is supported only for explicit loopback hosts; network target resolution uses the existing pinned-DNS policy. Private LAN addresses and arbitrary HTTP endpoints are not enabled by this form.
- URL credentials, queries, fragments, invalid ports and unsupported fields are rejected. API keys and arbitrary headers never enter the definition file. The four protocols determine the appropriate authorization header.
- Automatic discovery uses the existing bounded, paginated catalog flow. Manual models instead receive short non-streaming requests using the selected protocol. These requests can be billed by the provider. The form and connection view disclose this before validation. Refreshing/revalidating manual models repeats validation.
- A successful probe establishes that the endpoint accepts the model, protocol and credential. It does not certify every model capability such as images, reasoning or function tools. Existing runtime/model capability checks remain authoritative.
- A failed model probe, malformed response, authentication failure or cancellation does not commit a partially validated connection. No request is retried under a different protocol behind the user's back.
- Stored connection receipts bind the provider ID, canonical endpoint, protocol, authentication scheme, network scope and region. Editing a destination does not grant it the old credential. A changed destination requires explicit connection; live model certification is revoked when a definition changes.
- Edits compare the originally opened definition before writing. Concurrent stale saves/removals are rejected. File writes sync a temporary file then atomically replace the prior file before publishing the registry revision. Invalid settings remain available for diagnosis; they are not silently replaced with an empty list.
- Providers from projects or the downloadable catalog cannot occupy the `user.*` namespace. Dynamic catalog metadata cannot override user provider definitions or their model capabilities.
- At most 32 definitions, 16 manual models per definition and 128 KiB of serialized settings are allowed. These are configuration limits, not a model's context/output budget.

## Validation scope

Focused Kotlin/Compose compilation and host tests exercise persistence, stale writes, reserved identities, four protocol probes, connection failure/endpoint ownership, provider/model routing and the real Compose form. Device validation and a live Meta account request remain separate checks; this source change alone is not a release APK or proof of live account access.
