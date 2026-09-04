# Aether Documentation

Central hub for all Aether Unified Application Runtime documentation.

## Getting Started

Two distinct entry points, depending on what you're doing:

- [Getting Started with Aether](getting-started.md) — install the tools, scaffold a project, run it locally. Start here if you're new to Aether itself.
- [My First Aether Slice](slice-developers/getting-started.md) — build, test, and deploy your first slice. Start here if Aether is already running and you're writing application code.

## Architecture

High-level design and the numbered architecture series, one document per subsystem.

- [Aether Overview](aether-overview.md) — platform overview and core concepts
- [Architecture Diagrams](architecture-diagrams.md) — visual system architecture
- [00 — Overview](architecture/00-overview.md) — entry point for the architecture series
- [01 — Consensus and KV-Store](architecture/01-consensus.md) — Rabia consensus protocol, KV-Store state machine, leader election
- [02 — Deployment and Lifecycle](architecture/02-deployment.md) — how slices are deployed, managed, and scaled
- [03 — Slice Invocation and Routing](architecture/03-invocation.md) — method invocation, request routing, pub/sub, scheduled tasks
- [04 — Networking and Transport](architecture/04-networking.md) — cluster transport layer, topology management, message routing
- [05 — Worker Pools and Two-Layer Topology](architecture/05-worker-pools.md) — scaling beyond the Rabia consensus limit to 10,000+ nodes
- [06 — HTTP Request Routing](architecture/06-http-routing.md) — routing HTTP requests to slices, cross-node forwarding, retries
- [07 — Observability](architecture/07-observability.md) — metrics pipeline, alerting, dynamic aspects, monitoring interfaces
- [08 — Auto-Scaling](architecture/08-scaling.md) — the two-tier scaling system: reactive (decision tree) and predictive (TTM)
- [09 — Distributed Storage (DHT)](architecture/09-storage.md) — distributed hash table for artifact storage and caching
- [10 — Security](architecture/10-security.md) — mTLS, gossip encryption, RBAC, API key authentication
- [11 — Slice Container](architecture/11-slice-container.md) — ClassLoader isolation, dependency materialization, lifecycle hooks
- [12 — Management and Tooling](architecture/12-management.md) — CLI, Management API, Forge simulator, web dashboard
- [13 — Cloud Integration](architecture/13-cloud-integration.md) — automated infrastructure management across cloud providers
- [14 — Consistency and Partition Behavior](architecture/14-consistency-and-partitions.md) — per-operation consistency/partition contract and the mechanism earning each guarantee
- [15 — Resource and Isolation Model](architecture/15-resource-and-isolation-model.md) — single-JVM node model and the per-slice isolation limits that follow from it
- [Resilience & Operability Principles](architecture/resilience-operability-principles.md) — adopted operability principles feeding the resilience workstream

## Slice Developers

Build applications on Aether. Start with the [section index](slice-developers/README.md).

- [Thinking in Slices](slice-developers/thinking-in-slices.md) — the functional-model on-ramp: no exceptions, no null, composition over control flow
- [Getting Started](slice-developers/getting-started.md) — your first slice in 5 minutes
- [Slice Patterns](slice-developers/slice-patterns.md) — writing slices with JBCT patterns, including built-in infrastructure services
- [Testing Slices](slice-developers/testing-slices.md) — unit and integration testing
- [Deployment](slice-developers/deployment.md) — blueprints, environments, CI/CD
- [Persistence Guide](slice-developers/persistence-guide.md) — type-safe PostgreSQL persistence adapters (AEP)
- [PostgreSQL LISTEN/NOTIFY Integration](slice-developers/pg-notifications.md) — event-driven notifications from Postgres
- [API Versioning & Media Types](slice-developers/api-versioning-and-media-types.md) — per-route content types and API evolution via `routes.toml`
- [Resource Reference](slice-developers/resource-reference.md) — how resource provisioning works
- [Forge Guide](slice-developers/forge-guide.md) — local development and chaos testing
- [FAQ](slice-developers/faq.md) — frequently asked questions
- [Troubleshooting](slice-developers/troubleshooting.md) — common issues and solutions
- [Migration Guide](slice-developers/migration-guide.md) — moving from monolith to slices
- [Demos](slice-developers/demos.md) — example applications

## Reference

API specifications, CLI commands, and configuration options.

