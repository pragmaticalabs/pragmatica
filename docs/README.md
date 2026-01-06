# Aether Documentation

Welcome to the Aether Distributed Runtime documentation. This guide helps you find the right documentation for your role and needs.

## Quick Start

| I want to... | Start here |
|--------------|------------|
| Learn what Aether is | [Introduction](guide/introduction.md) |
| Deploy my first slice | [Getting Started](guide/getting-started.md) |
| Write a slice | [Slice Developer Guide](slice-developer-guide.md) |
| Run a local cluster | [Forge Guide](guide/forge-guide.md) |
| Deploy to production | [Docker Deployment](guide/docker-deployment.md) |
| Troubleshoot issues | [Troubleshooting](runbooks/troubleshooting.md) |

## Documentation by Role

### For Developers

Learn to build and deploy slices on Aether:

1. [Introduction](guide/introduction.md) - What is Aether and why use it
2. [Getting Started](guide/getting-started.md) - Your first slice in 10 minutes
3. [Slice Developer Guide](slice-developer-guide.md) - Writing slices with JBCT patterns
4. [Slice Lifecycle](slice-lifecycle.md) - Understanding slice states and transitions
5. [Scaling](guide/scaling.md) - How automatic scaling works

### For Operators

Deploy and manage Aether clusters:

1. [Docker Deployment](guide/docker-deployment.md) - Container-based deployment
2. [Configuration Reference](guide/configuration-reference.md) - All configuration options
3. [CLI Reference](guide/cli-reference.md) - Command-line tools
4. [Management API](api/management-api.md) - HTTP API reference

**Runbooks:**
- [Deployment](runbooks/deployment.md) - Production deployment checklist
- [Scaling](runbooks/scaling.md) - Scaling operations
- [Incident Response](runbooks/incident-response.md) - Handling incidents
- [Troubleshooting](runbooks/troubleshooting.md) - Common issues and solutions

### For Architects

Understand Aether's design and capabilities:

1. [Vision and Goals](vision-and-goals.md) - Why Aether exists
2. [Architecture Overview](architecture-overview.md) - System design
3. [Metrics and Control](metrics-and-control.md) - Observability and AI integration
4. [Infrastructure Services](infrastructure-services.md) - Built-in platform services

## Feature Guides

### Operational Features

- [Rolling Updates](guide/rolling-updates.md) - Zero-downtime deployments
- [Alerts and Thresholds](guide/alerts-and-thresholds.md) - Monitoring and alerting
- [Migration Guide](guide/migration-guide.md) - Moving from monolith to slices

### Testing and Development

- [Forge Guide](guide/forge-guide.md) - Local development environment
- [E2E Testing](guide/e2e-testing.md) - End-to-end test infrastructure

## Reference

| Document | Description |
|----------|-------------|
| [CLI Reference](guide/cli-reference.md) | Complete command-line reference |
| [Management API](api/management-api.md) | HTTP API for cluster management |
| [Configuration Reference](guide/configuration-reference.md) | All configuration options |
| [Invocation Metrics](invocation-metrics.md) | Per-method metrics specification |

## Version

This documentation is for **Aether v0.7.2**.

## Getting Help

- [Troubleshooting Guide](runbooks/troubleshooting.md)
- [GitHub Issues](https://github.com/siy/aether/issues)
