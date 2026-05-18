<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Session Handover — 2026-05-18 (RC1 — reachability aggregator + KV-ON_DUTY confirmation + #1/#3/#4/#6 fixes)
date: 2026-05-18
branch: release-1.0.0-rc1
head: 120ed0fc1
predecessor: aether/docs/internal/progress/session-handover-2026-05-17b.md
status: in-progress — Tier-1 + Tier-2 architecture landed, integration validation in third re-run
---

# Session Handover — 2026-05-18

## TL;DR (3 minutes)

1. **Reachability aggregator landed (Tier-1 + Tier-2, code-complete, 11 unit tests pass).** Cluster-canonical transport-reachability view broadcast via the existing metrics ping-pong: `AggregatedReachabilitySnapshot` carried on `ClusterSyncPing`, leader-side `ReachabilityAggregator` ingesting from pongs with TTL + ⌈N/2⌉+1 quorum, follower-side cache + on-leader-gain seed. The variance-source fix lives at `MembershipView.mapKvState`: when `kvState == ON_DUTY` and local SWIM hasn't acked HEALTHY, the snapshot is the SECOND confirmation source. Spec: `aether/docs/specs/reachability-aggregator-spec.md`.

2. **#1 09-artifacts HTTP 500 — bounded retry in ArtifactStore.** Root-caused via background investigation: the DHT-resilience layer's fast-fail-on-`BackpressureRefused` is correct for Rabia (built-in retransmit) but wrong for one-shot `ArtifactStore.deploy` (no retry cycle). Added `dhtPutWithRetry` around the metadata + versions-list writes — 3 attempts with 100/250/500ms backoff, selective on `DHTError.PeerUnreachable` / `QuorumNotReached`, 30s outer timeout safety net.

3. **#3 disable_auto_heal rewritten** (CLI-based, idempotent, verify-after) AND local CLI was stale — built 2026-05-09, predated the `auto-heal` subcommand. Rebuilt + reinstalled. Two pre-existing picocli option collisions (`-o` on `TracesCommand.InjectCommand`, `--format` on `ClusterScaffoldCommand`) fixed in the process.

4. **#4 EchoSlice probe** switched from synthetic `/health` intercept (always 200) to real `/api/echo/health` route. **#6 BootstrapModuleTest** stale `_seedDeferred` test replaced with `_seedEmitted` (post-grace-drop contract).

5. **Integration validation cycle:** run-2 (1p/6f, no improvement — stale CLI + missing membership-view upgrade); run-3 launched after all fixes (in progress at handover time).

---

## Quick state

```
branch:  release-1.0.0-rc1
HEAD:    120ed0fc1 docs(changelog): RC1 reachability aggregator + artifact retry + test-infra fixes
pushed:  yes (origin/release-1.0.0-rc1)
tag:     v1.0.0-rc1-candidate @ 120ed0fc1 (force-pushed)
working: clean
```

13 commits ahead of session start (`b73a6045b`).

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

**Run #2** (`/tmp/rc1-validation-run-2.log`, before today's fixes): `1p/6f`. Surfaced:
- CLI stale (blocked #3)
- Tier-1 not addressing the actual variance source (was working at wrong layer — `/api/status` consumer instead of `MembershipView`)
- #1 retry committed AFTER JAR push, not in deployed image
- #4 probing real `/api/echo/health` exposed slice-not-wired, which could be downstream of cluster B cascade

**Run #3** (`/tmp/rc1-validation-run-3.log`): launched with all fixes deployed (rebuilt CLI + rebuilt aether/node JAR including #1 retry + MembershipView snapshot upgrade). **In progress at handover time.** Suites: `00,02,03,05,09,12,13`.

Pre-existing baseline (per previous handover, before this session's fixes): cluster A 34p/2f, cluster B 5p/11f. Same suite subset surfaced 1p/6f in run-2 (no improvement because fixes weren't in the deployed code yet).

---

## Open issues at handover time

### Awaiting run-3 outcome to validate
- #2 + #5 (Kill_2_nodes per-reader variance + chaos cascade): the architectural fix shipped; needs validation
- #1 (09-artifacts HTTP 500): retry shipped; needs validation
- #3 (disable_auto_heal): CLI rebuilt + helper rewritten; needs validation
- #4 (EchoSlice probe): could pass once Kill_2_nodes-class variance is gone, OR could reveal a real slice-routing issue downstream of chaos
- #6 (BootstrapModuleTest): closed (unit test pass)

### Pre-existing, deferred
- Pong-loss replay (`integrations/cluster/.../ClusterSyncCollector.buildPong`) — TODO marker in spec; defer until measured
- `MembershipView.bootstrapAware` and `MembershipView.legacy` factories DON'T thread the snapshot — they predate it. Bootstrap path doesn't need the upgrade (cluster is forming, KV will be sparse anyway). If a future caller needs the snapshot from `bootstrapAware`, the constructor accepts it
- `ClusterPhaseView.MembershipViewReader` (AetherNode line ~1013) still uses the 3-arg legacy `strict` — keeps phase-determination free of snapshot dependency. Reconsider only if phase decisions need to be reader-invariant cluster-wide

---

## Critical gotchas — do NOT redo these mistakes

1. **The variance source is in `MembershipView.mapKvState`, not `/api/status` consumer code.** Original Tier-1 attempt fixed the wrong layer (transport-honest downgrade in `/api/status`) and produced zero observable improvement in chaos suites. The view itself was the variance source: it required local SWIM HEALTHY to confirm KV-ON_DUTY, downgrading to UNTRACKED otherwise. Snapshot becomes the SECOND confirmation source for ON_DUTY case ONLY — DO NOT add upgrade paths for other lifecycle states (the snapshot describes transport reachability, not membership state).

2. **Snapshot ≠ ON_DUTY indicator.** A peer can be REACHABLE in the snapshot while being JOINING / DRAINING / DECOMMISSIONED in KV. The aggregator ingests `PeerConnectivityObservation` + `PeerHealthObservation`, neither carries lifecycle. Using the snapshot as upgrade source for non-ON_DUTY lifecycle states would silently misreport membership.

3. **The DHT-resilience fast-fail is wrong for one-shot operations.** `ArtifactStore.deploy` is the only DHT caller without a retransmit cycle. If you add another similar caller, add a bounded retry on `PeerUnreachable` / `QuorumNotReached` (~3 attempts with backoff). Do NOT amend the DHT-resilience spec to make fast-fail per-cause-conditional — that breaks Rabia's design contract.

4. **The local `aether` CLI must be rebuilt + reinstalled after CLI source changes** (`aether/cli/target/aether.jar → ~/.aether/lib/aether.jar`). The integration test runner invokes the LOCAL `aether` command for cluster management. A stale CLI silently breaks helpers that depend on subcommands added in newer commits. Don't fall back to curl in helpers — the project rule is strict CLI for cluster management.

5. **Picocli option collisions throw at CLI startup.** Two fixed this session (`-o` on `TracesCommand.InjectCommand`, `--format` on `ClusterScaffoldCommand`). Adding a new `@Option` to ANY command, audit against `OutputOptions.format` (which claims `-o` / `--format` via mixin). Use `--template` or another flag name to disambiguate.

6. **`--skip-build` in `run-tests.sh` does NOT skip the JAR push.** It only skips the full `build.sh` invocation. The aether-node JAR is always pushed + the image rebuilt remotely (line ~463 `Pushing aether-node.jar to ${host} and rebuilding aether-node:local`). `--skip-image-push` is the flag for "reuse remote image as-is". For RC1 work, `--skip-build` is the right default — local focused builds via `mvn -pl aether/node install -am -DskipTests` are faster.

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
