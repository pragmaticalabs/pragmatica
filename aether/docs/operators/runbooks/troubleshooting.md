# Troubleshooting Runbook

## Diagnostic Commands

### Cluster Health

```bash
# Overall health
curl http://node:8080/api/v1/health

# Detailed cluster status
curl http://node:8080/api/v1/cluster/status

# Node-specific metrics
curl http://node:8080/api/v1/metrics
```

### Slice Status

```bash
# List all slices
curl http://node:8080/api/v1/slices

# Slice instance states
curl http://node:8080/api/v1/slices/status

# Active routes
curl http://node:8080/api/v1/routes
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

2. Check Java version (requires Java 25+)
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
   curl http://node:8080/api/v1/metrics | jq '.["heap.used"], .["heap.max"]'
   ```

2. Generate heap dump
   ```bash
   jmap -dump:format=b,file=heap.hprof $(pgrep -f aether)
   ```

3. Analyze with MAT or VisualVM

4. Temporary fix: replace the node. A restart under the same id is refused (a node id never
   returns); the replacement joins under a new id and the old node drains and retires
   ```bash
   curl -X POST http://node:8080/api/v1/nodes/replace/<node-id>
   ```

### Issue: Slow Response Times

**Symptoms:** High `method.*.duration.avg` metrics

**Checklist:**
1. Identify slow methods
   ```bash
   curl http://node:8080/api/v1/metrics | jq 'to_entries | .[] | select(.key | contains("duration.avg")) | select(.value > 100)'
   ```

2. Check CPU usage
   ```bash
   curl http://node:8080/api/v1/metrics | jq '.["cpu.usage"]'
   ```

3. Check for GC pressure
   ```bash
   grep -i "gc\|pause" /var/log/aether/aether.log | tail -20
   ```

4. Check network latency between nodes
   ```bash
   ping -c 10 node2
   ```

### Issue: `cluster apply --resume` Failed Part-Way

The wave rollout behind `--resume` is not yet started by plain `cluster apply` (#686), which performs scale-only writes; this applies to a halted wave rollout. `--resume` continues from the apply ledger (it looks up every node the ledger intended before creating anything), and `--rollback` terminates what the ledger recorded, newest first.

The failing step's VMs are destroyed. The error names any VM still running and billed (a failed destroy, or one kept from an earlier step), each with its removal steps: remove each `STILL RUNNING AND BILLED` VM as printed, and keep or remove each `kept` one (see `aether cluster apply` in `reference/cli.md`).

### Issue: Split Brain

**Symptoms:** Different nodes report different cluster states

**Checklist:**
1. Check each node's view
   ```bash
   for node in node1 node2 node3; do
     echo "=== $node ==="
     curl -s http://$node:8080/api/v1/cluster/status | jq '.nodes'
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

3. Resolution: heal the network partition. A minority-side node that self-fenced has left for good
   (its id does not return); replace it under a fresh id with
   `POST /api/v1/nodes/replace/<node-id>`, or let CTM auto-heal do it. Replacement needs a committed
   quorum, so this holds only while a majority of the cores is alive

4. If the cores are gone (every core process exited with code 2, `reason=QUORUM_LOSS`), or no group of
   cores held a majority, healing the network does not bring them back and replacement cannot run. Follow
   [No-majority partition / every core halted](incident-response.md#no-majority-partition--every-core-halted)

### Issue: A Stream Partition's WAL Fail-Stopped

**Symptoms:**
- CRITICAL `storage-wal-fail-stopped` with the subject `stream[partition]`
- Every publish to that partition on the node is refused with `PartitionFailStopped`
- `wal.failStopped` is `true` for the partition; gauge `aether.streams.wal.failstopped` is above 0

**Cause:** the WAL's fsync, a frame write or a post-compaction reopen failed (often a full or read-only volume). The log stops
for good rather than retry, because a retried fsync can falsely succeed after the OS dropped the dirty pages.

**Resolution:**
1. Check the volume that holds the WAL (free space, read-only remount, disk errors in the kernel log).
2. Replace the node (`POST /api/v1/nodes/replace/<node-id>`). A node id never returns, so a single node cannot rejoin under its
   old id; the replacement joins under a new id and its partitions catch up from the other replicas. Only a whole-cluster
   restart, which keeps every node id, reopens the same WAL.

**How the alarm closes** — one of three events, each with the same `stream[partition]` subject:
- `storage-wal-retired` (INFO): the node that fail-stopped was removed or decommissioned, as when it is replaced. Its log is not
  reopened.
- `storage-wal-writable` (INFO): a whole-cluster restart reopened the same WAL under the same node id, and it accepted a durable
  write.
- `NODE_FAILED`: the node crashed and could publish nothing; the failure event supersedes the alarm.

Known limit: the subject names the partition, not the node. If two nodes fail-stop the same partition, the close from one node
also closes the other's alarm; check `wal.failStopped` on each node.

## Log Levels

To increase logging temporarily:

```bash
# Via management API (applies to the node you call; needs the ADMIN role, so add -H "X-API-Key: <key>" when API keys are on)
curl -X POST http://node:8080/api/v1/logging/levels -H "Content-Type: application/json" \
  -d '{"logger": "org.pragmatica.aether", "level": "DEBUG"}'

# Reset to normal (drops the override for that logger)
curl -X DELETE http://node:8080/api/v1/logging/levels/org.pragmatica.aether
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
