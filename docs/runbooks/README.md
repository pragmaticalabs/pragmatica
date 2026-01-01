# Aether Operational Runbooks

Operational procedures for managing Aether clusters in production.

## Runbooks

| Runbook | Description |
|---------|-------------|
| [Incident Response](incident-response.md) | Procedures for handling production incidents |
| [Scaling](scaling.md) | Manual scaling procedures for nodes and slices |
| [Troubleshooting](troubleshooting.md) | Common issues and diagnostic steps |
| [Deployment](deployment.md) | Deployment and upgrade procedures |

## Quick Reference

### Health Check Endpoints

Each node exposes a management API (default port 8080):

```bash
# Health status
curl http://node:8080/health

# Node metrics
curl http://node:8080/metrics

# Cluster status
curl http://node:8080/cluster/status
```

### CLI Commands

```bash
# Connect to cluster
aether-cli --host node1:8080

# Check cluster health
aether> status

# List deployed slices
aether> slices list

# Deploy a slice
aether> deploy org.example:my-slice:1.0.0
```

### Key Metrics to Monitor

- `cpu.usage` - Node CPU utilization (0.0-1.0)
- `heap.usage` - Heap memory utilization (0.0-1.0)
- `method.*.calls` - Request count per method
- `method.*.duration.avg` - Average response time per method

### Alert Thresholds

| Metric | Warning | Critical |
|--------|---------|----------|
| CPU usage | > 70% | > 90% |
| Heap usage | > 75% | > 90% |
| Response time | > 500ms | > 2000ms |
| Quorum | degraded | lost |
