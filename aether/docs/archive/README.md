# Archived Documentation

This folder contains historical documentation that has been superseded by newer approaches or is no longer actively maintained.

## Deprecation Status

| Document | Status | Replacement |
|----------|--------|-------------|
| [mcp-integration.md](mcp-integration.md) | **Deprecated** | [Management API](../reference/management-api.md) - Direct REST API access |
| [ai-integration.md](ai-integration.md) | **Deprecated** | [Management API](../reference/management-api.md) - Agents use REST directly |
| [vision-and-goals.md](vision-and-goals.md) | Reference | Still valid as architectural vision |
| [terminology.md](terminology.md) | Reference | Terminology definitions remain accurate |
| [classloader-architecture.md](classloader-architecture.md) | Superseded | [Slice Architecture](../contributors/slice-architecture.md) |
| [cluster-deployment-manager.md](cluster-deployment-manager.md) | Superseded | Implementation evolved significantly |
| [kv-schema-simplified.md](kv-schema-simplified.md) | Reference | KV schema design notes |
| [infrastructure-slices-design.md](infrastructure-slices-design.md) | Reference | Design rationale for infra slices |

## When to Use Archive

- **Historical context**: Understanding why decisions were made
- **Reference designs**: Original design documents for context
- **Migration**: When updating from older patterns

## When NOT to Use Archive

- **Implementation guidance**: Use current contributor docs
- **API reference**: Use current reference docs
- **Best practices**: Use current slice developer docs

## Key Changes Since Archive

1. **MCP Server removed** - Agents interact via Management API REST endpoints
2. **HTTP routing redesigned** - Dedicated forwarding instead of SliceInvoker
3. **Consensus-based leader election** - Two modes: local (fast) and consensus (consistent)
4. **Slice lifecycle simplified** - Clearer state machine documented in [slice-lifecycle.md](../contributors/slice-lifecycle.md)
