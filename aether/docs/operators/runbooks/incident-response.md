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

> **Every core process has exited, or no group of cores holds a majority?** Go to
> [No-majority partition / every core halted](#no-majority-partition--every-core-halted). The steps
> below assume a majority of the cores is alive and can reach each other.

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
3. Replace unresponsive nodes (`POST /api/v1/nodes/replace/<node-id>`, or CTM auto-heal); a restart under the same id is refused. Replacement needs a committed quorum (a capacity reservation is a KV transaction and the voter add is a Rabia reconfiguration), so this step works only while a majority of the cores is alive; with no majority, use the no-majority section below
4. Verify cluster port (default: 8090) is accessible

### No-majority partition / every core halted

**What this is.** A network event separated the core nodes so that no group of mutually reachable cores holds a majority (`cores/2 + 1`): a multi-way split, or a switch or route failure that cuts every core-to-core link. Rabia commits nothing on any side (writes return `QuorumPaused`). A core whose count of reachable cores stays below the threshold for the split timeout `T` (`split_timeout`, default 15 s) begins a self-drain (`reason=QUORUM_LOSS`) and exits with code 2. The drain is a single compare-and-set with no cancel path, so a heal after it has started does not save that node `[mechanism: DrainProcedure.initiate, DrainProcedure.java:186-199]`.

**How long a cut halts the cores.** Measured on five cores with every core isolated from every other (the shape "a cut where no group holds a majority, measured with every core isolated"; a 2/2/1 split was not run), five repetitions per cut length, 25 core-runs each:

| Cut length | Cores halted | Notes |
|---|---|---|
| 10 s | 0 of 25 | 0 of 25 halted; in one run the leader still saw a peer as Dead 60 s after the heal |
| 15 s | 2 of 25 | one core in each of 2 repetitions: the heal came before it fired, but its detector had not yet recovered, so it fired 3 to 4 s after the heal |
| 20 s, 25 s, 30 s | 25 of 25 each | every core in every repetition |

The drain begins 18 to 19 s after the cut starts in every case that fired: the quorum-loss window opens 3 to 4 s after the cut (failure detection), and the drain begins `T` (14.90 to 15.00 s measured) after that. Cuts of 20, 25 and 30 s halted every core in every repetition (longer cuts were not run); a cut of 10 s halted none; lengths between 15 s and 20 s were not run, so the outcome in that range is a race that this page does not predict. A heal never saved a core that had fired: 77 of 77 cores that fired exited with code 2 and none returned, including the 2 that fired after the heal. `[measured: five-node Ember probe on the rc4 tip, 5 repetitions per cut length; loopback on one host, so a real network adds probe latency; the probe is not part of the repository's test suite]`

**What is guaranteed and what is not.**

| Operation | During the event | After the halt |
|---|---|---|
| KV write / consensus commit | Refused (`QuorumPaused`) on every side: no side has a majority, so none can commit `[mechanism: Rabia quorum]` | Unavailable: no process is left |
| KV read, management reads | Served from each node's local applied state, possibly stale (not linearizable), until that node halts | Unavailable |
| New slice invocations on a core that began draining | Refused: admission closes when the drain starts; in-flight work may run until it settles or the 30 s grace expires `[mechanism: DrainProcedure.java:61-63,186-199]` | Unavailable |
| Slice re-placement | Not available: placing slices needs committed writes | n/a |
| Cluster state | In-memory consensus state is lost on every halted core. Only `[backup]` (if enabled and pushed) survives. Stream WAL files stay on disk but are not read under fresh NodeIds `[unverified: read from code, not run]` | |

Durability of consensus state here is process-durable only: it is replicated in memory across the cores and is lost when all cores exit. The measured halts ended the Ember node through its exit hook; a production halt happens between the drain start and the end of the 30 s grace, and that span was not measured `[unverified: production halt timing]`.

#### Symptoms

- Every core process is gone while the hosts are up. systemd units show `failed`, containers show `exited (2)`. The shipped launchers use `Restart=no` / `--restart no` on purpose: a self-fenced node must not return on its own `[mechanism: SystemdUnitTemplate.java:48, NodeUserDataRenderer.java:480]`.
- The exit code is **2 on every core. This is the same code as a planned, completed drain**, so the code alone does not tell you which happened. Distinguish them from the logs:
  - Quorum-loss halt: `DrainProcedure: DRAINING (reason=QUORUM_LOSS) ...`, preceded by a `QUORUM_LOSS drain INTENT firing` line from the quorum detector.
  - Planned drain: a different reason (`COMMANDED`, `CORE_ABSENCE`) in the same `DRAINING (reason=...)` line.
  - Exit code **78** is something else: the node's identity was refused (`FATAL: this node's identity <id> was refused by the cluster`).
- Health endpoints on the cores are unreachable and no events are emitted: no process is left to emit them.
- Workers and governors may still be running. They have lost their core and do not form a cluster on their own; stop them before recovering.

#### What NOT to do

- **Do not restart the halted cores under their old NodeIds while any core, worker or governor from the old cluster is still running.** Every live observer holds the old boot token and refuses the new process; it exits with code 78. This includes workers and governors, not only cores.
- Even when every process of the old cluster is stopped, restarting the same NodeIds with empty state is not a supported restart mode (#1543; [backup-recovery](backup-recovery.md#restarting-a-whole-cluster--a-regular-start-then-the-restore)).
- Do not use `POST /api/v1/nodes/replace/<node-id>` or wait for CTM auto-heal. Replacement needs a committed quorum; with no quorum it cannot run.
- Do not restore the same backup into two live clusters (`BACKUP_FORKED`, see [backup-recovery](backup-recovery.md)).
- Do not re-run `aether cluster bootstrap` with the halted cluster's name as a shortcut. See [re-running bootstrap](../../reference/cli.md#aether-cluster-bootstrap): a fresh bootstrap refuses a name that the registry, local state or (cloud sources) labelled machines show as in use, and `destroy` of a name removes every machine carrying the cluster's label.

#### Recovery with `[backup]` enabled before the event

Follows [backup-recovery](backup-recovery.md#restarting-a-whole-cluster--a-regular-start-then-the-restore). Prerequisite: `[backup] enabled = true` with a `remote` that is reachable now. The restore reads the remote; without a `remote` it reads only the local repository of whichever node leads the cold start, which may be older than another node's (`BACKUP_RESTORE_SOURCE_LOCAL`).

1. Confirm the network is healed and stable, and that you are looking at this case (all cores exited with 2, `reason=QUORUM_LOSS`).

   **Before any `destroy` or host clean-up (step 3): secure the backup.** With a `remote`, run `git ls-remote <remote> refs/heads/kv-backup` and confirm it answers with a head; the remote survives a destroy. That proves a head exists, not that it is current: if a halted core's disk is still readable, compare its `git -C <path>/kv-backup rev-parse kv-backup` with the remote's to learn how much was not pushed. **Without a `remote`, the only copy of the backup repository is `<path>/kv-backup` on the halted cores' own disks** (host `/opt/aether/backups/kv-backup` for bootstrap-launched container nodes), and a cloud `destroy` deletes those VMs and that disk. All cores are halted, so the old leader is unknown and any core's copy may be the newest or stale: copy `kv-backup` from every halted core to storage outside the cluster, compare their `git rev-parse kv-backup` heads, verify each copy (`git -C <copy> fsck`, readable head), and keep all of them before you destroy anything. A fresh cluster has no pre-start window in which to seed its leader from a copy, so a no-remote recovery re-creates the declared state by hand. The copy gives you an inventory of what existed: `git show <head>:kv-backup.txt` in the best copy has one `<base64 value> <key>` line per entry, and only the keys are readable (the values are base64 of a binary codec and cannot be used directly). Use the keys to list the blueprints, slice targets, API keys, stream and cluster configuration to rebuild, and re-create each from your own source of truth (blueprint files, configuration management), as in the no-backup procedure below. `[unverified: re-applying from kv-backup.txt was not run]`
2. Stop every remaining node, including workers and governors.
3. Start fresh cores: new NodeIds, the same cluster configuration, the same `[backup]` remote, `restore = "auto"` (the default). Start them the way you started the first cores (for a cloud or SSH source, `aether cluster bootstrap <config>` against fresh hosts; for Docker, new containers), not by restarting the halted processes. The restore is keyed by the `[backup]` remote and its lineage, not by the cluster name `[mechanism: no cluster-name reference in aether/node/.../node/backup/]`, so the name may be new. If you reuse the old name, `destroy` the halted cluster first (`aether cluster destroy --cluster <name> --yes`; with every core halted it cannot enumerate nodes and refuses unless you add `--force-undrained`, which deletes the VMs without a drain, so keep the old volumes aside first if the stream WAL files may be needed): a fresh `bootstrap` over a name that still has local state, a registry entry or labelled machines refuses (see [re-running bootstrap](../../reference/known-limitations.md#re-running-bootstrap-with-a-live-clusters-name)). With a new name the halted cluster's VMs keep running and billing until you destroy it. On SSH and Docker sources the start refuses any host that already holds an `aether-node` container in any state (`docker ps -a`), so use other hosts or remove the halted container first. `[unverified: bootstrap onto fresh hosts followed by restore was not run end to end]` Genesis forms exactly as on a first start and needs every configured core.
4. The leader commits one decision (`Backup restore decision: ...`): RESTORED, FRESH, SKIPPED_EXISTING_STATE or DISABLED. Expect RESTORED. Until it commits, backup-enabled nodes refuse writes to cluster state (`RestorePending`, retryable).
5. Verify: restored blueprints, slice targets, API keys and stream configurations are present; slices are ACTIVE on the fresh nodes; streams have owners among the fresh nodes; worker communities re-form.

**Recovered** (declared state, every `ClusterStateKey` up to the last successful push): blueprints, slice targets, stream configurations, API keys, cluster configuration, schema versions, communities, operator settings, lineage and incarnation. Placement is rebuilt, not restored.

**Not recovered:**
- Changes made after the last successful push (the recovery point is the last push; `BACKUP_PUSH_FAILING` fires after 60 s of lag).
- **Entity state restarts empty.** Checkpoint pointers are withheld (`BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED`).
- **Acknowledged stream records are not recovered under fresh NodeIds.** The WAL files remain on the old volumes, unread. `[unverified: the fresh-NodeId case is read from code, not run]`
- Runtime state: placements, partition owners, leases, node registrations (rebuilt).
- In-flight deployments: IN_PROGRESS outcomes are dropped; a rolling update resumes as after a leader failover.
- Schema migrations in MIGRATING restart as PENDING.

If the restore cannot read the backup the cluster stays up and gated (`BACKUP_RESTORE_BLOCKED`); fix the remote, or restart with `restore = "fresh"` to abandon the backup.

#### Recovery without `[backup]`

No automated path exists in rc4. The steps below follow from the same mechanisms and were not run end to end `[unverified: not run end to end]`.

1. Stop every node, including workers and governors.
2. Bring up a new cluster with new NodeIds as on a first start. Use a cluster name the old cluster did not hold, or remove the old cluster first (see the bootstrap warning above).
3. Re-apply from your own source of truth (configuration management, CI, your deployment repository) everything that lived only in consensus state:
   - blueprints and slice targets (redeploy slices)
   - API keys (mint new ones; old keys are gone and clients must be updated)
   - cluster and operator configuration changes made at runtime (anything not in the node TOML)
   - stream configurations (the streams are created again with new incarnations)
   - community definitions and schema-version state (migrations re-check from PENDING)
4. **Entity state and previously acknowledged stream records are lost.** Old stream WAL files stay on disk unread; keep the volumes if the data may be needed for manual extraction.

#### Prevention

- Configure `[backup]` with a reachable `remote` on every core of a multi-core production cluster, and put it in the source `node_config` so replacements carry it. Backup is off by default in rc4. See [the production prerequisite](deployment.md#production-prerequisite-backup-with-a-remote).
- Test the restore on a scratch cluster before production.
- Place cores so that one failure domain (a switch, a route) cannot cut all core-to-core links or split the cores three ways.
- `split_timeout` (`T`) is tunable. Raising it lengthens the cut a cluster rides out, and lengthens the time an isolated core keeps serving stale reads and refusing writes; there is no way to disable the fence. Its effect on the halt threshold was measured only at the default `[unverified: other values of T]`.

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

1. Read the message: it names the group, the partition, the skipped range `[from, to)` and its count. Nothing reopens the range. The alert is closed by `stream-consumer-cursor-resumed` (INFO, same subject) once the cursor is re-established: a read from the new offset succeeded, the cursor moved again, or the consumer was cancelled. The recovery closes the alert, not the loss.
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

### Stream Retention Withheld (`stream-retention-withheld`)

**Symptoms:** a WARNING `OPERATOR_WARNING` with `details.code` `stream-retention-withheld`, subject `node <nodeId> stream <name>`; the node's stream disk use grows and the stream's sealed history on that node is not reclaimed.

**Cause:** sealed-tier retention runs on every node over its own segments, and judges each stream by the retention policy of the committed config the node has applied. For two consecutive passes (about 10 minutes) this node had no policy it could read for the stream: it has not applied the stream's committed config (a node that has just restarted and has not yet restored its consensus state, or one lagging behind), or the stream's retention mode is unknown to this node's build (written by a newer node). A rule the node cannot read is not authority to delete, so nothing of the stream is reclaimed there.

**Actions:**

1. Check that the node is in the cluster and has caught up: a node that applies the committed config resumes on the next pass, and `stream-retention-resumed` (INFO, same subject) follows.
2. If the stream was destroyed while this node was down, its segments stay withheld on this node, and so does the disk they hold: a node cannot yet tell a destroyed life from an orphaned one that holds acknowledged data, so it keeps both and announces them (`know:` `d19f755873`; retirement records, PR-8j/#2458, will let it reclaim the destroyed ones).
3. If the mode is unknown to the build, upgrade the node to the build the rest of the cluster runs.

### Stream Segment Unreadable (`stream-segment-unreadable`)

**Symptoms:** a WARNING `OPERATOR_WARNING` with `details.code` `stream-segment-unreadable`, subject `node <nodeId> stream <name>[<partition>] <start>-<end>`.

**Cause:** retention read the block of a sealed segment rebuilt after a restart to learn its age, and the read failed in a way a retry does not change: a missing or wrong encryption key, or a damaged or missing block. The node does not read it again until it restarts. Retention judges the segment by the age of the next newer segment, so it neither holds the stream's sealed history for ever nor goes before a newer segment; reads of its range fail as storage failures until then.

**Actions:**

1. Read the message: it names the segment and the read failure. Segment encryption is not wired in this release (#253), so the cause is in practice a damaged or missing block.
2. The range is lost on this node; another holder of the partition may still serve it. Check the node's stream storage directory and disk for errors.
3. Nothing needs to be done for retention: `stream-segment-unreadable-reclaimed` (INFO, same subject) follows when retention drops the segment.

### Stream Segment Deletes Failing (`stream-segment-delete-failing`)

**Symptoms:** a WARNING `OPERATOR_WARNING` with `details.code` `stream-segment-delete-failing`, subject `node <nodeId> storage streams`; the node's stream disk and the DHT's memory stop shrinking although retention keeps releasing segments.

**Cause:** retention released expired segments, and the node's collection run, which deletes their blocks from every tier with the cluster-shared DHT first, has failed for three consecutive runs (one per minute). The DHT write quorum is unreachable, or its removes fail. Shared first means a failed run leaves the node's private copies too, and retries the whole delete on the next run. Readers are not affected: the released ranges are already out of the index and answer `CursorExpired`.

**Actions:**

1. Check DHT health: quorum, member reachability, and the node's DHT error logs.
2. Restore the DHT; the next run deletes the backlog (up to 10,000 blocks per run), and `stream-segment-delete-recovered` (INFO, same subject) follows.
3. If disk or heap pressure builds meanwhile, it is these released blocks; nothing else needs to be done for them.

### Stream Segment DHT Near Full (`stream-segment-dht-near-full`)

**Symptoms:** a WARNING `OPERATOR_WARNING` with `details.code` `stream-segment-dht-near-full`, subject `node <nodeId> dht prefix stream-segments`.

**Cause:** this node's DHT engine holds 85% or more of `[streaming] dht_segment_max_bytes` as live plus tombstone bytes of the `stream-segments` prefix. Tombstones are never capped but are heap (about the key length plus 64 bytes each, for `tombstone_retention`), so they count here: this is a heap warning, not a refusal. Until the stream retention work reduces them, every sealed event leaves one tombstone per replica.

**Actions:**

1. Check the gauges `aether.dht.prefix.bytes{prefix="stream-segments"}` and `aether.dht.prefix.tombstone.bytes{prefix="stream-segments"}` to see which part is growing.
2. Live bytes: shorten stream retention, or raise `[streaming] dht_segment_max_bytes` (and the heap with it). Tombstone bytes: they fall as `tombstone_retention` passes.
3. `stream-segment-dht-near-full-cleared` (INFO, same subject) follows when live plus tombstone bytes fall below 80% of the cap.

### Stream Segment DHT Full (`stream-segment-dht-full`)

**Symptoms:** a CRITICAL `OPERATOR_WARNING` with `details.code` `stream-segment-dht-full`, subject `node <nodeId> dht prefix stream-segments`; stream seals fail, publishers to partitions without a WAL see `SEALING_BEHIND`, and seal failures log `TierFull` for the `REMOTE` tier.

**Cause:** this node's DHT engine refused a client put because it would take the live bytes of `stream-segments` above `[streaming] dht_segment_max_bytes`. The message names the cap, the usage and the refused size. Raised once per episode, at the first refusal. The cap is per node and binds client writes only: repair writes (catch-up, migration, hand-off, re-homing) are accepted and counted, so a node can hold more than the cap after a re-replication. A put that reached its write quorum while one replica refused is a success with fewer than the full replica count, counted in `aether.dht.prefix.refused.puts`; anti-entropy repairs the copy later. Entity checkpoint blocks count against the cap and are not reclaimed yet, so a node hosting entity partitions can fill it quickly.

**Actions:**

1. Free space: retention passes and the collection run release expired segments; check `stream-segment-delete-failing` and `stream-retention-withheld` for a reason they do not.
2. Raise `[streaming] dht_segment_max_bytes` together with the node's heap if the working set is legitimate.
3. `stream-segment-dht-full-cleared` (INFO, same subject) follows when live bytes fall below 85% of the cap; the sealer retries on its own.

### Stream Segment Disk Pressure (`stream-segment-disk-pressure`)

**Symptoms:** a WARNING `OPERATOR_WARNING` with `details.code` `stream-segment-disk-pressure`, subject `node <nodeId> storage streams`.

**Cause:** the durable local-disk tier of the streams instance is at or above its warning ratio on this node. Seals fail when it is full and owner publishes are refused (`SEGMENT_TIER_FULL`) from the refuse ratio. Raised once per episode, on every node.

**Actions:**

1. Raise `[streaming] segment_disk_max_bytes`, add disk, or shorten stream retention.
2. `stream-segment-disk-pressure-relieved` (INFO, same subject) follows when the tier falls below the warning ratio.

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
