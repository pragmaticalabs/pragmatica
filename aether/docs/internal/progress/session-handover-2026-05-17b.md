<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Session Handover — 2026-05-17b (RC1 — DHT resilience architecture, cluster-label scoping, 06+14 storage fixes)
date: 2026-05-17
branch: release-1.0.0-rc1
head: 6bd50d26e
predecessor: aether/docs/internal/progress/session-handover-2026-05-17.md
status: shipped — RC1 architecture-correctness work landed; 4 specific failures remain, all with focused investigation hints
---

# Session Handover — 2026-05-17b

## TL;DR (3 minutes)

1. **DHT resilience layered architecture shipped.** Replaced wait-and-mask pipeline with signal-and-route-around. Three layers: WriteOutcome surface (transport synchronously reports refusals), live-replica filter (DHT routes around unreachable peers), QuorumCollector fast-fail (immediate failure propagation on refusal). Spec: `aether/docs/specs/dht-resilience-spec.md`. Net: cluster B chaos suites 9-88× faster, 09-artifacts 1MB no longer hangs (49min → 38s fast-fail), 08-resources Deploy_SQL_app restored, cluster A `34p/2f` stable, cluster B durations dramatically reduced with same failure count.

2. **Cluster-label scoping (RC1+RC2) shipped.** Orthogonal `aether.cluster` + `aether.node-id` labels across all 5 compute providers (Docker/Hetzner/AWS/GCP/Azure); `AETHER_CLUSTER_NAME` env propagation when KV-Store unseeded; `aether cluster scaffold --format docker-compose` CLI subcommand; first-boot Docker-socket consistency check via JDK-native `UnixDomainSocketAddress`; RFC-0015 + operator playbook. Solves cross-cluster contamination on shared infrastructure.

3. **06-deployment and 14-storage systemic fixes shipped.** Both were `set -euo pipefail`-on-empty-grep silent script aborts. 06-deployment `deploy_cleanup` guarded; 14-storage scripts guarded; `StorageFactory` always registers default `artifacts` storage; fixed pre-existing `StorageSetup.storageSetup` infinite recursion. 06-deployment `2p/3f → 5p/0f`, 14-storage `0p/2f → 2p/0f`.

4. **Failed experiment captured and learned-from.** Backpressure-buffer attempt (queue messages when channel unwritable instead of dropping) was reverted: stale Rabia consensus messages flooded reconnected peers during chaos kills, breaking SWIM detection (60s timeout) and cascading cluster B into degraded state for every subsequent suite. The right fix turned out to be **transport-layer synchronous failure signaling**, NOT buffering — which is what the final DHT resilience architecture does.

---

## Quick state

```
branch:  release-1.0.0-rc1
HEAD:    6bd50d26e docs(changelog): DHT resilience layered architecture entry
pushed:  yes (origin/release-1.0.0-rc1)
tag:     v1.0.0-rc1-candidate @ 6bd50d26e (forced)
working: clean
```

23 commits ahead of the previous session's `b9d52a50b` baseline. All pushed.

---

## Integration test final state

### Cluster A (non-destructive — 34p/2f)

| Suite | Result | Notes |
|---|---|---|
| 00-smoke | 2p/0f | |
| 04-streaming | 4p/0f | |
| **06-deployment** | **5p/0f** | +3 vs pre-session (deploy_cleanup pipefail guard) |
| 07-cluster-mgmt | 4p/0f | |
| 08-resources | 5p/0f | Restored (was -1 mid-session) |
| **09-artifacts** | **1p/2f** | 1MB+5MB push fail with HTTP 500 — see issue #1 below. 38s fast-fail vs 49min hang previously |
| 10-database | 3p/0f | |
| 11-observability | 6p/0f | |
| **14-storage** | **2p/0f** | +2 vs pre-session (artifacts auto-register + recursion fix + script guards) |
| 15-delegation | 2p/0f | |

### Cluster B (destructive — 5p/11f, durations 9-88× faster than baseline)

