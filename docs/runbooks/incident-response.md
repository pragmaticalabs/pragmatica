# Incident Response Runbook

## Severity Levels

| Level | Description | Response Time | Example |
|-------|-------------|---------------|---------|
| SEV1 | Complete outage | Immediate | Cluster unreachable, quorum lost |
| SEV2 | Partial outage | 15 min | Single node down, slice unavailable |
| SEV3 | Degraded | 1 hour | High latency, resource pressure |
| SEV4 | Minor | Next business day | Non-critical errors in logs |

## Initial Assessment

### 1. Check Cluster Health

```bash
# Quick health check from any node
curl http://node1:8080/health

# Expected healthy response:
# {"status":"healthy","quorum":true,"nodeCount":3,"sliceCount":5}

# Degraded response (no slices):
# {"status":"degraded","quorum":true,"nodeCount":3,"sliceCount":0}

# Unhealthy response (no quorum):
# {"status":"unhealthy","quorum":false,"nodeCount":1,"sliceCount":2}
```

### 2. Check Individual Nodes

```bash
# Check each node
for node in node1 node2 node3; do
  echo "=== $node ==="
  curl -s http://$node:8080/health || echo "UNREACHABLE"
done
```

### 3. Check Logs

```bash
# Recent errors
grep -i error /var/log/aether/aether.log | tail -50

# Consensus issues
grep -i "quorum\|leader\|rabia" /var/log/aether/aether.log | tail -50
```

## Common Incidents

### Quorum Lost

**Symptoms:** Health check returns `quorum: false`, consensus operations fail

**Diagnosis:**
```bash
# Count reachable nodes
for node in node1 node2 node3; do
  curl -s http://$node:8080/health && echo " - $node OK"
done | grep OK | wc -l
```

**Resolution:**
1. Identify unreachable nodes
2. Check network connectivity between nodes
3. Restart unresponsive nodes
4. Verify cluster port (default: 8090) is accessible

### Node Unresponsive

**Symptoms:** Node doesn't respond to health checks, other nodes report it as disconnected

**Diagnosis:**
```bash
# Check process
ssh node1 "ps aux | grep aether"

# Check port binding
ssh node1 "netstat -tlnp | grep 8080"

# Check disk space
ssh node1 "df -h"

# Check memory
ssh node1 "free -m"
```

**Resolution:**
1. If process is running but unresponsive, collect thread dump then restart
2. If process crashed, check logs and restart
3. If resource exhaustion, free resources then restart

### Slice Deployment Stuck

**Symptoms:** Slice stays in LOADING or ACTIVATING state

**Diagnosis:**
```bash
# Check slice state
curl http://node1:8080/slices | jq '.[] | select(.state != "ACTIVE")'

# Check deployment logs
grep -i "slice\|artifact" /var/log/aether/aether.log | tail -100
```

**Resolution:**
1. Check artifact is available in repository
2. Verify slice dependencies are satisfied
3. Force undeploy and redeploy if stuck:
   ```bash
   aether> undeploy org.example:stuck-slice:1.0.0 --force
   aether> deploy org.example:stuck-slice:1.0.0
   ```

### High Latency

**Symptoms:** Slow response times, `method.*.duration.avg` metrics elevated

**Diagnosis:**
```bash
# Check CPU and memory
curl http://node1:8080/metrics | jq '.["cpu.usage"], .["heap.usage"]'

# Check method-level latency
curl http://node1:8080/metrics | jq 'to_entries | .[] | select(.key | contains("duration.avg"))'
```

**Resolution:**
1. If CPU high: scale out (add nodes) or reduce load
2. If heap high: increase heap or restart nodes
3. If specific method slow: investigate slice implementation

## Escalation

| Severity | First Responder | Escalate To |
|----------|-----------------|-------------|
| SEV1 | On-call engineer | Engineering lead + management |
| SEV2 | On-call engineer | Team lead |
| SEV3 | On-call engineer | - |
| SEV4 | Any team member | - |

## Post-Incident

1. Document timeline and actions taken
2. Identify root cause
3. Create follow-up tickets for improvements
4. Update runbooks if needed
