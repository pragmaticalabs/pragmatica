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
   watch -n 2 'aether-cli --host node1:8080 -c "slices list" | grep my-slice'
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
