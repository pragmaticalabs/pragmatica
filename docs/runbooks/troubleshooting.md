# Troubleshooting Runbook

## Diagnostic Commands

### Cluster Health

```bash
# Overall health
curl http://node:8080/health

# Detailed cluster status
curl http://node:8080/cluster/status

# Node-specific metrics
curl http://node:8080/metrics
```

### Slice Status

```bash
# List all slices
curl http://node:8080/slices

# Slice details
curl http://node:8080/slices/org.example:my-slice:1.0.0

# Active routes
curl http://node:8080/routes
```

### Log Analysis

```bash
# Recent errors
grep -i "error\|exception\|fail" /var/log/aether/aether.log | tail -100

# Consensus activity
grep -i "rabia\|propose\|commit" /var/log/aether/aether.log | tail -50

# Slice lifecycle
grep -i "load\|activate\|deactivate" /var/log/aether/aether.log | tail -50
```

## Common Issues

### Issue: Node Won't Start

**Symptoms:** Process exits immediately or fails to bind ports

**Checklist:**
1. Check port availability
   ```bash
   netstat -tlnp | grep -E "8080|8090"
   ```

2. Check Java version (requires Java 21+)
   ```bash
   java -version
   ```

3. Check file permissions
   ```bash
   ls -la /var/lib/aether/
   ```

4. Check logs for startup errors
   ```bash
   tail -100 /var/log/aether/aether.log
   ```

### Issue: Nodes Can't Connect to Each Other

**Symptoms:** Quorum not established, nodes report each other as disconnected

**Checklist:**
1. Verify network connectivity
   ```bash
   # From node1 to node2
   nc -zv node2 8090
   ```

2. Check firewall rules
   ```bash
   iptables -L -n | grep 8090
   ```

3. Verify TLS configuration (if using TLS)
   ```bash
   openssl s_client -connect node2:8090
   ```

4. Check peer configuration
   ```bash
   # Verify --peers argument matches actual node addresses
   ps aux | grep aether | grep peers
   ```

### Issue: Slice Won't Deploy

**Symptoms:** Slice stays in LOADING state, deployment errors in logs

**Checklist:**
1. Verify artifact exists in repository
   ```bash
   curl http://repository/org/example/my-slice/1.0.0/my-slice-1.0.0.jar -I
   ```

2. Check artifact format
   ```bash
   # JAR must have Slice-Class manifest attribute
   unzip -p my-slice-1.0.0.jar META-INF/MANIFEST.MF | grep Slice
   ```

3. Check for class loading errors
   ```bash
   grep -i "classnotfound\|linkage\|nosuchmethod" /var/log/aether/aether.log
   ```

4. Verify dependencies
   ```bash
   # List dependencies in slice JAR
   jar tf my-slice-1.0.0.jar | grep -v "^META"
   ```

### Issue: Slice Activation Fails

**Symptoms:** Slice moves from LOADED to FAILED during activation

**Checklist:**
1. Check activation logs
   ```bash
   grep -i "activat\|FAILED" /var/log/aether/aether.log | tail -50
   ```

2. Verify slice's start() method doesn't throw
   ```bash
   # Look for exception during start
   grep -A 10 "Error in slice start" /var/log/aether/aether.log
   ```

3. Check resource availability (ports, files, connections)

### Issue: High Memory Usage

**Symptoms:** Heap usage > 80%, OutOfMemoryError in logs

**Checklist:**
1. Check current heap usage
   ```bash
   curl http://node:8080/metrics | jq '.["heap.used"], .["heap.max"]'
   ```

2. Generate heap dump
   ```bash
   jmap -dump:format=b,file=heap.hprof $(pgrep -f aether)
   ```

3. Analyze with MAT or VisualVM

4. Temporary fix: restart node
   ```bash
   systemctl restart aether
   ```

### Issue: Slow Response Times

**Symptoms:** High `method.*.duration.avg` metrics

**Checklist:**
1. Identify slow methods
   ```bash
   curl http://node:8080/metrics | jq 'to_entries | .[] | select(.key | contains("duration.avg")) | select(.value > 100)'
   ```

2. Check CPU usage
   ```bash
   curl http://node:8080/metrics | jq '.["cpu.usage"]'
   ```

3. Check for GC pressure
   ```bash
   grep -i "gc\|pause" /var/log/aether/aether.log | tail -20
   ```

4. Check network latency between nodes
   ```bash
   ping -c 10 node2
   ```

### Issue: Split Brain

**Symptoms:** Different nodes report different cluster states

**Checklist:**
1. Check each node's view
   ```bash
   for node in node1 node2 node3; do
     echo "=== $node ==="
     curl -s http://$node:8080/cluster/status | jq '.nodes'
   done
   ```

2. Verify network connectivity between all nodes
   ```bash
   # Test all pairs
   for src in node1 node2 node3; do
     for dst in node1 node2 node3; do
       ssh $src "nc -zv $dst 8090 2>&1"
     done
   done
   ```

3. Resolution: Restart minority partition nodes

## Log Levels

To increase logging temporarily:

```bash
# Via management API
curl -X POST http://node:8080/admin/logging -d 'level=DEBUG&logger=org.pragmatica.aether'

# Reset to normal
curl -X POST http://node:8080/admin/logging -d 'level=INFO&logger=org.pragmatica.aether'
```

## Getting Help

1. Collect diagnostic information:
   ```bash
   tar czf diagnostics.tar.gz \
     /var/log/aether/*.log \
     /etc/aether/*.yaml
   ```

2. Include:
   - Node configuration
   - Recent logs
   - Steps to reproduce
   - Expected vs actual behavior
