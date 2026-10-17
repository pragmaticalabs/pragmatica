# RFC Proposals

Request for Comments documents for cross-project design decisions.

## Overview

RFCs document design decisions affecting multiple projects in the Pragmatica ecosystem. Each RFC follows a lifecycle: Draft -> Review -> Approved (or Accepted) -> Implemented. Superseded RFCs remain for historical reference. The status in each RFC file is authoritative; this index mirrors it. In the "Affects" column, `jbct-cli` is the `jbct/jbct-cli` module of this repository.

## Index

| RFC | Title | Status | Affects |
|-----|-------|--------|---------|
| [RFC-0000](RFC-0000-ecosystem-foundation.md) | Ecosystem Foundation | Approved | all projects |
| [RFC-0001](RFC-0001-core-slice-contract.md) | Core Slice Contract | Implemented | jbct-cli, aether |
| [RFC-0002](RFC-0002-dependency-protocol.md) | Dependency Protocol | Draft | jbct-cli, aether |
| [RFC-0003](RFC-0003-http-layer.md) | HTTP Layer | Implemented | jbct-cli, aether |
| [RFC-0004](RFC-0004-slice-packaging.md) | Slice Packaging | Implemented | jbct-cli, aether |
| [RFC-0005](RFC-0005-blueprint-format.md) | Blueprint Format | Implemented | jbct-cli, aether |
| [RFC-0006](RFC-0006-slice-runtime-config.md) | Slice Runtime Config | Draft | jbct-cli, aether |
| [RFC-0007](RFC-0007-dependency-sections.md) | Dependency Sections | Draft | jbct-cli, aether |
| [RFC-0008](RFC-0008-aspect-framework.md) | Aspect Framework | Draft | jbct-cli, aether |
| [RFC-0009](RFC-0009-request-tracing.md) | Request Tracing | Superseded | aether |
| [RFC-0010](RFC-0010-unified-invocation-observability.md) | Unified Invocation Observability | Draft | aether-invoke, aether-node, forge-core, slice-processor, resource-api |
| [RFC-0011](RFC-0011-messaging-and-resource-lifecycle.md) | Messaging & Resource Lifecycle | Draft | slice-processor, slice-api, aether-invoke, aether-deployment, resource-api |
| [RFC-0012](RFC-0012-resource-provisioning.md) | Resource Provisioning Architecture | Draft | jbct-cli, aether |
| [RFC-0013](RFC-0013-deployment-provider.md) | DeploymentProvider SPI | Draft | environment-integration, hetzner, cloud-tests, aether-setup, node |
| [RFC-0014](RFC-0014-deployment-state-machine.md) | Deployment State Machine | Implemented | aether-deployment |
| [RFC-0015](RFC-0015-cluster-label-scoping.md) | Cluster-Label Scoping | Accepted | aether |
| [RFC-0016](RFC-0016-full-major-cloud-support.md) | Full Major-Cloud Support | Draft (W1-W4, W6, W10 landed) | aether |
| [RFC-0017](RFC-0017-cluster-owned-provisioning.md) | Cluster-Owned Provisioning | Draft | aether |
| [RFC-0018](RFC-0018-kv-lost-update-fence.md) | Lost-update fence for KV read-modify-write | Accepted (implemented on `release-1.0.0-rc3`) | integrations/cluster |

## Process

1. The author creates the RFC as Draft.
2. Maintainers of the affected modules review and propose changes.
3. Pull requests implementing or acknowledging the RFC are opened in the affected modules.
4. The owner reviews and approves; the RFC status becomes "Approved" (or "Accepted") when the owner accepts it,
   and "Implemented" once the change has landed.
5. An RFC replaced by a later one is marked "Superseded" and kept for history.

## Naming Convention

```
RFC-NNNN-short-title.md
```

4-digit zero-padded number, lowercase hyphenated title. No versioning within RFCs -- create a new RFC instead of updating.
