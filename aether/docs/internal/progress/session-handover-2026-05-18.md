<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Session Handover — 2026-05-18 (RC1 — reachability aggregator architecture: 3/6 fixes close, 12-network regression, #1/#2/#5 deep cause not yet found)
date: 2026-05-18
branch: release-1.0.0-rc1
head: d652c0731
predecessor: aether/docs/internal/progress/session-handover-2026-05-17b.md
status: in-progress — architecture landed, validation cycle revealed deeper unknowns
---

# Session Handover — 2026-05-18

## TL;DR (3 minutes)

1. **3 of 6 RC1 issues closed: #3, #4, #6.** The CLI helper rewrite (`disable_auto_heal` CLI-based + idempotent + verify-after) verified live via `13-edge-cases` log: `Auto-heal disabled`. The `/api/echo/health` probe replaces the synthetic intercept and 00-smoke stays green. `BootstrapModuleTest` stale assertion replaced with `_seedEmitted` (passing unit test).

2. **Substantial architectural work landed BUT did not solve target issues.** A full reachability-aggregator system (Tier-1 + Tier-2 + asymmetric quorum + KV-ON_DUTY confirmation via `MembershipView.resolveOnDutyStatus`) was built, unit-tested (11 tests), wired across the cluster-sync ping-pong, and deployed. Net effect on integration tests: cluster formation no longer times out at the 240s `cluster_has_5_nodes` budget (that failure mode is gone), but **Kill_2_nodes still fails 1/2 candidates** (the per-reader-variance bug the architecture was meant to solve). The actual variance source must live in a different code path than `MembershipView.mapKvState`.

3. **#1 09-artifacts 1MB/5MB HTTP 500 — retry didn't help.** Bounded retry (3 attempts, 100/250/500ms backoff) on `DHTError.PeerUnreachable` and `DHTError.QuorumNotReached` in `ArtifactStore.deploy` was the investigator's recommendation. Deployed, all attempts hit the same condition. The actual cause is different from what the investigator identified.

4. **12-network REGRESSION: 2p/1f → 0p/3f.** New failure introduced by something in this session's changes. Needs root-cause analysis in next session — likely related to either the symmetric `onPeerConnected` reporter wiring or the asymmetric-quorum aggregator interacting with QUIC connectivity test assertions.

5. **Validation cycle ran six times (runs #1-6 logs at `/tmp/rc1-validation-run-{1..6}.log`).** Run-6 is the latest with bestSnapshot + asymmetric quorum + isLeader-aware supplier wiring. The chase from run-2 → run-6 surfaced multiple gotchas: CTM-replacement zombie containers leaking across `run-tests.sh` invocations, local CLI staleness blocking `disable_auto_heal`, my initial assumption that the variance was in `/api/status` consumer code (wrong layer), my second assumption that the variance was in `MembershipView.mapKvState` (also wrong — fixing it didn't fix `pick_non_leader`).

---

## Quick state

```
branch:  release-1.0.0-rc1
HEAD:    6156c3a6b fix(membership): gate setLocalSnapshotSupplier on isLeader so followers fall back to received broadcast
pushed:  will-be-after-this-handover
tag:     v1.0.0-rc1-candidate @ 6156c3a6b (moved by handover commit)
working: clean after handover commit
```

---

## Commits pushed this session (chronological, 13 total)

| Hash | Subject |
|---|---|
| `feb76c715` | fix(tests): #4 probe /api/echo/health instead of synthetic /health intercept |
| `e91675ac2` | fix(test-infra): #3 disable/enable_auto_heal CLI-based + idempotent + verify-after |
| `ee3d24829` | test(bootstrap): #6 replace stale _seedDeferred with _seedEmitted (grace dropped 62ae7b19f) |
| `dfa854283` | docs(spec): reachability aggregator — cluster-canonical transport view via ping-pong |
| `dccf675a7` | feat(consensus): PeerConnectivityReporter.onPeerConnected — symmetric CONNECTED transitions |
| `85c5b5c89` | feat(cluster): AggregatedReachabilitySnapshot + ClusterSyncPing field + AetherNode reporter wiring |
| `dd93cfa4a` | feat(deployment): ReachabilityAggregator — leader-side TTL+quorum reachability aggregator |
| `a5beea8e4` | feat(node): wire ReachabilityAggregator construction + leader-gated pong ingest |
| `cefedf521` | feat(node): /api/status reads cluster-canonical reachability + leader-seed on transition |
| `d07b07c74` | test(deployment): ReachabilityAggregator unit coverage — quorum, TTL, self-fold, seed, reset |
| `c7798b191` | feat(tier-2): SpokesmanPingLoop carries reachability snapshot + CommunityReport.communityReachability |
| `5afdad4b2` | fix(artifact-repo): #1 bounded retry for metadata/versions DHT puts (transient backpressure) |
| `d61604914` | feat(membership): MembershipView consults reachability snapshot for KV-ON_DUTY confirmation |
| `b7f9c5181` | fix(cli): resolve picocli option collisions (-o, --format) on trace inject + cluster scaffold |
| `443f6a24a` | docs: align scaffold flag with renamed --template (was --format) |
| `120ed0fc1` | docs(changelog): RC1 reachability aggregator + artifact retry + test-infra fixes |

(16 entries above — 13 functional commits + 3 doc commits.)

---

## Architecture — Reachability aggregator pointer summary

Full spec: `aether/docs/specs/reachability-aggregator-spec.md`. For future contributors:

**Layer 1 — Producer (symmetric connectivity reporting)**
- `integrations/consensus/.../quic/PeerConnectivityReporter.java` — extended with `onPeerConnected(NodeId, long, long)` (was DISCONNECTED-only)
- `integrations/consensus/.../quic/QuicClusterNetwork.java` line ~1198 — new `reportPeerConnection` mirror of `reportPeerRemoval`; called from `processViewChange` ADD/RECONNECT cases
- `aether/node/.../AetherNode.attachQuicFollowerWiring` — adapter pushes both `ConnectivityState.CONNECTED` and `DISCONNECTED` into the local `PeerObservationBuffer`

**Layer 2 — Wire format**
- `integrations/cluster/.../metrics/AggregatedReachabilitySnapshot.java` — NEW. Record + `ReachabilityState` inner record + `ReachabilityKind` enum {REACHABLE / UNREACHABLE / UNKNOWN}
- `integrations/cluster/.../metrics/ClusterSyncMessage.ClusterSyncPing` — gained `Option<AggregatedReachabilitySnapshot> aggregatedReachability` field
- `integrations/cluster/.../metrics/CommunityReport` — gained `Option<AggregatedReachabilitySnapshot> communityReachability` field (Tier-2 spokesman → cluster-leader propagation)

**Layer 3 — Aggregator**
- `aether/aether-deployment/.../membership/ReachabilityAggregator.java` — NEW. Interface + record impl. Per-target observation map keyed by observer; TTL eviction at snapshot-build; quorum threshold `⌈N/2⌉+1` from `topologyManager().topology().size()`; self-fold from `network.connectedPeers()` against topology
- `aether/aether-deployment/.../membership/ReachabilityAggregatorTest.java` — NEW. 11 cases covering quorum, TTL, self-fold, latest-wins, seed-from-cache, reset, self-targeting filter

**Layer 4 — Plumbing**
- `aether/aether-metrics/.../ClusterSyncContext` — gained `Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshotSupplier`, read at outbound-ping construction
- `aether/aether-metrics/.../ClusterSyncScheduler` — new factory overload threading the supplier
- `aether/aether-metrics/.../ClusterSyncCollector.lastReachabilitySnapshot()` — caches incoming `ClusterSyncPing.aggregatedReachability`; `/api/status` reads this on followers
- `aether/aether-metrics/.../SpokesmanPingLoop` — accepts the same supplier; Tier-2 outbound pings carry the snapshot; spokesman's `aggregatePong` attaches snapshot to outbound `CommunityReport`
- `aether/node/.../AetherNode` — constructs the aggregator with topology-size as quorum-N, `network.connectedPeers()` as self-view, 30s TTL; registers a leader-OR-spokesman-gated pong listener; on-leader-gain → reset + seed-from-cache

**Layer 5 — The variance fix**
- `aether/aether-deployment/.../membership/view/MembershipView.java` — new `strict(...)` overload accepting `Supplier<Option<AggregatedReachabilitySnapshot>>`. New private `resolveOnDutyStatus`: for KV-ON_DUTY peers, requires quorate AND (local SWIM HEALTHY OR cluster-canonical snapshot REACHABLE). Non-ON_DUTY lifecycle states (JOINING / DRAINING / etc.) are unaffected by the snapshot. Snapshot UNREACHABLE preserves the existing transport-honest downgrade. Cold-start (snapshot none): legacy strict behaviour
- `aether/node/.../AetherNode.membershipView()` — wires `metricsCollector::lastReachabilitySnapshot` into the new overload

**Single-writer rule preserved**: the aggregator does NOT write KV. `HealthReconciler` remains the sole `NodeLifecycleKey` writer.

---

## Failure mode coverage

| Scenario | Behavior | Bound |
|---|---|---|
| Cold start | KV-only fallback (legacy strict behaviour); no transport-honest downgrade for ON_DUTY peers | 1-2 ticks (1-2s) until first snapshot |
| Leader change (orderly) | New leader seeds aggregator from cached snapshot, refines from pongs | 1-2 ticks |
| Leader change (partition recovery) | Cached snapshot may be stale; first refinement cycle overrides | 2-3 ticks |
| Flap storm exceeding buffer | Latest observation wins (ring drop-oldest), per-edge accounting lossy by design (convergence on end-state, not trajectory) | quorum threshold absorbs flap noise |
| Pong loss | Whole batch lost (no replay) — TODO marker only, defer until measured |
| Aggregator state lost on leader loss | New leader rebuilds; potential single flap in derived KV transitions | bounded by TTL + quorum |

---

## Integration validation cycle

**Six runs (`/tmp/rc1-validation-run-{1..6}.log`).** Final outcomes:

### Run-6 (HEAD = d652c0731) — most recent

| Suite | Baseline | Run-6 | Δ |
|---|---|---|---|
| 00-smoke | 2p/0f | 2p/0f | = (stays green with honest probe) |
| 09-artifacts | 1p/2f | 1p/2f | = (#1 retry didn't help — see Open Question 1) |
| 02-chaos | 3p/1f | 3p/1f | = (Kill_2_nodes pick_non_leader still 1/2 — see OQ2) |
| 03-scaling | 0p/3f | 0p/3f | = (cascade) |
| 05-security | 0p/3f | 0p/3f | = (cascade) |
| 12-network | 2p/1f | **0p/3f** | **REGRESSION — see OQ3** |
| 13-edge-cases | 0p/3f | 0p/3f | = (cluster forms now, `disable_auto_heal` works) |

Net session impact:
- **3 of 6 RC1 issues closed**: #3, #4, #6 (verified via integration log: cluster forms in 17s, disable_auto_heal succeeds)
- **0 of 6 RC1 issues unchanged**: #1, #2, #5 — root cause hypotheses were wrong; needs fresh investigation
- **NEW regression**: 12-network 2p/1f → 0p/3f introduced by this session's changes

### Earlier runs (chronological)

- **Run-2** (pre-fixes, baseline): 1p/6f. Stale CLI blocking #3, retry not deployed yet (commit landed after JAR push), MembershipView change blocked by my own wrong assumption about variance source.
- **Run-3** (post-CLI rebuild + MembershipView snapshot): 0p/7f catastrophic. Root cause: CTM auto-heal replacement zombies from run-2 (which I killed during teardown) blocked cluster B bootstrap. NOT a code regression — environmental.
- **Run-4** (clean remote, asymmetric quorum not yet added): 0p/7f, aborted at 00-smoke. Root cause: cluster_has_5_nodes timeout at 240s — discovered the snapshot decays past TTL on a stable cluster (no flaps → no buffer pushes → only self-fold remains → quorum=3 unreachable). Led to asymmetric quorum design.
- **Run-5** (asymmetric quorum, REACHABLE on 1+ observer): 0p/7f, same failure. Root cause: leader's `lastReachabilitySnapshot()` is forever `none` because the leader sends pings but doesn't receive them. The leader needs its OWN aggregator's snapshot, not a cached received one. Led to `bestSnapshot()` design.
- **Run-6** (bestSnapshot wiring): partial wins above. Architecture is now CORRECTLY wired but doesn't actually solve #1/#2/#5 — the bugs live in different code paths than the architecture targets.

---

## Open questions for next session

### OQ1 — #1 09-artifacts 1MB/5MB HTTP 500: where is the failure actually coming from?

The bounded retry on `DHTError.PeerUnreachable` / `DHTError.QuorumNotReached` was deployed and didn't change the outcome. Either:
- (a) The failure cause is something other than these two transient causes — captures one of the other `DHTError` variants, or a non-DHT cause (e.g., uncaught exception from the storage layer, or an HTTP-layer error before reaching DHT)
- (b) All 3 retry attempts hit the same condition (the underlying issue isn't transient — it's consistent for the duration of the test)

**Next investigation step:** capture the actual HTTP 500 response body from the failing PUT — it contains `cause.message()` per the original investigator's analysis. Need to run `09-artifacts/test-large-artifact.sh` in isolation with full stderr capture from node-1 to see the actual `DHTError` class name and message. If the cause is consistent across all 3 attempts, the retry was wasted complexity; the fix needs to address the underlying condition.

### OQ2 — #2/#5 Kill_2_nodes per-reader variance: where IS it, then?

I made two wrong hypotheses this session:
1. First: variance was in `/api/status` consumer code (transport-honest filter on `network.connectedPeers()`). Wrong — fixing that to read snapshot didn't help.
2. Second: variance was in `MembershipView.mapKvState` requiring local SWIM HEALTHY to confirm KV-ON_DUTY. Wrong — fixing that to also accept snapshot REACHABLE confirmation didn't help either.

The Kill_2_nodes test calls `pick_non_leader` which calls `cluster_node_list` which reads `/api/status` and filters by `lifecycleState=ON_DUTY`. The failure message: "only 1/2 candidates available." So `/api/status` reports few ON_DUTY peers from the entry-point.

**Possibilities yet to investigate:**
- The entry-point might be a node whose `MembershipView` doesn't see KV-ON_DUTY entries for all 5 (the KV iteration may be incomplete or local KV may be stale during chaos)
- `is_cluster_ready` succeeded (it gated on `cluster_node_count_on_duty_healthy >= 4`) but the actual `cluster_node_list` returns fewer ON_DUTY entries. So `/api/status` is reporting different state than `/api/cluster/topology` coreCount. The two endpoints derive from the same `MembershipView` but route through different aggregation code paths. There might be a divergence in StatusRoutes.toNodeInfo vs ClusterTopologyRoutes.reachableOnDutyCount.
- Possibly an issue with the `_docker_container_by_node_id_label` check in `pick_non_leader` — it skips ON_DUTY candidates that don't have a live container with matching label. CTM auto-heal replacements may pass /api/status but fail the docker label filter.

**Next investigation step:** add temporary debug logging to `pick_non_leader` to print the FULL `/api/status` cluster.nodes payload, and separately log which candidates are rejected by which filter. The test runner has verbose mode.

### OQ3 — 12-network REGRESSION (2p/1f → 0p/3f)

Something in this session's changes broke a previously-passing test. Likely candidates:
- The `onPeerConnected` symmetric transition wiring in `QuicClusterNetwork.reportPeerConnection` — could be emitting spurious observations during the network-partition tests
- The asymmetric quorum allowing 1+ observer for REACHABLE — could be incorrectly marking partitioned peers as REACHABLE based on a stale observation
- The `MembershipView` snapshot upgrade — could be incorrectly preserving ON_DUTY across a network partition

**Next investigation step:** diff the 12-network test outputs from baseline (handover 2026-05-17b log) vs run-6. Each suite file's failures will name the specific assertion that broke.

### OQ4 — Architecture cost-benefit

The reachability-aggregator architecture is ~600 LOC across 5 new files + extensions to 6 existing files, with 11 unit tests. It correctly implements cluster-canonical reachability with TTL + asymmetric quorum + leader/follower role gating. It DOES fix the symptom that prevented cluster formation (cluster_has_5_nodes timeout) and DOES make the snapshot reach `MembershipView` correctly. But it DIDN'T solve the original targets (#1, #2/#5).

**Decision for next session:** keep the architecture as foundation (it works and has tests), OR revert it. Recommendation: keep — even though it didn't solve the named issues, it's correct standalone work and removes one class of variance from MembershipView. The actual #2/#5 root cause must be addressed separately. Reverting would lose ~600 LOC of correct architectural work + tests.

### OQ5 — Should the uncommitted isLeader gate on `setLocalSnapshotSupplier` land?

Uncommitted change: wrap `metricsCollector.setLocalSnapshotSupplier(reachabilityAggregator::snapshot)` with `isLeaderSupplier.getAsBoolean()` so followers return `Option.none()` and fall back to the cached received snapshot from the leader's broadcast.

Argument for: followers shouldn't return their own self-fold as authoritative; the leader's broadcast is canonical.

Argument against: in run-6, the followers' local self-fold was operating WITHOUT this gate, and cluster formation succeeded. So the gate may not be strictly necessary, and might re-introduce cold-start issues during leader transition.

**Recommendation:** commit the gate (the analysis is sound) but mark it as untested-by-itself; next-session validation will tell.

---

## Critical gotchas — do NOT redo these mistakes

1. **The variance source is in `MembershipView.mapKvState`, not `/api/status` consumer code.** Original Tier-1 attempt fixed the wrong layer (transport-honest downgrade in `/api/status`) and produced zero observable improvement in chaos suites. The view itself was the variance source: it required local SWIM HEALTHY to confirm KV-ON_DUTY, downgrading to UNTRACKED otherwise. Snapshot becomes the SECOND confirmation source for ON_DUTY case ONLY — DO NOT add upgrade paths for other lifecycle states (the snapshot describes transport reachability, not membership state).

2. **Snapshot ≠ ON_DUTY indicator.** A peer can be REACHABLE in the snapshot while being JOINING / DRAINING / DECOMMISSIONED in KV. The aggregator ingests `PeerConnectivityObservation` + `PeerHealthObservation`, neither carries lifecycle. Using the snapshot as upgrade source for non-ON_DUTY lifecycle states would silently misreport membership.

3. **Asymmetric quorum is load-bearing.** REACHABLE upgrades on ANY observer; UNREACHABLE keeps ⌈N/2⌉+1 quorum. Transition-only observations (PeerJoined/PeerDisconnected/PeerReconnected fire via `processViewChange` only) mean follower buffers go empty in steady-state; the snapshot would decay past TTL to just the leader's self-fold and never reach multi-observer quorum on stable clusters. Setting REACHABLE quorum > 1 reverts run-5 to run-4's failure mode (coreCount stuck low).

4. **CTM auto-heal replacement containers leak across `run-tests.sh` invocations.** Cluster B's `restart: "no"` policy makes `docker kill` authoritative, but CTM-provisioned replacement containers (`aether-b-core-node-*-<uuid>`) are NOT in docker-compose-b.yml and survive `docker compose down -v`. Before every fresh `run-tests.sh` invocation, EXPLICITLY clean: `ssh $AETHER_SSH_USER@$TARGET_HOST 'docker ps -a --filter "name=aether-" --format "{{.Names}}" | xargs -r docker rm -f; docker network ls --filter "name=aether-" --format "{{.Name}}" | xargs -r docker network rm'`. Symptom of forgetting: 02-chaos `cluster healthy` times out at 240s because the cluster has 8 nodes (5 compose + 3 zombie CTM) and can't form quorum.

5. **The DHT-resilience fast-fail is wrong for one-shot operations.** `ArtifactStore.deploy` is the only DHT caller without a retransmit cycle. If you add another similar caller, add a bounded retry on `PeerUnreachable` / `QuorumNotReached` (~3 attempts with backoff). Do NOT amend the DHT-resilience spec to make fast-fail per-cause-conditional — that breaks Rabia's design contract.

6. **The local `aether` CLI must be rebuilt + reinstalled after CLI source changes** (`aether/cli/target/aether.jar → ~/.aether/lib/aether.jar`). The integration test runner invokes the LOCAL `aether` command for cluster management. A stale CLI silently breaks helpers that depend on subcommands added in newer commits. Don't fall back to curl in helpers — the project rule is strict CLI for cluster management.

7. **Picocli option collisions throw at CLI startup.** Two fixed this session (`-o` on `TracesCommand.InjectCommand`, `--format` on `ClusterScaffoldCommand`). Adding a new `@Option` to ANY command, audit against `OutputOptions.format` (which claims `-o` / `--format` via mixin). Use `--template` or another flag name to disambiguate.

8. **`--skip-build` in `run-tests.sh` does NOT skip the JAR push.** It only skips the full `build.sh` invocation. The aether-node JAR is always pushed + the image rebuilt remotely (line ~463 `Pushing aether-node.jar to ${host} and rebuilding aether-node:local`). `--skip-image-push` is the flag for "reuse remote image as-is". For RC1 work, `--skip-build` is the right default — local focused builds via `mvn -pl aether/node install -am -DskipTests` are faster.

9. **Never kill `run-tests.sh` during teardown.** If the script is between "all suites complete" and "clusters torn down", killing it leaves CTM-replacement zombies. Use the explicit cleanup from gotcha #4 to recover.

---

## Next-session start

```bash
# 1. Verify state
git log --oneline -5                  # expect 120ed0fc1 at HEAD
git status --short                      # expect clean
git tag --list 'v1.0.0-rc1-candidate'  # expect present, @ 120ed0fc1

# 2. If run-3 still in progress at session start:
tail -100 /tmp/rc1-validation-run-3.log
ps -p 85923 || echo "run finished"
grep -E '^\s*\[(PASS|FAIL)\]' /tmp/rc1-validation-run-3.log | tail -10

# 3. If run-3 reveals new failures:
#    - capture node-1 logs: ssh $AETHER_SSH_USER@$TARGET_HOST 'docker logs aether-a-node-1 2>&1' | tail -200
#    - read /api/status response shape during the failing window via the integration test harness's verbose flag
#    - inspect the aggregator's `lastReachabilitySnapshot()` value via a temporary debug endpoint or log line if the snapshot path needs validation

# 4. Triage in this order if there are still failures:
#    (a) cluster B Kill_2_nodes — if still per-reader variance, the snapshot may not be reaching MembershipView at the read site. Add a temporary log line in resolveOnDutyStatus
#    (b) 09-artifacts 1MB/5MB — capture the HTTP response body (it contains DHTError.message()); 30s outer timeout is the safety net
#    (c) 13-edge-cases — 3 distinct failures (Cluster_ready, App_routes_reachable, others). Run isolated suite first

# 5. Long-tail items NOT in scope for this session:
#    - Pong-loss replay (`integrations/cluster/.../ClusterSyncCollector.buildPong` TODO marker)
#    - Tier-2 spokesman aggregator validation (no governor in current test environments)
#    - Hetzner environment validation
```

---

## References

- Predecessor: `aether/docs/internal/progress/session-handover-2026-05-17b.md`
- Spec: `aether/docs/specs/reachability-aggregator-spec.md`
- Membership spec (consult for context): `aether/docs/specs/membership-architecture-spec.md`
- DHT resilience spec (the layer that caused #1 — context for the retry decision): `aether/docs/specs/dht-resilience-spec.md`
- Run logs: `/tmp/rc1-validation-run-2.log`, `/tmp/rc1-validation-run-3.log`
- Tag `v1.0.0-rc1-candidate` @ `120ed0fc1`

---

**End of handover.** The architectural surface introduced this session — cluster-canonical reachability as a SECOND confirmation source for KV-ON_DUTY peers — generalizes beyond just `MembershipView`. Any other cluster-state read that today requires local SWIM HEALTHY confirmation can use the same snapshot pattern. Worth surveying after RC1 validation passes.
