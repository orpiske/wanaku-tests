# Likely Bugs Found During Praxis Integration Testing

## 1. CLI `tools add` / `resources expose` sends JSON without `name` field

**Affected**: Wanaku CLI (`wanaku` binary)
**Severity**: High
**Impact**: All CLI-based tool/resource registration fails against praxis with `400 Bad Request: invalid tool JSON: missing field 'name'`

The CLI commands `tools add --name <name> --type http --uri <uri>` and `resources expose --name <name> --location <loc>` send a JSON payload to the praxis management API that does not include the `name` field at the top level. The praxis API requires `name` as a mandatory field in both `ToolEntry` and `ResourceEntry`.

**Reproduction**: Run `wanaku tools add --host http://localhost:<mgmt-port> --name test --type http --uri http://example.com --description "Test tool" --plain` and observe the 400 error with `missing field 'name'`.


## 2. Praxis `/api/v1/tools/payloads` and `/api/v1/resources/payloads` endpoints do not unwrap payload envelope

**Affected**: Praxis management API
**Severity**: Medium
**Impact**: `registerToolWithConfig()` and `exposeResourceWithConfig()` fail with `400 Bad Request: missing field 'name'`

The old Java router accepted a `{"payload": {...}, "configurationData": "..."}` wrapper format on the `/payloads` endpoints. Praxis routes both `POST /api/v1/tools` and `POST /api/v1/tools/payloads` to the same handler (`ToolRoute::Create`) which expects a flat `ToolEntry` JSON. The same applies to resources.


## 3. Praxis does not return `Mcp-Session-Id` header on MCP initialize

**Affected**: Praxis MCP server
**Severity**: Medium (compatibility)
**Impact**: MCP clients that require `Mcp-Session-Id` (e.g., quarkus-mcp-server-test library) cannot connect

Praxis responds to MCP `initialize` with protocol version `2025-03-26` but does not return the `Mcp-Session-Id` response header. The MCP Streamable HTTP transport specification makes this header optional, but many client libraries treat it as required for protocol versions before `2026-07-28`.


## 4. Praxis tool discovery from MCP forwards may fail against CIC (Camel MCP server)

**Affected**: Praxis forward tool discovery + CIC MCP server compatibility
**Severity**: High
**Impact**: Registering a CIC instance as an MCP forward succeeds (HTTP 200) but tool discovery returns 0 tools

When praxis registers a forward via `POST /api/v1/forwards`, it attempts to discover tools by connecting to the forward's MCP endpoint using the rmcp Rust SDK. The CIC's Camel MCP server (`VertxMcpStreamableServerTransportProvider`) may reject the rmcp client's requests due to MCP session/protocol negotiation incompatibility. The forward registration succeeds, but `discover_tools_from_forward` returns 0 tools.


## 5. Praxis uses upsert semantics for tools, resources, and prompts (no duplicate rejection)

**Affected**: Praxis management API
**Severity**: Low (behavior change)
**Impact**: Tests expecting `409 Conflict` on duplicate registration get `200 OK` instead

The old Java router returned HTTP 409 when attempting to register a tool/resource/prompt with a duplicate name. Praxis silently overwrites the existing entry (upsert). This is a semantic difference, not necessarily a bug, but breaks tests that assert on duplicate rejection.


## 6. Praxis prompt edit endpoint returns 404

**Affected**: Praxis management API (`PUT /api/v1/prompts/<name>`)
**Severity**: Low
**Impact**: Prompt edit operations fail with `PromptNotFoundException`

The prompt edit (PUT) endpoint returns 404 even when the prompt exists. The edit semantics may not be implemented in praxis.


## 7. Praxis `tools/call` forwarded response missing `isError` field

**Affected**: Praxis MCP filter (`wanaku_tool_call` in `filters/src/tool_call.rs`)
**Severity**: High
**Impact**: MCP clients crash with NullPointerException when parsing tool call responses

In `handle_forwarded_call`, the successful response is:
```rust
"result": {"content": mcp_content}
```

It should be:
```rust
"result": {"content": mcp_content, "isError": false}
```

The MCP spec says `isError` is optional (defaults to false), but the `quarkus-mcp-server-test` library calls `getBoolean("isError")` which returns null for a missing field, causing an unboxing NPE.

**Fix**: Add `"isError": false` to the result object in `handle_forwarded_call` (line ~159 of `tool_call.rs`).
