# Aether Documentation

Documentation hub for the Aether distributed runtime.

## Overview

Organized by audience: slice developers building applications on Aether, core contributors extending the platform, and operators deploying clusters.

## Slice Developers

Build applications on Aether. Start here: [Overview](slice-developers/README.md)

1. [Getting Started](slice-developers/getting-started.md) - Your first slice in 5 minutes
2. [Development Guide](slice-developers/development-guide.md) - Complete development workflow
3. [Slice Patterns](slice-developers/slice-patterns.md) - Writing slices with JBCT patterns
4. [Testing Slices](slice-developers/testing-slices.md) - Unit and integration testing
5. [Deployment](slice-developers/deployment.md) - Blueprints, environments, CI/CD
6. [Infrastructure Services](slice-developers/infra-services.md) - Built-in platform services
7. [Forge Guide](slice-developers/forge-guide.md) - Local development and chaos testing
8. [Troubleshooting](slice-developers/troubleshooting.md) - Common issues and solutions
9. [Migration Guide](slice-developers/migration-guide.md) - Moving from monolith to slices
10. [Demos](slice-developers/demos.md) - Example applications

## Core Contributors

Extend and maintain Aether:

1. [Architecture](contributors/architecture.md) - System design overview
2. [Concepts](contributors/concepts.md) - Core concepts and philosophy
3. [Slice Architecture](contributors/slice-architecture.md) - Code generation, packaging, manifests
4. [Slice Runtime](contributors/slice-runtime.md) - How slices execute in Aether
5. [Slice Lifecycle](contributors/slice-lifecycle.md) - States and transitions
6. [Consensus](contributors/consensus.md) - Rabia protocol implementation
7. [HTTP Routing](contributors/http-routing.md) - Request routing and forwarding
8. [Metrics and Control](contributors/metrics-control.md) - Observability and AI integration
9. [Node Implementation](contributors/aether-node.md) - AetherNode internals

## Operators

Deploy and run Aether clusters:

1. [Scaling](operators/scaling.md) - Auto-scaling configuration and behavior
2. [TTM Guide](ttm-guide.md) - Predictive auto-scaling
3. [Monitoring](operators/monitoring.md) - Alerts and thresholds
4. [Docker Deployment](operators/docker-deployment.md) - Container-based deployment
5. [Rolling Updates](operators/rolling-updates.md) - Zero-downtime deployments
6. [Artifact Repository](operators/artifact-repository.md) - Slice artifact management
7. [Runbooks](operators/runbooks/README.md) - Operational procedures

## Quick Reference

| Document | Description |
|----------|-------------|
| [Slice API Reference](reference/slice-api.md) | `@Slice` annotation, manifests, Maven plugin |
| [CLI Reference](reference/cli.md) | Command-line tools |
| [Management API](reference/management-api.md) | HTTP API for cluster management |
| [Configuration](reference/configuration.md) | All configuration options |