| Suite | Result | Duration | Previous (this session) |
|---|---|---|---|
| 02-chaos | 3p/1f | 110s | 1713s (backpressure attempt) → **15× faster** |
| 03-scaling | 0p/3f | 9s | 795s → **88× faster** |
| 05-security | 0p/3f | 11s | 530s → **48× faster** |
| 12-network | 2p/1f | 65s | 793s → **12× faster** |
| 13-edge-cases | 0p/3f | 89s | 795s → **9× faster** |

Cluster B fail counts unchanged (same pre-existing root causes). What CHANGED is fail-fast behaviour — clusters no longer churn timeouts on dead peers; the architecture surfaces failure signal in seconds instead of minutes.

---

## Commits pushed this session (chronological, 23 total)

| Hash | Subject |
|---|---|
| `d3e54717e` | fix(dht): re-route TransportObservation.PeerDisconnected into ring prune (later reverted — see #2 below) |
| `99e0767e4` | fix(artifact): aggregate 30s timeout on chunk fan-out in ArtifactStore.deploy |
| `cf3b49d5c` | fix(test-infra): remove nginx mgmt-gateway; cluster-network-scoped label lookup |
| `07c6877a7` | fix(test-infra): 15-delegation correctly classified as destructive |
| `b9d52a50b` | docs: session handover + cluster-label-scoping spec + changelog |
| `3b2fbd2d7` | feat(cli): aether cluster scaffold emits docker-compose template |
| `4f7b87560` | fix(test-infra): aether.cluster label on compose nodes; helper filters on label+network |
| `c02dfeca9` | docs: RFC-0015 + multi-cluster-deployment operator guide + cli reference + changelog |
| `4f84cb241` | docs: drop kubernetes/terraform scaffolding from RFC-0015 and spec — not needed |
| `de5edb791` | fix(compute-providers): AETHER_CLUSTER_NAME env fallback for empty ProvisionContext.clusterName |
| `04d4553b9` | feat(node): first-boot Docker-label consistency check fails-closed on aether.cluster mismatch |
| `70cd1a76c` | docs: changelog + RFC-0015 reflect consistency check + caveat-c landed |
| `58900d451` | fix(test-infra): pipefail guards on legitimately-empty grep results in deploy_cleanup + 14-storage extractions |
| `06d027a3c` | fix(storage): always register default 'artifacts' StorageSetup so /api/storage exposes it |
| `21299d1ac` | fix(storage): StorageSetup.storageSetup factory was infinite-recursing; use canonical constructor |
| `70e26f0ad` | fix(artifact): move DEPLOY_TIMEOUT to outer flatMap so it covers metadata+versions DHT writes too |
| `d70d41c38` | revert(dht): un-wire PeerDisconnected ring prune; consensus-driven NodeRemoved is the correct trigger |
| `1be349322` | docs(spec): DHT resilience layered architecture for chaos-safe writes |
| `9b4e8bfcd` | feat(consensus): WriteOutcome surface — transport refusals propagate synchronously to callers |
| `f140d009e` | feat(dht): live-replica routing + fast-fail QuorumCollector on transport refusal |
| `6bd50d26e` | docs(changelog): DHT resilience layered architecture entry |

(Earlier commits pre-session: `39b921b7c` higher-id reconciler grace, `8dec985e0` pick_non_leader stale-id skip — these were already in place before this session.)

---

## DHT resilience architecture — implementation reference

Full spec at `aether/docs/specs/dht-resilience-spec.md`. Pointer summary for future contributors:

**Layer 1 — Transport signals failures synchronously**
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/WriteOutcome.java` — sealed interface, 4 records (Sent / BackpressureRefused / ConnectionDead / NoPeerState)
- `QuicClusterNetwork.writeIfWritable` returns `WriteOutcome` instead of void
- `writeToStream`, `dispatchSerialized`, new `dispatchPayloadWithOutcome` propagate the outcome
- New `ClusterNetwork.sendOutcome(NodeId, M) → Promise<WriteOutcome>` API (default impl falls back to existing `send` and returns Sent — backward-compatible)

**Layer 2 — DHT routes only to currently-reachable replicas**
- `DHTNetwork.livePeers()` default method (returns empty set = filter disabled)
- `DistributedDHTClient.targetNodes` filters `node.ring().nodesFor(key, RF)` by `network.livePeers()`
- `AetherNode` adapter override returns `clusterNode.network().connectedPeers() ∪ {self}`

**Layer 3 — Fast-fail on synchronous refusal**
- `DistributedDHTClient.dispatchTracked` is the common helper for `sendRemote{Get,Put,Remove,Exists}`
- On non-`Sent` outcome → `pendingOps.remove(correlationId); collector.onFailure(DHTError.peerUnreachable(peerId, reason))`
- `QuorumCollector.onFailure` existing logic: `if (failures > total - quorum) promise.fail(...)` — fires immediately, no waiting

**Deferred to RC2 (deliberately)**
- Hinted handoff (Cassandra-style): durable hint store + replay
- Sloppy quorum with fallback nodes: ring offset + tagged writes
- Per-peer write-failure circuit breaker: rolling window + exclusion-until-success

---

## Remaining open issues (in priority order)

### Issue #1 — 09-artifacts 1MB and 5MB push fail with HTTP 500 (HIGH)

**Symptom**: `[FAIL] [09-artifacts/1MB_artifact] Push 1.1.0 returned 500 (expected 2xx)`. Total suite duration 38s — fast-fail confirmed. Test client sends `PUT /repository/org/test/large-artifact-test/1.1.0/large-artifact-test-1.1.0.jar` with 1MB binary body. Server responds HTTP 500.

**Architecture before this session**: hung indefinitely (writeIfWritable silent drop → DHT quorum waits forever → no HTTP response). After this session: fast-fail returns a proper HTTP 500 — the architecture surfaces the error, which is now visible as a real product bug.

**Root cause investigation hints**:
- The 500 is server-emitted, not test-client-side. Server received the PUT, started processing, encountered an error, returned 500. Different code path from the old hang.
- Endpoint handler: search `grep -rn "/repository" aether/node/src/main/java` — likely `RepositoryRoutes.java` or `MavenProtocolHandler.java`.
- Body path: `ArtifactStore.deploy(artifact, content)` is called with the 1MB byte array. With the timeout now correctly wrapping the outer flatMap (`70e26f0ad`), a failed deploy bounds to 30s and returns failure. The 500 is therefore likely DHT quorum failure being surfaced as HTTP 500.
- Investigation steps:
  1. Capture node-1's stderr/stdout for a single 1MB push attempt — `ssh $TARGET_HOST 'docker logs aether-a-node-1 2>&1' | grep -E "ArtifactStore|deploy|500|quorum"` after isolating the test.
  2. Check `/api/cluster/topology` `connectedPeerCount` while the push is in flight — if peers are temporarily disconnected during the burst, live-replica filter may produce too-small target set.
  3. Add temporary DEBUG logging in `ArtifactStore.storeMetadataAndVersions` — see exactly which DHT write fails.
- **Hypothesis A**: the 16-chunk fan-out saturates outbound QUIC buffers so much that even the OTHER 4 nodes' channels go unwritable briefly. Live-replica filter then sees only self (or 0 peers) and fails quorum.
- **Hypothesis B**: the metadata write or versions-list write fails because by the time the chunks are all queued, the metadata-write target has been overloaded.
- **Hypothesis C**: serializer-level issue — 1MB+ chunks may hit some message-size limit in the QUIC stream config (line 142: `MAX_FRAME_LENGTH = 32 * 1024 * 1024`).
- **Hypothesis D**: actual server-side exception during DHT put — uncaught, returned as 500 by the HTTP handler.
- **Recommended first step**: run `09-artifacts/test-large-artifact.sh` in isolation against a freshly-bootstrapped cluster with verbose logging, and capture node-1's stack trace if any.

### Issue #2 — 02-chaos/Kill_2_nodes intermittent fail (task #30) (MEDIUM)

**Symptom**: `[FAIL] [02-chaos/Kill_2_nodes] pick_non_leader: only 1/2 candidates available (leader=node-2, pinned=<none>, cluster=b)`. The test needs to kill 2 non-leader nodes but `pick_non_leader` only finds 1 candidate.

**Root cause**: documented in `session-handover-2026-04-17.md` and elsewhere. The entry-point's `/api/status` only shows 4 ON_DUTY when the cluster has 5 because the 5th peer isn't yet probe-acked on the entry-point's local SWIM (per-reader variance). The transport-honest cross-reference (`StatusRoutes.toNodeInfo` ON_DUTY → UNKNOWN downgrade) makes this visible instead of papered over.

**Architectural fix** (deferred to RC2): cross-node `PeerObservationStore` aggregator. Each node observes peer states locally; the aggregator reduces them to a cluster-wide canonical view that all readers agree on. See `aether/docs/specs/membership-architecture-spec.md` v2 "Future work" section.

**RC1 workaround**: this test fails intermittently. Not a regression. Continues to be tracked as task #30.

### Issue #3 — 13-edge-cases/Cluster_ready_5_nodes: disable_auto_heal failed (MEDIUM)

**Symptom**: `[FAIL] [13-edge-cases/Cluster_ready_5_nodes] Cluster ready: disable_auto_heal failed — disruption budget cannot be deterministically tested under active auto-heal racing`.

**Root cause**: test attempts to disable CTM auto-heal so it can deterministically test the disruption budget (each test rejects N+1th drain). But `aether topology auto-heal disable` returns non-zero — likely because cluster state already inconsistent from earlier failed tests in 13-edge-cases or a previous suite cascade.

**Investigation hints**:
- `disable_auto_heal` helper is in `aether/tests/integration/lib/cluster.sh` — calls `aether topology auto-heal disable`
- Check whether `aether topology auto-heal status` works at the moment of failure — if it does and returns "already disabled", `disable_auto_heal` may be incorrectly treating that as failure
- 13-edge-cases EXIT trap explicitly warns: "operator must manually re-enable via 'aether topology auto-heal enable'". If a previous test failed before re-enabling, subsequent tests inherit the disabled state — which then "fails" to disable (because already disabled).
- **Recommended fix**: make `disable_auto_heal` idempotent (treat "already disabled" as success). Audit the helper for stricter response handling.

### Issue #4 — 13-edge-cases/App_routes_reachable (LOW)

**Symptom**: `[FAIL] [13-edge-cases/App_routes_reachable] App route http://192.168.0.71:8080/health not wired (expected EchoSlice handler to respond)`.

**Root cause**: the EchoSlice blueprint expects to serve `/health` but the route isn't registered. Earlier in this session we added a `routes.toml` for the EchoSlice (`39b921b7c`) but the test might still expect a specific endpoint that the slice doesn't actually define.

**Investigation hints**:
- Read `aether/tests/blueprints/test-echo/src/main/resources/org/pragmatica/aether/test/echo/routes.toml`
- Check whether `/health` is in the route table after blueprint deploy: `aether routes` or `/api/routes` 
- The handler that should respond to `/health` may not be wired in EchoSlice — different code path from the management `/health/live` endpoint
- **Recommended fix**: either add `/health` to EchoSlice's routes.toml, OR change the test to probe an endpoint that EchoSlice actually registers (e.g. `/echo`).

### Issue #5 — 03-scaling and 05-security 0p/3f (cascade from 02-chaos) (MEDIUM)

**Symptom**: Both suites report 0p/3f with very short duration (9s, 11s). Tests fail at `Cluster ready` step — cluster never reports healthy after the previous chaos test left it degraded.

**Root cause**: same chaos cascade as before — when 02-chaos leaves a half-recovered cluster B, subsequent destructive suites can't initialize. The fix from this session (DHT resilience architecture) makes the fast-fail clean (no hours of wall-time wasted), but doesn't address the underlying cluster-not-recovering issue.

**Investigation hints**:
- After 02-chaos completes, check `aether status` and `aether topology` against cluster B — what does the cluster actually look like?
- The `restore_cluster_baseline` helper in `cluster.sh` waits for "4+ ON_DUTY healthy cores (target=5)" but with the per-reader variance from issue #2, this may be unreliable
- `await_generation_quiesced` after 02-chaos sometimes times out per the log
- **Recommended approach**: dig into a single chaos test → next-suite handoff. Capture cluster state precisely at the transition. Likely needs the same `PeerObservationStore` work as issue #2.

### Issue #6 — BootstrapModuleTest pre-existing failure (task #23) (LOW)

**Symptom**: Unit test `BootstrapModuleTest$ClusterConfigSeed.initialCoreSizeAtQuorum_butLifecycleCountTooLow_seedDeferred:76` fails. Pre-existing.

**Root cause**: test asserts no `Put` is issued when lifecycle count is below threshold; the module DOES issue a Put. Either the test is stale or the module behaviour drifted.

**Recommended fix**: 30-min investigation — either update the test to match current intended behaviour or fix the module to honour the original contract.

---

## What's NOT a regression worth tracking

- **The "08-resources -1" mid-session** — was caused by DHT-prune-on-PeerDisconnected unwire (`d70d41c38`), then re-fixed by the DHT resilience architecture (live-replica filter + fast-fail). Final state: 08-resources back to 5p/0f.
- **The backpressure-buffer experiment** — fully reverted before commit. Lessons in `aether/docs/specs/dht-resilience-spec.md` "Why this fix does NOT include buffering" section.

---

## Key files modified this session

### DHT resilience architecture
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/WriteOutcome.java` — NEW
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/ClusterNetwork.java` — sendOutcome default
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java` — Layer 1 propagation
- `integrations/dht/src/main/java/org/pragmatica/dht/DHTNetwork.java` — sendOutcome + livePeers defaults
- `integrations/dht/src/main/java/org/pragmatica/dht/DHTError.java` — PeerUnreachable factory
- `integrations/dht/src/main/java/org/pragmatica/dht/DistributedDHTClient.java` — Layer 2 + 3
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` — DHTNetwork adapter wiring
- `aether/docs/specs/dht-resilience-spec.md` — NEW

### Cluster-label scoping
- `aether/environment/{aws,azure,docker,gcp,hetzner}/src/main/java/.../{Aws,Azure,Docker,Gcp,Hetzner}ComputeProvider.java` — AETHER_CLUSTER_NAME env fallback
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/{ClusterScaffoldCommand,DockerComposeTemplate}.java` — NEW
- `aether/node/src/main/java/org/pragmatica/aether/node/labels/ContainerLabelInspector.java` — NEW
- `aether/node/src/main/java/org/pragmatica/aether/Main.java` — `verifyClusterLabelConsistency` hook
- `aether/tests/integration/docker-compose-{a,b}.yml` — aether.cluster labels + AETHER_CLUSTER_NAME env
- `aether/tests/integration/lib/cluster.sh` — label+network filter for `_docker_container_by_node_id_label`
- `docs/rfc/RFC-0015-cluster-label-scoping.md` — NEW
- `aether/docs/operator/multi-cluster-deployment.md` — NEW
- `aether/docs/specs/cluster-label-scoping-spec.md` — NEW

### Storage + 06-deployment fixes
- `aether/node/src/main/java/org/pragmatica/aether/node/StorageFactory.java` — always-register artifacts + recursion fix
- `aether/tests/integration/lib/cluster.sh` — deploy_cleanup pipefail guard
- `aether/tests/integration/suites/14-storage/test-storage-{cli,management}.sh` — pipefail guards

### ArtifactStore + nginx removal
- `aether/resource/services/artifact-repo/src/main/java/.../ArtifactStore.java` — DEPLOY_TIMEOUT wraps outer flatMap
- `integrations/dht/src/main/java/org/pragmatica/dht/DHTTopologyListener.java` — unwired PeerDisconnected (replaced by live-replica filter in DHT)
- `aether/tests/integration/docker-compose-{a,b}.yml` — removed nginx mgmt-gateway service blocks
- `aether/tests/integration/run-tests.sh` — CLUSTER_*_MGMT direct to node-1
- (Deleted) `aether/tests/integration/nginx-mgmt-gateway-{a,b}.conf`

---

## Next-session start

```bash
# 1. Verify state
git log --oneline -5                  # expect 6bd50d26e at HEAD
git status --short                      # expect clean
git tag --list 'v1.0.0-rc1-candidate'  # expect present, @ 6bd50d26e

# 2. Decide focus — recommended in priority order:

# (a) Issue #1 — 09-artifacts 1MB/5MB HTTP 500. Most impactful, structurally clean.
#     Isolation run + verbose logging is the minimum first step.
#     cd aether/tests/integration && ./run-tests.sh --env remote --skip-build --suites 09

# (b) Issue #3 — 13-edge-cases disable_auto_heal idempotency. ~30-min fix, helps suite reliability.

# (c) Issue #2 — Kill_2_nodes per-reader variance (task #30). Architectural piece, needs PeerObservationStore work.
#     Spec at aether/docs/specs/membership-architecture-spec.md v2.

# (d) Issue #4 — App_routes_reachable EchoSlice route wiring. Tiny test-side or blueprint-side fix.

# 3. Continue with task #52 (unit tests for the new WriteOutcome / live-replica surfaces)
#    if you want the architecture covered before further changes. Mostly mechanical.

# 4. The cluster-label-scoping RC2 polish (k8s/terraform scaffolding) was explicitly
#    descoped per user direction — do NOT re-add.

# 5. Tag, push when done. Use the wrap-up flow.
```

---

## Critical gotchas — do NOT redo these mistakes

1. **Don't buffer messages under backpressure** — the failed experiment in this session proved it breaks chaos tests catastrophically. The DHT resilience architecture (signal-and-route-around) is the correct pattern. Re-implementing buffering will accumulate stale Rabia messages and break SWIM detection.

2. **Don't re-add `PeerDisconnected → DHT ring prune`** — the aggressive pruning caused rebalance storms under sustained write pressure. The live-replica filter in DHT (Layer 2 of resilience) replaces this — the ring stays stable, quorum computes against currently-reachable peers.

3. **Don't increase `network.send` API to require Promise<WriteOutcome>** — there are many fire-and-forget callers (Rabia broadcast, SWIM gossip, etc.) that don't want the overhead. `sendOutcome` is the additive parallel API for tracked sends. Only DHT consumes it.

4. **Pipefail traps**: `set -euo pipefail` + `grep` that legitimately matches nothing = silent script abort. The pattern appears in many test-infra helpers. When refactoring tests, audit for this. Wrap such greps in `(grep ... || true)` or use the `json_field` helper.

5. **`mvn verify` is forbidden when `HCLOUD_TOKEN` is set** — creates real Hetzner cloud resources. Use `mvn -pl <module> test` for tests and let `build-runner` agent own all Maven invocations.

6. **`build.sh` is currently blocked by pre-existing JBCT lint debt** across several non-storage files (worker/, http/security/, api/, etc.) — `mvn -pl aether/node install -am -DskipTests` works as a focused rebuild. Track but don't block on lint cleanup.

---

## References

- Predecessor: `aether/docs/internal/progress/session-handover-2026-05-17.md`
- Spec: `aether/docs/specs/dht-resilience-spec.md`
- Spec: `aether/docs/specs/cluster-label-scoping-spec.md`
- RFC-0015: `docs/rfc/RFC-0015-cluster-label-scoping.md`
- Operator guide: `aether/docs/operator/multi-cluster-deployment.md`
- Membership spec (for issues #2, #5): `aether/docs/specs/membership-architecture-spec.md`
- Tag `v1.0.0-rc1-candidate` @ `6bd50d26e`

---

**End of handover.** The architectural piece of this session — DHT resilience — is reusable for any other transport-bound path with similar failure modes (slice deploy, configuration KV writes, schema migration coordination). The signal-and-route-around pattern beats wait-and-mask in every chaos scenario.
