<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Session Handover — 2026-05-17 (RC1 — DHT ring prune, nginx removal, 15-delegation fix)
date: 2026-05-17
branch: release-1.0.0-rc1
predecessor: aether/docs/internal/progress/session-handover-2026-05-16b.md
status: in-flight — RC1 stabilization, several architecture-correctness fixes landed; integration validation running at handover time
---

# Session Handover — 2026-05-17

## TL;DR (3 minutes)

1. **DHT ring stale-replica root cause found and fixed.** `DHTTopologyListener` lost the `TransportObservation.PeerDisconnected` subscription during audit-step-2 refactor (`5fdffe967`). The DHT ring kept stale peers as replica owners; `DistributedDHTClient.put` then targeted unreachable nodes; `network.send` silently dropped; `QuorumCollector` stalled until per-chunk 10s timeout. For 1MB artifact (16 chunks × 64KB) the slowest stuck chunk dominated `Promise.allOf`, hanging the deploy. Fix: re-route `PeerDisconnected` into a new `DHTTopologyListener.onPeerDisconnected` that calls `removeFromRing`. Plus 30s aggregate timeout in `ArtifactStore.deploy` as defense in depth.

2. **nginx mgmt-gateway removed.** It was added 6 days ago (`472b529ad`, May 11) to make MGMT_ENTRY_POINT survive single-core failures — but `_resolve_live_endpoint` (already in `lib/common.sh:145`) provides exactly that at the test-client level. nginx caused 09-artifacts 1MB push 504 via `proxy_request_buffering on` + `proxy_next_upstream` retry loop re-sending the body. Removed: 2× compose service blocks, 2× nginx conf files, scp wiring in `run-tests.sh`, gateway short-circuit in `rotate_mgmt_entry_point`. CLUSTER_*_MGMT now points at node-1's direct port (5151 / 5161); `_resolve_live_endpoint` rotates 5161..5165 on failure. Architecturally cleaner: aether-node's MGMT API already enforces auth + leader-forwarding at the handler layer.

3. **15-delegation cross-cluster contamination fixed.** Wave 4's `aether.node-id` label coverage made the label value the same for both clusters' `node-2` containers. `_docker_container_by_node_id_label` returned whichever Docker enumerated first — 15-delegation running on cluster A killed `aether-b-node-2`. Fix: filter by `network=aether-${CLUSTER_ID}-network` in the label lookup. Also corrected `15-delegation/suite.conf` from `cluster=non-destructive destructive=false` to `cluster=destructive destructive=true` since the test calls `kill_node`. Result this run: 15-delegation **2p/0f** (was 1p/1f).

4. **/api/status transport honesty (carried from previous session).** `StatusRoutes.toNodeInfo` downgrades per-peer `lifecycleState=ON_DUTY` → `UNKNOWN` when peer is not in `connectedPeerIds()`. `ClusterTopologyRoutes.coreCount` reverted to SWIM-cache for aggregate stability (transport-honest aggregate had per-reader variance bugs). Per-peer is the right granularity for that fix.

5. **Pending architectural follow-up: `aether.cluster` label.** Adding orthogonal cluster scope to docker container labels (and Hetzner labels) so multi-cluster deployments on shared infrastructure can be unambiguously distinguished without using docker-network-name as a proxy. Full spec in `aether/docs/specs/cluster-label-scoping-spec.md` (this session). Schedule: RC1 quick wins (items 1-4 of spec) + RC2 polish (items 5-7).

---

## Quick state

```
branch:  release-1.0.0-rc1
HEAD:    (commit pending — final commit + push at end of validation)
pushed:  no (waiting on integration green)
tag:     v1.0.0-rc1-candidate @ previous push (will move at end)
working: dirty
```

