# Manage MCP

Manage MCP opens Settings → Tools for the active workspace. It supports adding,
renaming, editing, enabling/disabling and removing local stdio servers. Expand a
server to see connection details and actions. Plugin contributions remain owned by
their plugin and cannot be rewritten from this workspace form.

## Access from chat

Type `/` at the beginning of the chat draft to show a temporary command list.
`/mcp` opens workspace MCP settings and `/plugins` opens installed plugins. These
navigation commands run locally, including before a model is configured, and do
not send a message or call a provider. `/compact` uses the built-in agent's existing
context compaction action; external CLI commands that Droide does not own pass
through unchanged. A command with attachments is not consumed as local navigation.

Type `@` at a word boundary to search installed plugin and enabled MCP identities.
Choosing an entry inserts `@plugin/id` or `@mcp/server-name` at the current cursor;
it does not send the draft, start a server or grant tool permission. The agent
receives the identity with the message and uses its existing tool/skill catalog.
Typing in code, an email address or a path does not open the picker. The picker is
bounded, dismissible and keeps keyboard focus in the input. It introduces no
permanent chat button or extra toolbar row.

MCP settings use a compact list. Connection information and server actions appear
only when a row is expanded; configuration, permissions and help live in its
overflow menu. The server form scrolls on smaller screens.

Configuration is saved in `.droide/mcp.json`:

```json
{
  "servers": {
    "local": {
      "type": "stdio",
      "command": "node",
      "args": ["server.js"],
      "protocol": "auto",
      "enabled": true
    }
  }
}
```

Arguments are a JSON array of strings, preserving spaces and empty arguments;
they are not split as a shell command. The runtime validates server names,
executable/argument bounds, transport and enablement types. Remote HTTP, OAuth
and custom environment variables are not implemented by this stdio-only client.
The form and runtime reject those transports/settings instead of pretending they
are connected. Auto negotiates the protocol when the first connection opens. The 2025 and
2026 options select a specific protocol for servers that require it.

Saving or enabling a server does not launch it. Connect displays the exact command
and arguments, and Connect once authorizes that connection/discovery attempt under
the default Ask policy. A Deny rule remains authoritative. The reviewed selection
is one-use and belongs to the workspace/controller; changed configuration,
execution host or referenced script invalidates it. This action does not create
persistent permission grants for agent tool calls.

Disabling or removing a server retires its Droide stdio session and invalidates
cached tool discovery. Late results cannot restore deleted/disabled health rows.
Disabled servers are excluded from tool discovery and new external CLI sessions.
Already-running external CLI processes own their MCP connections: restart those
sessions to apply configuration changes. Manage MCP does not interrupt their
in-flight work to force a reconnect.

The form writes through FileRepository with the version of the configuration the
user opened. Concurrent disk edits and dirty editor buffers block conflicting
writes. Other servers and unknown JSON fields survive edits. Invalid existing
configuration is left intact: use Open config to repair it, then Reload. JSON
formatting is normalized on save; comments are not supported by this JSON format.

## Tools, resources and prompts

Connect reads the capabilities advertised by the server. A server that offers
only resources or prompts can connect with zero tools. Its resources and
prompts are available through the corresponding MCP operations.

The agent reuses the discovered tool catalog. When a connected server reports
a tool list change, the next discovery refreshes that server. Before dispatch,
Droide checks the selected tool against its advertised input schema. If the
schema changed, the agent can rediscover the tool and prepare a new call. Both
structured and JSON-form calls use the permission for the named server and tool.

A new external CLI session receives up to 16 enabled workspace servers whose
commands and arguments can be resolved for its execution environment. Several
servers can share the same executable. The CLI owns the connections it opens.
