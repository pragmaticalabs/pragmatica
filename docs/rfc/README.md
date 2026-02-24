# RFC Proposals

Request for Comments documents for cross-project design decisions.

## Overview

RFCs document design decisions affecting multiple projects in the Pragmatica ecosystem. Each RFC follows a lifecycle: Draft -> Review -> Approved -> Implemented. Superseded RFCs remain for historical reference.

## Index

| RFC | Title | Status | Affects |
|-----|-------|--------|---------|
| [RFC-0000](RFC-0000-ecosystem-foundation.md) | Ecosystem Foundation | Approved | all projects |
| [RFC-0001](RFC-0001-core-slice-contract.md) | Core Slice Contract | Draft | jbct-cli, aether |
| [RFC-0002](RFC-0002-dependency-protocol.md) | Dependency Protocol | Draft | jbct-cli, aether |
| [RFC-0003](RFC-0003-http-layer.md) | HTTP Layer | Draft | jbct-cli, aether |
| [RFC-0004](RFC-0004-slice-packaging.md) | Slice Packaging | Draft | jbct-cli, aether |
| [RFC-0005](RFC-0005-blueprint-format.md) | Blueprint Format | Draft | jbct-cli, aether |
| [RFC-0006](RFC-0006-slice-runtime-config.md) | Slice Runtime Config | Draft | jbct-cli, aether |
| [RFC-0007](RFC-0007-dependency-sections.md) | Dependency Sections | Draft | jbct-cli, aether |
| [RFC-0008](RFC-0008-aspect-framework.md) | Aspect Framework | Draft | jbct-cli, aether |
| [RFC-0009](RFC-0009-request-tracing.md) | Request Tracing | Superseded | aether |
| [RFC-0010](RFC-0010-unified-invocation-observability.md) | Unified Invocation Observability | Draft | aether-invoke, aether-node, forge-core, slice-processor, resource-api |
| [RFC-0011](RFC-0011-messaging-and-resource-lifecycle.md) | Messaging & Resource Lifecycle | Draft | slice-processor, slice-api, aether-invoke, aether-deployment, resource-api |
| [RFC-0012](RFC-0012-resource-provisioning.md) | Resource Provisioning Architecture | Draft | jbct-cli, aether |

## Process

1. Author creates RFC as Draft
2. Affected project agents review and propose changes
3. Multi-agent consensus reached (all affected agents approve)
4. PRs created in affected projects
5. User reviews and approves PRs
6. Both PRs merged -> RFC status becomes "Approved"

## Naming Convention

```
RFC-NNNN-short-title.md
```

4-digit zero-padded number, lowercase hyphenated title. No versioning within RFCs -- create a new RFC instead of updating.
