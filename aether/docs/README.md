# Aether Documentation

Central hub for all Aether distributed runtime documentation.

## Architecture

High-level design and visual overviews of the Aether platform.

- [Aether Overview](aether-overview.md) - Platform overview and core concepts
- [Architecture Diagrams](architecture-diagrams.md) - Visual system architecture

## Slice Developers

Build applications on Aether. Start with the [section index](slice-developers/README.md).

- [Getting Started](slice-developers/getting-started.md) - Your first slice in 5 minutes
- [Development Guide](slice-developers/development-guide.md) - Complete development workflow
- [Slice Patterns](slice-developers/slice-patterns.md) - Writing slices with JBCT patterns
- [Testing Slices](slice-developers/testing-slices.md) - Unit and integration testing
- [Deployment](slice-developers/deployment.md) - Blueprints, environments, CI/CD
- [Infrastructure Services](slice-developers/infra-services.md) - Built-in platform services
- [Forge Guide](slice-developers/forge-guide.md) - Local development and chaos testing
- [Troubleshooting](slice-developers/troubleshooting.md) - Common issues and solutions
- [Migration Guide](slice-developers/migration-guide.md) - Moving from monolith to slices
- [Demos](slice-developers/demos.md) - Example applications

## Reference

API specifications, CLI commands, and configuration options.

- [Feature Catalog](reference/feature-catalog.md) - Complete feature inventory with status tracking
- [Slice API](reference/slice-api.md) - `@Slice` annotation, manifests, Maven plugin
- [Management API](reference/management-api.md) - HTTP API for cluster management
- [CLI](reference/cli.md) - Command-line tools
- [Configuration](reference/configuration.md) - All configuration options

## Operators

Deploy, monitor, and maintain Aether clusters.

- [Scaling](operators/scaling.md) - Auto-scaling configuration and behavior
- [TTM Guide](ttm-guide.md) - Predictive auto-scaling with TTM
- [Monitoring](operators/monitoring.md) - Alerts and thresholds
- [Docker Deployment](operators/docker-deployment.md) - Container-based deployment
- [Current Docker Setup](operators/current-docker-setup.md) - Current container configuration
- [Rolling Updates](operators/rolling-updates.md) - Zero-downtime deployments
- [Artifact Repository](operators/artifact-repository.md) - Slice artifact management
- [Infrastructure Design](operators/infrastructure-design.md) - Infrastructure architecture

### Runbooks

- [Runbooks Index](operators/runbooks/README.md) - Operational procedures overview
- [Deployment](operators/runbooks/deployment.md) - Deployment procedures
- [Incident Response](operators/runbooks/incident-response.md) - Incident handling procedures
- [Scaling](operators/runbooks/scaling.md) - Scaling operations
- [Troubleshooting](operators/runbooks/troubleshooting.md) - Operational troubleshooting
- [Lifecycle Verification](runbooks/lifecycle-verification.md) - Slice lifecycle verification

## Contributors

Extend and maintain the Aether platform internals.

- [Architecture](contributors/architecture.md) - System design overview
- [Concepts](contributors/concepts.md) - Core concepts and philosophy
- [Slice Architecture](contributors/slice-architecture.md) - Code generation, packaging, manifests
- [Slice Runtime](contributors/slice-runtime.md) - How slices execute in Aether
- [Slice Loading](contributors/slice-loading.md) - Classloading and slice isolation
- [Slice Lifecycle](contributors/slice-lifecycle.md) - States and transitions
- [Consensus](contributors/consensus.md) - Rabia protocol implementation
- [HTTP Routing](contributors/http-routing.md) - Request routing and forwarding
- [Metrics Control](contributors/metrics-control.md) - Observability and AI integration
- [Invocation Metrics](contributors/invocation-metrics.md) - Slice invocation metrics collection
- [TTM Integration](contributors/ttm-integration.md) - Time-to-metric integration details
- [Node Implementation](contributors/aether-node.md) - AetherNode internals

## Articles

- [Introduction to Aether](articles/aether-introduction.md) - What Aether is and why it exists

## Internal

Development tracking and internal tooling notes. Not intended for external audiences.

- [Development Priorities](internal/progress/development-priorities.md) - Current development priorities
- [Infra Slices Progress](internal/progress/infra-slices-progress.md) - Infrastructure slices tracker
- [E2E Port Allocation](internal/e2e-port-allocation.md) - Port allocation for E2E tests

## Archive

Historical design documents preserved for reference. See the [archive index](archive/README.md).