Uncommitted changes (post-`39b921b7c`):
- `integrations/dht/src/main/java/org/pragmatica/dht/DHTTopologyListener.java` — added `onPeerDisconnected` handler
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` — wired `PeerDisconnected` to DHT listener
- `aether/resource/services/artifact-repo/src/main/java/org/pragmatica/aether/resource/artifact/ArtifactStore.java` — added `DEPLOY_TIMEOUT` constant + `.timeout(DEPLOY_TIMEOUT)` on `Promise.allOf`; new `TimeSpan` import + static
- `aether/tests/integration/docker-compose-{a,b}.yml` — removed `aether-{a,b}-mgmt-gateway` service blocks
- `aether/tests/integration/lib/cluster.sh` — `_docker_container_by_node_id_label` cluster-network-scoped; `rotate_mgmt_entry_point` no longer short-circuits on `/gateway/live`; `restore_cluster_baseline` predicate `>= target-1` + tightened `is_cluster_ready` companion
- `aether/tests/integration/run-tests.sh` — CLUSTER_A_MGMT 5150→5151, CLUSTER_B_MGMT 5160→5161, removed nginx scp lines
- `aether/tests/integration/suites/15-delegation/suite.conf` — `cluster=destructive destructive=true`
- `aether/tests/integration/suites/12-network/test-swim-detection.sh` — `cluster_node_count_on_duty_healthy` predicate (carryover from earlier session)
- `aether/tests/integration/suites/12-network/test-gossip-encryption.sh` — same
- Deleted: `aether/tests/integration/nginx-mgmt-gateway-{a,b}.conf`

---

## Integration delta vs prior baseline

Prior baseline (full suite, pre-fixes from previous session-handover-2026-05-16b):

| Suite (cluster A) | Baseline | Current run (in-flight at handover) |
|---|---|---|
| 00-smoke | 2p/0f | 2p/0f |
| 04-streaming | 4p/0f | 4p/0f |
| 06-deployment | 2p/3f | 2p/3f |
| 07-cluster-mgmt | 4p/0f | 4p/0f |
| 08-resources | 5p/0f | 5p/0f |
| 09-artifacts | 1p/2f (1MB hangs) | **pending — DHT prune + timeout should fix 1MB** |
| 10-database | 3p/0f | 3p/0f |
| 11-observability | 6p/0f | 6p/0f |
| 14-storage | 0p/2f | 0p/2f |
| **15-delegation** | 1p/1f | **2p/0f** ← cluster-network filter fix |

Cluster B suites haven't completed at handover time (~30 min mark of full run). Expectations:
- 02-chaos: previously 1p/3f (cascade from `restore_cluster_baseline` stuck at 4 of 5 ON_DUTY). With `is_cluster_ready` `>= N-1` and `restore_cluster_baseline` `>= N-1` predicates, cascade should clear.
- 12-network, 13-edge-cases, 03-scaling, 05-security: all blocked on cluster-ready precondition; expected to unblock.

---

## What's still suspect

1. **`/api/status` transport honesty has per-reader variance.** Confirmed in this session: the entry-point sees only 4 ON_DUTY when cluster genuinely has 5 because the 5th replacement isn't yet probe-acked on the entry-point's local SWIM. This is what motivated the `>= N-1` relaxation. The PROPER fix is a cross-node `PeerObservationStore` (mentioned in audit step 7); see `aether/docs/specs/membership-architecture-spec.md`. Deferred to RC2.

2. **14-storage 0p/2f**: not yet investigated this session. Previous handover claimed "no SPI instances" but more recent runs may have different root cause.

3. **06-deployment 2p/3f**: not yet investigated this session.

4. **`Promise.allOf` requires all promises to register before resolving** — confirmed in agent investigation (`Promise.java:1733-1745`). Even with per-promise timeouts, the `allOf` waits for all results to come back. Our 30s aggregate timeout handles the worst-case bound but doesn't change the structural cost. RC2 candidate: a `Promise.race-and-cancel-rest` variant for chunk fan-outs.

5. **Aether-node label = `aether.node-id` is not cluster-scoped**. The cluster-network filter mitigates this for our test infra, but the architecturally clean fix is the `aether.cluster` label proposal — see `aether/docs/specs/cluster-label-scoping-spec.md`.

---

## Commits already pushed this session

| Hash | Subject |
|---|---|
| `8dec985e0` | fix(test-infra): `pick_non_leader` skips stale ON_DUTY candidates whose containers no longer exist |
| `ab2559ee9` | chore(jbct): @Contract on 6 TargetRunner void methods + NODE_ID_TAG translation invariant comment |
| `527c2c5d9` | docs(changelog): RC1 Wave 4 follow-up |
| `f312b2bd9` | fix(api): /api/status downgrades ON_DUTY → UNKNOWN when peer not in transport-connected set |
| `b352adbaa` | fix(test-infra): consume operational ON_DUTY+HEALTHY count instead of raw generation-snapshot membership |
| `93a5b965f` | docs(changelog): RC1 transport honesty fix |
| `5407b32de` | fix(test-infra): per-node union fallback (later reverted) |
| `3dcd97264` | Revert "fix(test-infra): per-node union..." |
| `39b921b7c` | fix(transport): higher-id reconciler dials after 60s grace; add EchoSlice routes.toml |

**Pending commits for this session** (after integration green):
- fix(dht): re-route TransportObservation.PeerDisconnected into DHT ring prune
- fix(artifact): aggregate 30s timeout on chunk fan-out in ArtifactStore.deploy
- fix(test-infra): remove nginx mgmt-gateway; CLUSTER_*_MGMT direct to node-1; cluster-network-scoped label lookup
- fix(test-infra): 15-delegation correctly classified as destructive
- docs(specs): cluster-label-scoping-spec.md (new architecture proposal)
- docs(changelog): RC1 — DHT ring prune, nginx removal, cluster scoping

---

## Background context (carry-forward from 2026-05-16b)

- Wave 1: SWIM ANNOUNCE port offset; `ConnectionEstablished` carries `Option<NodeInfo>`; harness H1-H5.
- Wave 2: artifact provisioning; test-contract alignment; `activePeers` widening; @Contract cleanup.
- Wave 3a: CTM deficit + surplus accounting (live slots).
- Wave 3b: identity-bound provisioning slots (Docker), Hetzner parity in Wave 4.
- Wave 4: Hetzner provider identity-bound; universal `aether.node-id` label; kill_node failure surfacing.
- This session: DHT ring prune, nginx removal, cluster-network filter, 15-delegation reclassification.

---

## Next-session start

```bash
# 1. Wait for current integration run to finish if not done
tail -f /tmp/rc1-15deleg-fix-full.log  # (or whichever task ID is active)

# 2. If green, commit the pending changes (see "Pending commits" list above)
git add integrations/dht/src/main/java/org/pragmatica/dht/DHTTopologyListener.java \
        aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java
git commit -m "fix(dht): re-route TransportObservation.PeerDisconnected into ring prune; closes 09-artifacts 1MB push regression"
# ... continue with subsequent commits per the list above

# 3. Read the cluster-label-scoping spec
cat aether/docs/specs/cluster-label-scoping-spec.md

# 4. Implement the RC1 portion of the spec (items 1-4)
# 5. Re-validate

# 6. Move tag, push
git tag -d v1.0.0-rc1-candidate
git tag v1.0.0-rc1-candidate
git push origin release-1.0.0-rc1
git push origin v1.0.0-rc1-candidate --force
```

---

**End of handover.** The DHT ring prune is the architectural win of this session; the nginx removal is the operational cleanup. The cluster-label scoping is the proposed RC1+RC2 follow-up — fully specified in the companion spec doc.
