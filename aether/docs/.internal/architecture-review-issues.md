# Aether Architecture Review — Open Issues

Identified during comprehensive architecture review (2026-03-10). Issues ordered by severity within each category.

## Architectural Gaps

### AG-1: No Inter-Node Encryption (High)

**Problem:** No TLS between core-core, core-worker, or worker-worker. Cluster assumes private network. At 10K nodes spanning zones/clouds (Phase 2 target), this is untenable. Any network-level attacker can read consensus traffic, KV-store state, and slice invocation payloads.

**Affected components:** `NettyClusterNetwork`, `WorkerNetwork`, SWIM (UDP), DHT (planned).

**Decision: Defer to v0.20.0. Added as #1 highest priority in development-priorities.md.** Certificate management infrastructure is a prerequisite. Must be complete before Phase 2 multi-zone features go live.

---

### AG-2: KV-Store Has No Durable Backup (High)

**Problem:** KV-store is in-memory, replicated via Rabia. If quorum is lost simultaneously (e.g., all nodes restart at once, datacenter power loss), all state is gone — blueprints, targets, config, routing rules. There's no WAL (write-ahead log) or persistent snapshot to disk.

**Affected components:** `KVStore`, `RabiaEngine`, `RabiaPersistence`.

**Mitigation today:** Rabia tolerates `(N-1)/2` failures. 5-node cluster survives 2 simultaneous failures. Full quorum loss requires catastrophic event.

**Decision: Promoted to #2 highest priority in development-priorities.md.** Periodic snapshots to cloud storage (S3/GCS/Azure Blob) and git repository (versioned config-as-code with audit trail). Must be in place before Phase 2 production deployment.

---

### AG-3: Worker Phase 1 Wiring Incomplete (High)

**Problem:** Workers can't actually run slices yet. The infrastructure exists (WorkerNode, GovernorElection, DecisionRelay, WorkerEndpointRegistry, CDM pool awareness, management API) but end-to-end wiring is incomplete:
- `WorkerSliceDirectiveKey/Value` written to KV-Store but workers don't execute them
- `WorkerEndpointRegistry` not actively populated from real worker data
- SliceInvoker → WorkerEndpointRegistry fallback path exists but isn't exercised

**Affected components:** `WorkerNode`, `NodeDeploymentManager` (worker variant), `WorkerEndpointRegistry`, `SliceInvoker`.

**Note:** Phase 2 (DHT ReplicatedMap) changes the storage model, so completing Phase 1 wiring as-is would be immediately replaced.

**Decision: Complete wiring after Phase 2.** Workers become functional on the Phase 2 DHT architecture (workers write endpoints directly to DHT, no WorkerGroupHealthReport path). No point wiring the Phase 1 health-report-based path only to replace it.

---

### AG-4: No Multi-Region Support (Medium)

**Problem:** Single-region only. Phase 2 introduces zones within a region, but cross-region deployment introduces:
- Consensus latency (Rabia requires all-to-all, cross-region RTT = 50-200ms per round)
- DHT cross-region replication (higher latency, potential partitioning)
- Split-brain between regions (network partition = quorum loss in one region)
- No region-aware placement policies

**Note:** This may be acceptable as a known limitation for v0.20.0. Cross-region is a different architecture (multi-cluster federation, not single-cluster stretch).

**Decision: Known limitation, not a gap. Added to FUTURE section in development-priorities.md.** Single-region is inherent to Rabia's all-to-all design. Multi-region requires federation architecture — fundamentally different, not an incremental fix.

---

### AG-5: RBAC Is Coarse-Grained (Medium)

**Problem:** RBAC Tier 1 is implemented (authentication + role-based access). But all authenticated keys have the same access level. No per-endpoint, per-slice, or per-namespace authorization. At 10K nodes with multiple teams deploying different slices, this is insufficient.

**Affected components:** Management API routes, CLI authentication.

**Decision: Already tracked as #8 (RBAC Tier 2) in development-priorities.md at medium priority.** No change needed.

---

### AG-6: Pub/Sub Subscriptions in Consensus (Medium)

**Problem:** `TopicSubscriptionKey/Value` lives in consensus KV-store. Cardinality is O(T×S×M) where T=topics, S=slices, M=methods. With many topics at 10K nodes, this could grow significantly. The KV-store scalability doc (`aether/docs/internal/kv-store-scalability.md`) flags EndpointKey, SliceNodeKey, and HttpNodeRouteKey for DHT migration — but not TopicSubscriptionKey.

**Question:** Is TopicSubscriptionKey per-node (O(N×T×S×M)) or per-slice (O(T×S×M))? If per-node, it has the same O(N) growth problem and should be migrated to DHT alongside the other three types.

**Decision: Not an issue.** `TopicSubscriptionKey` is `(topicName, artifact, methodName)` — per-slice, not per-node. Cardinality is O(T×S×M), independent of node count. Stays in consensus.

---

### AG-7: Predictive Scaling Disconnected (Low)

**Problem:** TTM (Traffic Trend Model) ONNX inference is wired but not connected to scaling decisions. SLM (Small LLM) and LLM (Large LLM) controller layers not started. Control loop operates at Layer 1 only (deterministic decision tree).

**Impact:** Scaling is reactive (threshold-based), not predictive. Acceptable for v0.20.0 but limits autonomous behavior.

