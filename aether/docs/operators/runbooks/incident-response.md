# Incident Response Runbook

> **This runbook is the *procedures* (how to diagnose and act). For the *catalog* of every known failure mode — symptoms, the exact operator surfaces, the automatic recovery behavior and its budget, and when intervention is needed — see the [Failure Almanac](../../reference/failure-almanac.md).**

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
3. Replace unresponsive nodes (`POST /api/v1/nodes/replace/<node-id>`, or CTM auto-heal); a restart under the same id is refused
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
1. If process is running but unresponsive, collect thread dump then replace the node (`POST /api/v1/nodes/replace/<node-id>`)
2. If process crashed, check logs; the node is replaced under a fresh id by CTM auto-heal (a crashed node id never returns)
3. If resource exhaustion, free resources, then replace the node

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

### Stream Partition Without an Owner (`STREAM_FAILOVER_REFUSED`)

**Symptoms:** a CRITICAL `STREAM_FAILOVER_REFUSED` event; publishes and reads to one stream partition fail.

**Cause:** the partition's owner is dead and no member of its in-sync replica set is live. Failover only elects an in-sync replica (unclean failover is off), so the partition waits.

**Actions:**

1. Read the event's `details`: `isr` lists the nodes that hold every acknowledged record; `live` lists who was alive at refusal.
2. Reconnect any live node in `isr` (heal the network fault; a node that was terminally removed does not return). The leader elects it (or the old owner resumes) and a `STREAM_FAILOVER_RESOLVED` event follows.
3. If every node in `isr` is permanently gone, the partition's acknowledged data is gone with them. There is no override to promote another replica yet (#1569). Destroy and recreate the stream.

### Stream Publishes Refused With `NOT_ENOUGH_REPLICAS` (`STREAM_ISR_BELOW_MINIMUM`)

**Symptoms:** a WARNING `STREAM_ISR_BELOW_MINIMUM` event; publishes to one stream partition fail before the append, reads still work.

**Cause:** the partition's in-sync replica set holds fewer members than the stream's `confirmation_factor`.

**Actions:**

1. Read the event's `details`: `isr` lists the members still in sync, `fenced` the members the leader keeps out because it does not see them live.
2. Bring the missing replica node back. When the leader sees it live it is unfenced, the owner expands it once it has caught up, and `STREAM_ISR_RESTORED` follows.
3. If the replica is gone for good, replace the node; the replacement joins the ISR after backfill. To tolerate the loss of one replica without refusing writes, declare `confirmation_factor` below `replication_factor`. On a running stream lowering `confirmation_factor` is not applied (durability only increases online; a committed lowering raises `STREAM_CONFIG_CHANGE_NOT_APPLIED`): restore the replicas, or re-create the stream.

### Stream Consumer Skipped Reclaimed Records (`stream-consumer-cursor-expired`)

**Symptoms:** a CRITICAL `OPERATOR_WARNING` whose `details.code` is `stream-consumer-cursor-expired`; `details.subject` is `stream[partition]/group@offset`.

**Cause:** the group's cursor pointed at records that the partition's owner no longer holds in its ring or sealed tier (reclaimed by retention, the expected cause; the warning reports what the owner holds, not why). The records are gone from the owner, so the group was moved forward once to the earliest retained offset and the range was not delivered. This is the one case in which a declarative consumer skips records; a checkpoint of the new cursor was requested at once, so a restart resumes there once it persists.

**Actions:**

1. Read the message: it names the group, the partition, the skipped range `[from, to)` and its count. Nothing reopens the range; the warning has no recovery event.
2. If the records matter, replay that range from its source into the stream.
3. Stop it recurring: raise the stream's retention, or give the group more capacity so it keeps up. A group that falls behind the ring first raises `stream-consumer-behind-ring`; acting on that is the early warning.

### Stream Consumer Waiting at a Hole (`stream-consumer-sealed-range-missing`)

**Symptoms:** a WARNING `OPERATOR_WARNING` with `details.code` `stream-consumer-sealed-range-missing`; the group delivers nothing past one offset.

**Cause:** the offset is in no sealed segment while a later offset is sealed, and retention did not reclaim it: a hole in the sealed range. The group never skips a hole; it re-reads with the poll backoff.

**Actions:**

1. Read the message: it names the group, the partition and the missing range `[from, to]`.
2. Wait briefly: a range whose seal was still in flight lands on its own, and the group resumes.
3. If it persists, treat it as a defect report (#1234) and escalate. There is no operator command to repair a hole in place. The partition's WAL still holds the range, since WAL truncation never passes a hole `[design intent — unverified: no run has exercised a hole on a cluster]`. If nothing fills it, the group does not resume on its own: retention never reclaims at or above a hole (its bound stops at the contiguous sealed watermark, which stops below the hole), so the hole is never turned into a skip.
4. `stream-consumer-sealed-range-restored` (INFO, same subject) confirms the end: a read from that offset succeeded, the cursor moved off it (a rewind), or the consumer was cancelled.

### Stream Consumer Behind the Ring (`stream-consumer-behind-ring`)

**Symptoms:** a WARNING `OPERATOR_WARNING` with `details.code` `stream-consumer-behind-ring`, subject `stream[partition]/group`.

**Cause:** the group's cursor is below the partition's in-memory ring, so it reads from the sealed tier (slower, still complete). A new group that starts at `earliest` on a stream with sealed history raises it once by design.

**Actions:**

1. None if `stream-consumer-back-in-ring` (INFO, same subject) follows: the group caught up.
2. If it persists, the group is slower than the stream; add capacity before retention reaches its position, or it will skip records (`stream-consumer-cursor-expired`).
3. A group on a node that does not hold the partition reads through the owner, and learns whether it read the tier from the owner's ring tail as the owner reports it just after the read. A read served from the ring at its very edge while the ring evicted can therefore raise a spurious `stream-consumer-behind-ring` / `stream-consumer-back-in-ring` pair, and a group that keeps reading at the ring's edge can raise such a pair again on later reads. A pair that closes at once on such a group is this edge, not lag.

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
2. If heap high: increase heap, then replace the affected nodes one at a time (`POST /api/v1/nodes/replace/<node-id>`); a restart under the same id is refused
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
