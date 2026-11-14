# Provider protocols and output budgets

Provider identity, exact model identity, endpoint protocol and advertised capabilities jointly determine a request. A model's marketing name is not proof of a wire format, token ceiling or account entitlement.

| Protocol | Output limit | Reasoning configuration |
| --- | --- | --- |
| OpenAI Chat Completions | Native OpenAI uses `max_completion_tokens`; compatible gateways use their supported fields | Supported effort/profile metadata |
| OpenAI Responses | `max_output_tokens` | `reasoning` supported by the selected endpoint/model |
| Anthropic Messages | `max_tokens` | Supported numeric budget or adaptive thinking/effort |
| Google GenerateContent | `generationConfig.maxOutputTokens` | Supported numeric thinking budget or level |

Configured endpoint metadata takes precedence over a routed model name. Catalog aliases resolve to an exact provider/model pair. Unsupported SDK packages or unknown reasoning fields are not made callable by guesswork. Local and custom providers use the same capability checks. See [custom providers](custom-providers.md).

## Allocation and context

Context occupancy and a single response's output allowance are separate limits. Reasoning can consume output space that would otherwise hold visible text and tool arguments. Low context usage therefore does not rule out an output-limit failure.

`ProviderModelRequestPolicy`, capability registries and request-budget helpers coordinate protocol choice, advertised input/output limits, explicit overrides and bounded fallbacks. Known output caps are honored; input-only ceilings are not reduced twice. Numeric thinking must leave space for visible output/tool calls. Conflicting or impossible settings fail before network use.

Preflight, history retention and compaction use the selected reservation. Compaction keeps its independent summary-size and checkpoint-shrinking checks; historical instructions do not become permission to execute tools. A fallback allocation is a local request choice, not a claim about an unknown model's true maximum. See [context management](agent-context.md).

## Discovery and streaming

`ProviderModelCatalog` strips the `models/` resource prefix only for Google. Exact non-Google IDs must survive discovery, certification and requests unchanged.

Reviewed Google/Anthropic cursor pagination stays on the configured model-list endpoint, rejects repeated/malformed cursors and bounds collection. A failed later page does not publish a partial certified catalog. Arbitrary provider-returned pagination URLs and undocumented gateway cursor schemes are not followed.

Streaming adapters distinguish heartbeat events from semantic completion. Supported keepalive events do not terminate a turn. Length/incomplete outcomes preserve available partial text but do not authorize partial tool arguments or automatic replay. See [stream-event handling](provider-streaming.md).

## Validation boundary

Regression coverage includes protocol serializers, budget allocation, model identity, pagination, SSE/buffered responses and failure preservation using local fixtures. Discovery success is not a successful inference call for every returned model. Live provider limits, entitlements, deployment changes and undocumented gateway behavior require separate evidence; do not claim every provider/model was exercised from a source audit.
