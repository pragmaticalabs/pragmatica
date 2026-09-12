# Scaling Runbook

## Scaling Principles

1. **Odd node counts** - Use 3, 5, or 7 nodes for quorum-based consensus
2. **Horizontal first** - Scale nodes before scaling up individual nodes
3. **Gradual changes** - Add/remove one node at a time
4. **Verify quorum** - Always verify quorum health after changes

## Adding a Node

### Prerequisites
- New node has Aether installed
- Network connectivity to existing nodes
- TLS certificates (if cluster uses TLS)

### Procedure

1. **Start new node with cluster configuration**
   ```bash
   java -jar aether-node.jar \
     --node-id=node4 \
     --port=8090 \
     --peers=node1:8090,node2:8090,node3:8090
   ```

2. **Verify node joined cluster**
   ```bash
   # On existing node
   curl http://node1:8080/cluster/status
   # Should show node4 in the node list
   ```

3. **Verify quorum maintained**
   ```bash
   curl http://node1:8080/health
   # quorum should be true
   ```

4. **Monitor for slice rebalancing**
   ```bash
   # Watch slice distribution
   watch -n 5 'curl -s http://node1:8080/slices | jq "group_by(.nodeId) | map({node: .[0].nodeId, count: length})"'
   ```

## Cloud Auto-Scaling

For clusters bootstrapped against a cloud provider (`source.type = "cloud"`, e.g. Hetzner / AWS / GCP / Azure), scaling is driven by a single command — the consensus leader provisions or terminates VMs through the provider's API.

### Scaling the cluster

```bash
# Single-source cluster — the source is inferred
aether cluster scale --role core --count 7

# Multi-source cluster — name the source that absorbs the change
aether cluster scale --source hetzner-eu --role core --count 7
```

This issues `POST /api/cluster/scale` against the leader. The desired count for that `(source, role)` is written to KV-Store, which triggers a reconciliation pass. If the new size is greater than the current healthy node count, CTM calls `ComputeProvider.provision()` for each missing node; if smaller, it drains and terminates the surplus.

Omitting `--source` when several sources declare the role is refused rather than guessed — the response names the candidates. Quorum safety is checked against the resulting **cluster-wide** core total, not the per-source count, so scaling one core source to 1 is accepted when another source carries 2.

Verify progress:
```bash
aether status --format json | jq '.cluster.coreCount, .cluster.nodes | length'
```

### Prerequisites for auto-scaling on cloud

The leader must hold valid cloud credentials at runtime. This requires:

1. **`[cloud]` section present in each node's `aether-config.toml`.** The `aether cluster bootstrap` workflow generates this automatically. If a node was deployed by other means, confirm the section exists — without it, `lifecycleManager.isCloudManaged()` is false on that node and any `/api/cluster/scale` request that elects it as leader will log `"no ComputeProvider, cannot auto-provision"` and silently no-op.

2. **`[cloud.credentials]` populated with a valid API token.** See [`reference/cloud-integration.md`](../../reference/cloud-integration.md#credential-propagation-to-nodes) for the credential-propagation model. **The literal API token is written to `aether-config.toml` on every cluster node.** Any node compromise leaks the cloud-provider token. Use a dedicated cloud project per cluster, restrict file system permissions on `aether-config.toml`, and rotate tokens periodically.

3. **Cloud-provider quota headroom for the scale-up target.** Before scaling, confirm the cloud account has capacity for the additional VMs. If quota is insufficient, the leader's provisioning loop will log repeated 403/422 responses from the provider API but will not fail the scale request — `aether status` will continue to report the deficit until the operator either raises quota or scales back down.

### Recovery from a stuck scale operation

If `aether cluster scale --role core --count N` returns 200 but the node count never reaches `N`:

```bash
# 1. Identify the leader
aether status --format json | jq '.cluster.leaderId'

# 2. Inspect leader logs for provisioning errors
ssh aether@<leader-host> 'journalctl -u aether-node --since "10 min ago" | grep -E "CTM|ComputeProvider|provision"'

# 3. Common causes:
#    - "no ComputeProvider, cannot auto-provision" → [cloud] missing from leader's aether-config.toml; see prerequisite 1
#    - "401 unauthorized" / "403 forbidden"        → invalid or expired API token; rotate per prerequisite 2
#    - "422 invalid_input" / "Cannot create"       → cloud quota exhausted; see prerequisite 3
#    - "deficit ... but no top-up needed"          → CTM is already mid-flight; wait for current wave to complete
```

### Hetzner-specific notes

- Tokens are project-scoped and grant **full read+write access** to every Hetzner resource in the project. The runbook for cloud auto-scaling assumes a dedicated Hetzner project per cluster.
- Hetzner servers boot in 30-60 seconds; allow at least 90 seconds per node before declaring a scale operation stuck.
- VMs are tagged with the label `aether-cluster=<cluster_name>` and identified by tag during termination. Manually-launched VMs without this label are never touched by CTM.

## Removing a Node

### Prerequisites
- Cluster has enough nodes to maintain quorum after removal
- No unique slice instances on the node being removed

### Procedure

1. **Verify quorum will be maintained**
   ```bash
   # Current node count
   curl http://node1:8080/health | jq '.nodeCount'
   # Must be > 2 after removal for 3-node quorum
   ```

2. **Drain slices from node (if possible)**
   ```bash
   # Graceful shutdown will attempt to migrate slices
   curl -X POST http://node4:8080/admin/drain
   ```

3. **Stop the node**
   ```bash
   # On node4
   kill -TERM $(pgrep -f aether-node)
   ```

4. **Verify remaining cluster health**
   ```bash
   curl http://node1:8080/health
   # quorum should still be true
   ```

## Scaling Slices

### Increase Slice Instances

1. **Update blueprint**
   ```bash
   aether> blueprint update org.example:my-slice:1.0.0 --instances=5
   ```

2. **Verify deployment**
   ```bash
   aether> slices list | grep my-slice
   # Should show 5 instances
   ```

### Decrease Slice Instances

1. **Update blueprint**
   ```bash
   aether> blueprint update org.example:my-slice:1.0.0 --instances=2
   ```

2. **Verify deactivation**
   ```bash
   # Watch instances decrease
   watch -n 2 'aether --connect node1:8080 slices | grep my-slice'
   ```

## Capacity Planning

### Node Sizing Guidelines

| Workload | CPU | Memory | Nodes |
|----------|-----|--------|-------|
| Development | 2 cores | 4 GB | 1-3 |
| Small production | 4 cores | 8 GB | 3 |
| Medium production | 8 cores | 16 GB | 5 |
| Large production | 16 cores | 32 GB | 7+ |

### When to Scale

**Scale out (add nodes) when:**
- CPU usage consistently > 70% across nodes
- Response latency increasing
- Need to increase slice capacity

**Scale in (remove nodes) when:**
- CPU usage consistently < 30% across nodes
- Cost optimization needed
- Minimum 3 nodes for production

### Monitoring Thresholds

```bash
# Set up alerts for these conditions
cpu.usage > 0.7        # Warning: consider scaling
cpu.usage > 0.9        # Critical: scale immediately
heap.usage > 0.8       # Warning: memory pressure
quorum = false         # Critical: cluster degraded
```
