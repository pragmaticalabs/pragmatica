# Operational Runbooks

Operational procedures for managing Aether clusters in production.

## Overview

Step-by-step runbooks for common operational tasks including incident response, scaling, troubleshooting, and deployment.

## Runbooks

| Runbook | Description |
|---------|-------------|
| [Incident Response](incident-response.md) | Procedures for handling production incidents |
| [Scaling](scaling.md) | Manual scaling procedures for nodes and slices |
| [Troubleshooting](troubleshooting.md) | Common issues and diagnostic steps |
| [Deployment](deployment.md) | Deployment and upgrade procedures |

## Quick Reference

### Health Check Endpoints

```bash
curl http://node:8080/health          # Health status
curl http://node:8080/metrics         # Node metrics
curl http://node:8080/cluster/status  # Cluster status
```

### Key Metrics

| Metric | Warning | Critical |
|--------|---------|----------|
| CPU usage | > 70% | > 90% |
| Heap usage | > 75% | > 90% |
| Response time | > 500ms | > 2000ms |
| Quorum | degraded | lost |
