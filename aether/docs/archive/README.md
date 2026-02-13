# Archived Documentation

Historical documentation that has been superseded by newer approaches.

## Overview

Contains design documents and references from earlier development phases. Use for historical context, understanding past decisions, or migration from older patterns. For current implementation guidance, use the [contributor docs](../contributors/) and [reference docs](../reference/).

## Contents

| Document | Status | Replacement |
|----------|--------|-------------|
| [mcp-integration.md](mcp-integration.md) | Deprecated | [Management API](../reference/management-api.md) |
| [ai-integration.md](ai-integration.md) | Deprecated | [Management API](../reference/management-api.md) |
| [vision-and-goals.md](vision-and-goals.md) | Reference | Still valid as architectural vision |
| [terminology.md](terminology.md) | Reference | Terminology definitions remain accurate |
| [classloader-architecture.md](classloader-architecture.md) | Superseded | [Slice Architecture](../contributors/slice-architecture.md) |
| [cluster-deployment-manager.md](cluster-deployment-manager.md) | Superseded | Implementation evolved significantly |
| [kv-schema-simplified.md](kv-schema-simplified.md) | Reference | KV schema design notes |
| [infrastructure-slices-design.md](infrastructure-slices-design.md) | Reference | Design rationale for infra slices |

## Key Changes Since Archive

1. **MCP Server removed** - Agents interact via Management API REST endpoints
2. **HTTP routing redesigned** - Dedicated forwarding instead of SliceInvoker
3. **Consensus-based leader election** - Two modes: local (fast) and consensus (consistent)
4. **Slice lifecycle simplified** - Clearer state machine documented in [slice-lifecycle.md](../contributors/slice-lifecycle.md)