**Decision: Already tracked in development-priorities.md FUTURE section (LLM Integration Layer 3).** Low priority — Layer 1 decision tree is sufficient for v0.20.0.

---

## Potential Implementation Issues (Need Verification)

### PI-1: KVStore Snapshot Restore Race (High)

**Problem:** `restoreSnapshot()` in `KVStore.java` does clear + re-add without synchronization. Three-step mutation:
1. Notify all removals
2. Clear storage
3. Put all entries + notify

Between steps, concurrent readers (CDM via `isNodeOnDuty()`, EndpointRegistry, etc.) see empty or partial state. Uses `ConcurrentHashMap` which gives per-operation atomicity but not batch atomicity.

**Affected components:** `KVStore`, all KV-store consumers during leader failover.

**When it triggers:** Node recovery via `RabiaEngine.processSyncResponse()` → `stateMachine.restoreSnapshot()`.

**Decision: Not an issue.** `restoreSnapshot` runs only during node sync (joining/recovering). At that point, the node is not active, not participating in consensus, not serving traffic, and cannot be leader. No concurrent readers exist.

---

### PI-2: CDM State Rebuild After Failover (High)

**Problem:** `rebuildStateFromKVStore()` in `ClusterDeploymentManager` reconstructs local maps (`blueprints`, `sliceStates`, `sliceDependencies`) by scanning KV-store. But:
- No lock between the scan and concurrent `trackSliceState()` updates from KV notifications
- SliceNodeValue updates may arrive before rebuild completes
- New leader may see stale KV-store snapshot from consensus sync

**Result:** Lost state updates or incorrect reconciliation decisions (e.g., scaling down when should scale up).

**When it triggers:** Leader failover → new leader activates CDM.

**Decision: Not an issue.** `rebuildStateFromKVStore` iterates `ConcurrentHashMap` which reflects the latest committed state. Concurrent KV notifications write to CDM's `sliceStates` (also `ConcurrentHashMap`). `forEach` sees updates made during iteration, so `restoreSliceState` always reads the latest committed value. No stale overwrites possible.

---

### PI-3: RabiaEngine correlationMap Promise Leak (High)

**Problem:** When a batch is committed, `commitChanges()` removes from `correlationMap` using correlation IDs from the decided batch. If local `pendingBatches` merged different correlation IDs than the decided batch contains, orphan promises remain in the map and never resolve. Client calls hang indefinitely.

**When it triggers:** Batch merge during high-concurrency proposal.

**Decision: Not an issue.** Code already handles this correctly (lines 880-893 of `RabiaEngine.java`). `commitChanges` explicitly uses `localBatch.correlationIds()` (fully merged) rather than `decision.value().correlationIds()` (potentially partial from proposer). Fallback to decision's IDs only when `localBatch` is null (non-submitter node, no local promise to resolve).

---

### PI-4: Leader Election Stall on First Bootstrap (Medium)

**Problem:** Race between topology delivery and election scheduling in `LeaderManager`. In consensus mode:
- `tryElect()` refuses to pre-cache leader until active
- `triggerElection()` schedules retries that may run when `active=false`
- If candidate fails before consensus activates, retries may stall

**When it triggers:** First cluster formation with slow topology discovery.

**Decision: Not an issue.** Code has multiple fallback layers: (1) 3s initial delay for sync, (2) 500ms retry loop, (3) candidate rotation after 6 retries (3s) cycles through all candidates, (4) mid-rotation fallback allows any active node to submit after 3 retries. A slow or failed candidate is bypassed within seconds.

---

### PI-5: Drain Eviction Race in CDM (Medium)

**Problem:** `evictNextSliceFromNode()` and `checkReplacementAndUnload()` both read `sliceStates` map without synchronization. Concurrent CDM update can remove entry between check and action.

**Result:** Double-unload commands or missed entries during node drain.

**When it triggers:** Node drain with concurrent slice state transitions.

**Decision: Not an issue.** Both methods use read-then-act on ConcurrentHashMap (consistent reads). Actions go through consensus (idempotent). A duplicate unload command is harmless — second one is a no-op since slice is already in UNLOADING state. Drain processes one slice at a time, sequenced by schedule chain.

---

### PI-6: Network Frame Size Limit (Medium)

**Problem:** `NettyClusterNetwork` sets max frame size to 1MB (`LengthFieldBasedFrameDecoder(1048576, ...)`). No validation during serialization. Oversized messages (e.g., large KV-store snapshot, large blueprint) could be silently dropped or cause OOM.

**When it triggers:** Large cluster state, many slices, or large blueprint payloads.

**Decision: Acceptable given Phase 2.** Phase 2 moves heavy entries (endpoints, slice-nodes, http-routes) to DHT, keeping consensus KV-store <1MB. The 1MB frame limit is sufficient for consensus traffic after migration. Not worth making configurable now.

---

### PI-7: SWIM Topology Update Race in CoreSwimHealthDetector (Low)

**Problem:** `updateTopology()` uses `CopyOnWriteArrayList.clear()` then `addAll()`. Between operations, concurrent `onMemberFaulty()` may `remove()` a node that is being re-added, causing duplicate topology removal events.

**When it triggers:** Concurrent SWIM membership changes during topology refresh.

**Decision: Not an issue.** `updateTopology()` has no callers — it's dead code. `currentTopology` is only set in the constructor and never modified after that. `onMemberFaulty` doesn't touch `currentTopology` — it only routes notifications. No concurrent modification exists.