- [Feature Catalog](reference/feature-catalog.md) — complete feature inventory with status tracking
- [Known Limitations & Current Scope](reference/known-limitations.md) — single source of truth for current scope and deliberate boundaries
- [Failure Almanac](reference/failure-almanac.md) — operator catalog of every known failure mode: symptoms, surfaces, recovery budgets
- [Node Operations](reference/node-operations.md) — node process exit codes and shutdown-bound behavior
- [Guarantees](reference/guarantees.md) — authoritative per-operation consistency/durability/delivery guarantees
- [Slice API](reference/slice-api.md) — `@Slice` annotation, manifests, Maven plugin
- [Management API](reference/management-api.md) — HTTP API for cluster management
- [CLI](reference/cli.md) — command-line tools
- [Configuration](reference/configuration.md) — all configuration options
- [Bootstrap Config](reference/bootstrap-config.md) — cluster bootstrap configuration reference
- [Configuration Provisioning](reference/config-provisioning.md) — application configuration provisioning
- [Timeout Configuration](reference/timeout-configuration.md) — timeout settings across all subsystems
- [Cloud Integration](reference/cloud-integration.md) — configuring Aether for cloud deployment: provisioning, discovery, load balancing, secrets
- [Streaming Performance Analysis](reference/streaming-performance-analysis.md) — streams architecture and performance design notes
- [Examples](reference/examples/) — sample client code (`curl`, Python)

## Operators

Deploy, monitor, and maintain Aether clusters.

- [Scaling](operators/scaling.md) — auto-scaling configuration and behavior
- [TTM Guide](ttm-guide.md) — predictive auto-scaling with TTM
- [Monitoring](operators/monitoring.md) — alerts and thresholds
- [Networking Requirements](operators/networking.md) — QUIC transport and inter-node connectivity requirements
- [TLS Certificate Management](operators/tls-certificates.md) — certificate provisioning and rotation for inter-node TLS
- [Docker Deployment](operators/docker-deployment.md) — container-based deployment
- [Current Docker Setup](operators/current-docker-setup.md) — current container configuration
- [Deployment Guide](guides/deploy-guide.md) — zero-downtime deployments (canary, blue-green, rolling)
- [Rolling Cluster Upgrade](guides/rolling-upgrade.md) — zero-downtime cluster upgrades via the rolling upgrade script
- [Deployment Recovery](operators/deployment-recovery.md) — recovering from failed or interrupted deployments
- [Multi-Cluster Deployment](operators/multi-cluster-deployment.md) — running and coordinating multiple clusters
- [VM Snapshot](operators/vm-snapshot.md) — snapshot-based VM provisioning
- [Artifact Repository](operators/artifact-repository.md) — slice artifact management
- [Infrastructure Design](operators/infrastructure-design.md) — infrastructure architecture

### Runbooks

- [Runbooks Index](operators/runbooks/README.md) — operational procedures overview
- [Deployment](operators/runbooks/deployment.md) — deployment procedures
- [Incident Response](operators/runbooks/incident-response.md) — incident handling procedures
- [Backup & Recovery](operators/runbooks/backup-recovery.md) — backup and recovery procedures
- [Scaling](operators/runbooks/scaling.md) — scaling operations
- [Troubleshooting](operators/runbooks/troubleshooting.md) — operational troubleshooting
- [Lifecycle Verification](operators/runbooks/lifecycle-verification.md) — slice lifecycle verification

## Contributors

Extend and maintain the Aether platform internals.

- [Concepts](contributors/concepts.md) — core concepts and philosophy
- [Slice Architecture](contributors/slice-architecture.md) — code generation, packaging, manifests
- [Slice Runtime](contributors/slice-runtime.md) — how slices execute in Aether
- [Slice Loading](contributors/slice-loading.md) — classloading and slice isolation
- [Slice Lifecycle](contributors/slice-lifecycle.md) — states and transitions
- [Consensus](contributors/consensus.md) — Rabia protocol implementation
- [HTTP Routing](contributors/http-routing.md) — request routing and forwarding
- [Envelope Format Versioning](contributors/envelope-versioning.md) — the invocation envelope wire format and its versioning scheme
- [Metrics Control](contributors/metrics-control.md) — observability and AI integration
- [Invocation Metrics](contributors/invocation-metrics.md) — slice invocation metrics collection
- [TTM Integration](contributors/ttm-integration.md) — time-to-metric integration details
- [Node Implementation](contributors/aether-node.md) — AetherNode internals
- [Evolutionary Implementation Protocol](contributors/evolutionary-implementation.md) — the incremental build-and-validate protocol used for new subsystems
- [Test Charter Template](contributors/test-charter-template.md) — template for per-suite test charters (`TC-<SUITE>-<NUMBER>` convention)

For a system design overview, see [Architecture](#architecture) above — the former `contributors/architecture.md` overview has been retired in favor of the [architecture/](architecture/) series, which is kept current with shipped code.

## Specs

Design and implementation specifications for Aether subsystems — active, archived, and designed-only. See the [specs index](specs/README.md) for the full breakdown, including [archive/](specs/archive/) (superseded designs) and [future/](specs/future/) (designed-only, not in RC1).

## Articles

- [Introduction to Aether](articles/aether-introduction.md) — what Aether is and why it exists

## Archive

Historical design documents preserved for reference. See the [archive index](archive/README.md).
