## Embeddable Runtime

| Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| #73 Ember embeddable cluster | Complete | No `EmberInstance` / `Ember.cluster(...)` API used by any integration suite. Forge/Ember has its own `forge-tests` / unit-test module per audit. Integration suites use Docker only. | **NONE** (Expected) | `aether/tests/integration/suites/**` — zero matches for `EmberInstance`\|`Ember.cluster`. README explicitly references `aether-node.jar` / `aether-lb.jar` Docker images only. |
| #74 Remote Maven repositories | Complete | No suite exercises Maven Central / Nexus resolution, SHA-256 verification, or `~/.m2` cache fallback. Artifact suite (09-artifacts) tests local push/resolve via cluster artifact repo, not Maven. | **NONE** | `aether/tests/integration/suites/09-artifacts/` — all paths use `aether artifacts push` against cluster; zero matches for `nexus`\|`maven-central`\|`m2/repository`. |
| #75 Load Balancer | Complete | LB module exists (`aether/lb/`); built into `aether-lb:local` Docker image per README. No suite invokes LB round-robin / health-checking / X-Forwarded behavior end-to-end. (NB: catalog row predates LB-deletion notice in MEMORY but module still listed Complete.) | **NONE** | README L65 references the build target; no suite source file references LB endpoints. `grep -rln "aether-lb\|X-Forwarded\|round-robin"` → 0 hits in `suites/`. |

### Section summary
- **3 features classified** (all marked Complete in catalog)
- **0 COVERED / 0 PARTIAL / 3 NONE**
- **"Expected NONE" rationale:** Per the 2026-05-21 audit, Embeddable Runtime / Forge testing lives in `forge-tests` (out of integration-suite scope). Items #74 (Maven resolution) and #75 (LB) are **not Forge-domain** — they are runtime features that should be reachable from a Docker cluster but have no integration coverage today.
- **Notable RC1 gaps:** #74 Remote Maven repositories and #75 Load Balancer have **no end-to-end coverage** in integration suites. Maven resolution is security-sensitive (SHA-256, XXE hardening) and should be exercised against a real remote repo before GA.

---

## Worker Pools

| Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| #80 SWIM failure detection | Complete | `12-network/test-swim-detection.sh` — kill follower, assert SWIM detection within `SWIM_DETECTION_TIMEOUT=15s`. Strict detection-time test, soft on recovery. Per audit, this suite is **NARROW** (no indirect-probe / membership-piggyback assertions) but the failure-detection capability is exercised. | **PARTIAL** | `aether/tests/integration/suites/12-network/test-swim-detection.sh:32` `test_swim_detection_time` |
| #81 Worker node | Complete | No suite deploys a WORKER-role node, none invokes `--role=WORKER` or `placement=WORKERS_*`. Integration clusters are all 5 cores. | **NONE** | `grep -rn "role=WORKER\|WORKERS_PREFERRED\|WORKERS_ONLY\|aether-worker"` in `suites/` → 0 hits. |
| #82 Governor election | Complete | `04-streaming/test-stream-replication.sh::test_stream_visible_on_governor` queries "governor" via streaming API but the governor concept here is per-stream-partition, not the worker-group Governor. No test asserts Governor election ordering / deterministic lowest-NodeId rule / death cleanup. | **NONE** | `grep -rn "GovernorAnnouncement\|GovernorElection\|governor.election"` → 0 hits. |
| #83 Worker endpoint registry | Complete | No worker present → registry merging logic untested. Endpoint readiness tested only for core slices in 06-deployment / 08-resources. | **NONE** | n/a |
| #84 CDM pool awareness | Complete | `06-deployment/*` deploys slices but never with `placement=WORKERS_*`. `AllocationPool` / `PlacementPolicy` differentiation untested. | **NONE** | `grep -rn "placement\|PlacementPolicy"` in `suites/` → 0 hits in test bodies. |
| #85 Worker management API | Complete | No invocation of `aether workers list`, `aether workers health`, `aether workers endpoints`, or `POST /api/scale --placement`. CLI subcommand for workers absent from all suite scripts. | **NONE** | `grep -rn "workers list\|workers health\|workers endpoints\|/api/workers"` in `suites/` → 0 hits. |
| #86 Core-to-core SWIM health | Complete | `12-network/test-swim-detection.sh` covers core-to-core failure detection latency (kills a core, asserts detection). The `CoreSwimHealthDetector → DisconnectNode` path is exercised end-to-end. | **COVERED** (NARROW) | `aether/tests/integration/suites/12-network/test-swim-detection.sh:32` (5-core cluster, follower kill, SWIM detection time assertion). |
| #87 Automatic topology growth | Complete | `03-scaling/test-02-scale-up.sh` exercises scale-up but does **not** exercise core/worker role assignment (no worker pool present). `coreMax`/`coreMin` not touched by any test. CTM auto-heal is exercised by 15-delegation but at the core level only. | **PARTIAL** | `03-scaling/test-02-scale-up.sh` exercises a subset (core growth); worker-role assignment untested. |
| #93 DHT node cleanup | Complete | `09-artifacts/test-artifact-replication.sh` exercises DHT replication post-push but does not kill a node to assert endpoint-cleanup on DEAD. The cleanup-on-SWIM-DEAD path is **indirectly** verified by 02-chaos / 15-delegation reassignment (assignments survive node death — implies stale entries cleaned). | **PARTIAL** | `09-artifacts/test-artifact-replication.sh` (DHT replication only); `15-delegation/test-02-reassignment.sh::test_node_failure_reassignment` (indirect). |
| #94 SliceNodeKey DHT migration | Complete | Slice deployment + replication tested across 06-deployment / 09-artifacts, but no test specifically asserts SliceNodeKey lives in `slice-nodes` ReplicatedMap vs. consensus KV. | **PARTIAL** (functional only) | `09-artifacts/test-artifact-replication.sh` exercises DHT path indirectly. |
| #95 HttpNodeRouteKey DHT migration | Complete | Same as #94 — HTTP route lookups work cross-node (smoke + 06-deployment) but DHT-vs-consensus storage location not asserted. | **PARTIAL** (functional only) | `00-smoke/test-slice-deployment.sh::test_app_endpoint_reachable` works cross-node. |
| #96 DHT replication config | Complete | No test sets `[dht.replication]` TOML knobs or asserts environment-aware defaults. | **NONE** | `grep -rn "dht.replication\|cooldown_delay\|target_rf"` in `suites/` → 0 hits. |
| #97 Multi-group worker topology | Complete | No multi-group deployment (no zones, no `WorkerGroupId`) tested. | **NONE** | `grep -rn "WorkerGroupId\|groupName"` in `suites/` → 0 hits. |
| #98 CDM community-aware placement | Complete | No community-aware test; no worker pool exists in integration topology. | **NONE** | n/a |
| #99 Worker zone configuration | Complete | No `worker.zone` / `worker.groupName` / `worker.maxGroupSize` set in any test env or aether.toml under `suites/`. | **NONE** | n/a |
| #100 Event-based community scaling | Complete | No `WorkerMetricsPing` / `CommunityScalingRequest` event tested. | **NONE** | `grep -rn "CommunityScalingRequest\|WorkerMetricsPing"` in `suites/` → 0 hits. |
| #101 Governor advertised address | Complete | No test inspects `worker.advertise_address` or verifies Governor address auto-detect. | **NONE** | n/a |
| #132 Role-aware unified node | Complete | Single `aether-node.jar` exercised (per README) but always in CORE role. `ForwardingClusterNode` / `SwitchableClusterNode` / `authorizeActivation()` promotion paths not exercised. | **NONE** | n/a — only CORE role used in suites. |
| #150 DHT-backed ReplicatedMap | Complete | `MapSubscription` / drain-loop / `CachedReplicatedMap` LRU+TTL: covered by unit tests in `aether-dht`, not by integration suites. Integration only exercises end-result functionality (#94, #95). | **PARTIAL** | Indirect via #94 / #95. |
| #151 Community-aware replication | Complete | `ReplicationPolicy` / `HomeReplicaResolver` / RF=3 home-replica rule not asserted by any suite. | **NONE** | n/a |
| #152 Endpoint DHT migration | Complete | Same as #94/#95 — end-to-end functionality works but write-amplification / O(3) vs O(N) not asserted. | **PARTIAL** (functional only) | n/a |
| #153 Replication cooldown | Complete | Cooldown delay / rate-limited push not asserted. No test inspects RF during startup window. | **NONE** | n/a |
| #156 Compound KV-Store key types | Complete | `NodeArtifactKey` / `NodeRoutesKey` merging not asserted at the storage layer; the ~10x reduction claim is unverified by integration tests. WorkerNetwork elimination → no test consequence visible. | **NONE** | n/a |

