# Archived Documentation

Historical documentation that has been superseded by newer approaches.

## Overview

Contains design documents and references from earlier development phases. Use for historical context, understanding past decisions, or migration from older patterns. For current implementation guidance, use the [contributor docs](../contributors/) and [reference docs](../reference/).

## Contents

| Document | Status | Replacement |
|----------|--------|-------------|
| [vision-and-goals.md](vision-and-goals.md) | Reference | Still valid as architectural vision |
| [terminology.md](terminology.md) | Reference | Terminology definitions remain accurate |
| [classloader-architecture.md](classloader-architecture.md) | Superseded | [Slice Architecture](../contributors/slice-architecture.md) |
| [cluster-deployment-manager.md](cluster-deployment-manager.md) | Superseded | Implementation evolved significantly |
| [infrastructure-slices-design.md](infrastructure-slices-design.md) | Reference | Design rationale for infra slices |
| [development-guide.md](development-guide.md) | Superseded | [Resource Reference](../slice-developers/resource-reference.md) |
| [infra-services.md](infra-services.md) | Superseded | [Slice Patterns](../slice-developers/slice-patterns.md) |
| [aether-high-level-overview.md](aether-high-level-overview.md) | Superseded | Early (v0.7.2) project overview; see [aether-overview.md](../aether-overview.md) for the current one |
| [implementation-plan.md](implementation-plan.md) | Superseded | Early comprehensive build-out plan written against `vision-and-goals.md`; current work is tracked via GitHub Issues |
| [clusterdeploymentmanager-implementation-guide.md](clusterdeploymentmanager-implementation-guide.md) | Superseded | Early implementation guide; see [02-deployment.md](../architecture/02-deployment.md) for current architecture |
| [nodedeploymentmanager-implementation-guide.md](nodedeploymentmanager-implementation-guide.md) | Superseded | Early implementation guide; see [02-deployment.md](../architecture/02-deployment.md) for current architecture |
| [typed-slice-api-design.md](typed-slice-api-design.md) | Superseded | Early API design; see [thinking-in-slices.md](../slice-developers/thinking-in-slices.md) for the current slice-developer guide |
| [dependency-injection-summary.md](dependency-injection-summary.md) | Historical | Point-in-time session summary (dated 2024-11-26) of DI implementation work; not a design document |
| [canary-blue-green-spec.md](canary-blue-green-spec.md) | Draft — design only | Draft strategy spec (target 0.22.0+); referenced from several current specs but never implemented, and its own "Priority #4" backlog pointer is dead (see note in the document) |

## Key Changes Since Archive

1. **MCP Server removed** - Agents interact via Management API REST endpoints
2. **HTTP routing redesigned** - Dedicated forwarding instead of SliceInvoker
3. **Consensus-based leader election** - Two modes: local (fast) and consensus (consistent)
4. **Slice lifecycle simplified** - Clearer state machine documented in [slice-lifecycle.md](../contributors/slice-lifecycle.md)