### Section summary
- **22 features classified** (all marked Complete in catalog)
- **1 COVERED / 5 PARTIAL / 16 NONE**
- **#15-delegation context (RC1-blocker #27):** `test-01-task-assignments.sh::test_tasks_distributed` asserts `unique_nodes >= 1`, which is tautological (any assignment guarantees ≥1 node). The audit flags this as RC1-BLOCK — it is the canonical worker-pool-adjacent test the catalog leans on but it does **not** actually verify distribution. Worker Pools generally has **no Forge-style fallback**: there is no dedicated `worker-pool-tests` module that would explain the NONE entries above.
- **Notable RC1 gaps (Complete features with NONE):** The entire WORKER-role topology (#81, #84, #85, #87 worker-half, #97, #98, #99, #100, #101, #132 worker-half, #151) has **zero end-to-end coverage**. Worker pools were added in v0.21+ and the integration topology was never expanded to a 3-core + 2-worker (or similar) layout. This is a **systemic RC1 gap** if the worker-pool feature is on the GA surface.

---

## Cross-section roll-up (PLACEHOLDER — to be filled by master synthesis)

The other 4 agents will produce coverage tables for:
- Deployment & Lifecycle, Scaling & Control, Cluster & Consensus, Networking & Routing → `/tmp/coverage-deploy-scale-cluster-net.md`
- Messaging, Scheduled Invocation, Storage & Data, Observability & Metrics → `/tmp/coverage-messaging-sched-storage-obs.md`
- Resource Provisioning, Cloud Integration, Management → `/tmp/coverage-resources-cloud-mgmt.md`
- Developer Tooling, Reusable Libraries, Node Operations, Security & Resilience → `/tmp/coverage-devtools-libs-nodeops-security.md`
- Embeddable Runtime, Worker Pools → this file

In the master synthesis, the roll-up will combine all these into a single coverage matrix with these top-line stats:
- Total Complete features: N
- COVERED count: M
- PARTIAL count: X
- NONE count: Y
- RC1-impacting NONE (Complete features with no test): the action list for Phase 4 backlog additions

### This file's contribution to the roll-up

| Sub-section | Features | COVERED | PARTIAL | NONE | Notable |
|---|---|---|---|---|---|
| Embeddable Runtime | 3 | 0 | 0 | 3 | Forge-domain (forge-tests); #74 Maven + #75 LB are real coverage gaps |
| Worker Pools | 22 | 1 | 5 | 16 | Systemic gap: WORKER-role topology never deployed in integration tests; 15-delegation RC1-BLOCK #27 documented in audit |
| **Subtotal** | **25** | **1** | **5** | **19** | **76% NONE** in this slice — second-largest gap area expected |

### Confirmation re: Known Limitations / Planned Features (lines 276-324)
- `## Known Limitations` (lines 278-282) — 3 entries (security/networking/dashboard). These are documented gaps with planned remediation. **Correctly excluded** from coverage matrix.
- `## Planned Features` (lines 286-321) — 36 entries. Status mix: `Planned` (true future work, exclude) plus several `Complete` entries (#71, #176-#198, #200-#203) and 2 `Partial` (#70, #199, #204). The `Complete` Planned-Features entries **should be classified** in their topical sections by the other 4 agents (e.g., #194-#196 API key rotation → Security agent; #200-#203 cloud bootstrap → Cloud agent). They are not in scope for this Embeddable/Workers slice.
