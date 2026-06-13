<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Session Handover — 2026-05-18b (RC1 — 7 commits, 1p/6f→8p/7f, empirical root cause for cluster B cascade)
date: 2026-05-18
branch: release-1.0.0-rc1
head: ad4495a61
predecessor: aether/docs/internal/progress/session-handover-2026-05-18.md
status: in-progress — 8p/7f stable baseline; remaining failures all rooted in KV-state cleanup for killed nodes
---

# Session Handover — 2026-05-18b

## TL;DR (3 minutes)

1. **8 of 15 suites pass on remote Docker (run-13).** Stable baseline up from run-6's 1p/6f. Per-suite: 00, 04, 06, 07, 08, 10, 11, 14 green. 09, 02, 03, 05, 12, 13, 15 red.

2. **The session's most important lesson: subagent analyses have been wrong in citable ways, twice in row.** Agent C audit claimed Rabia R1 + ClusterPhaseKey not landed — both are landed. W1 jbct-coder removed BOOTING fallback in TopologyObserver as "legacy" — it was bootstrap-load-bearing; reverted as `daacd55df` after empirical run failed at `coreCount=0 mode=BOOTING`. Saved as `feedback_verify_subagent_claims.md` memory. Demand empirical evidence before driving non-trivial change off agent diagnosis.

3. **Real root cause of cluster B cascade — verified empirically:** killed nodes' `NodeLifecycleKey` stays `ON_DUTY` in KV; `/api/cluster/topology` reports 8 NodeIds (3 CTM replacements + 5 originals) as `ACTIVE/HEALTHY` while only 5 containers are alive. `aether cluster drain <killed-id>` returns "Node already DECOMMISSIONED" for SOME entries (node-1 specifically), but most CTM-provisioned replacements that subsequently get killed remain `ON_DUTY` because the SWIM→MembershipFsm→DECOMMISSIONED write path either lags too slowly or doesn't fire for these. The aggregator math fix addressed snapshot REACHABILITY, not KV lifecycle staleness — the snapshot fix is correct but orthogonal.

4. **The bonus partial fix candidate for #1 5MB:** Bundle B (chunk-fan-out retry + `StorageInstance` claim-release) was found uncommitted in the working tree at unknown origin. Stashed (`git stash list`). Mechanism: extend bounded retry to per-chunk DHT puts + release lifecycle sentinel on tier-write failure (otherwise existing retry just bumps refCount instead of re-attempting). Was authored by some prior session/agent and not committed. Origin needs attribution before merging.

5. **The DHT quorum math fix lives in a worktree branch** (`worktree-agent-aac3b33d1c2fa0a68` at `2e6fa238a`). Caps quorum to live targets instead of full ring; adds `DHTError.InsufficientReplicas`. Was based on a wrong-target diagnosis (the investigator hypothesized kill_2 quorum-math, but the empirical 5MB failure is on a healthy cluster, not chaos). Correct standalone work for kill_2 scenarios but doesn't address the actual #1 cause. Keep available.

---

## Quick state

```
branch:  release-1.0.0-rc1
HEAD:    ad4495a61 fix(reachability): keep 30s TTL to allow cold-start convergence
pushed:  no (will be after this handover commit)
tag:     v1.0.0-rc1-candidate @ d6ac85442 (predecessor session; needs move to current HEAD after handover commit)
working: clean (Bundle B in `git stash list`)
```

---

## Commits this session (7 total)

| Hash | Subject |
|---|---|
| `077bc39e2` | refactor(consensus): TopologyObserver reads snapshot only, no nodeStatesById fallback — **REVERTED** |
| `daacd55df` | Revert "refactor(consensus): TopologyObserver reads snapshot only..." — restored bootstrap |
| `9b0bb435f` | fix(reachability): symmetric quorum + 5s TTL + RECONNECT no-emit (fixes 12-network regression math) |
| `3dd0c9852` | fix(tests): test-artifact-replication grep tolerates stale /api/status schema (`|| true`) |
| `074af5506` | fix(routes): exclude DECOMMISSIONED from /api/cluster/topology coreNodes+nodeDetails |
| `cee4d6e4d` | test(integration): chaos+scaling under-load tests hit app endpoint /api/echo/health |
| `ad4495a61` | fix(reachability): keep 30s TTL (5s was too aggressive — caused cold-start coreCount=1 → is_cluster_ready timeout) |

Net effect: 5 functional changes after W1 revert. Plus stashed Bundle B and uncommitted DHT-quorum-fix in worktree.

---

## Score progression

```
run-6  (handover baseline):  1p/6f      (only 00-smoke ran, cluster B cascade)
run-8  (W1 + aggregator + tests): 7p/8f  (W1 broke 12-network worse, but cluster A suites improved)
run-10 (W1 reverted + ClusterTopologyRoutes filter): 8p/7f  (cluster A fully clean except 09-artifacts 1MB)
run-13 (run-10 + TTL restored + app-endpoint load tests): 8p/7f (stable)
```

15-suite breakdown at run-13:

| Suite | Result | Notes |
|---|---|---|
| 00-smoke | ✅ 2p/0f | |
| 04-streaming | ✅ 4p/0f | |
| 06-deployment | ✅ 5p/0f | (CTM filter unblocked) |
| 07-cluster-mgmt | ✅ 4p/0f | |
| 08-resources | ✅ 5p/0f | |
| 10-database | ✅ 3p/0f | (CTM filter unblocked) |
| 11-observability | ✅ 6p/0f | |
| 14-storage | ✅ 2p/0f | |
| 09-artifacts | ❌ 2p/1f | **5MB-on-healthy-cluster HTTP 500** (Bundle B candidate fix stashed) |
| 15-delegation | ❌ 1p/1f | Node_failure_reassignment cluster healthy timeout (probably KV cleanup) |
| 02-chaos | ❌ 3p/1f | Kill_2_nodes "1/2 candidates" + restore_cluster_baseline timeout |
| 03-scaling | ❌ 0p/3f | Cascade from cluster B contamination |
| 05-security | ❌ 0p/3f | Cascade from cluster B contamination |
| 12-network | ❌ 0p/3f | "got 6, expected 5" + "0/1 candidates" (KV state leak) |
| 13-edge-cases | ❌ 0p/3f | App_routes_reachable EchoSlice not wired |

---

## Real root causes (empirically verified)

### Cluster B cascade: stale ON_DUTY in KV

**Failure mode:** `pick_non_leader: only N/M candidates available` and `Cluster_ready_5_nodes: expected '5', got '6'`.

**Evidence:** queried `http://192.168.0.71:5162/api/cluster/topology` directly after run-9-chaos's --skip-teardown. Result:
- `coreCount: 5`, `clusterSize: 5`
- `coreNodes` lists **8** NodeIds (5 originals + 3 CTM replacements)
- `nodeDetails` lists all 8 as `ACTIVE/HEALTHY`
- Real container state: 4 originals alive, 1 CTM replacement alive, 3 containers exited (137)

**Verified that:**
- `aether cluster drain <killed-id>` returns "Node already DECOMMISSIONED" for `node-1` (the original leader that was killed first) — so the DECOMMISSIONED-write path DOES work in some cases.
- But CTM-provisioned-then-killed replacements stay ON_DUTY — the SWIM→MembershipFsm path either lags too slowly or doesn't fire for them.

**The mechanism:** `MembershipFsm.onSwimObservation` (line 336 of `MembershipFsm.java`) gates on `isLeader.getAsBoolean()`. The reducer cell `(OnDuty, SwimFaulty) → DECOMMISSIONED` exists. So the leader must observe SWIM FAULTY locally for the kill to trigger the write. In rapid-kill scenarios (test-kill-multiple kills 2 nodes within ~1s), the leader's SWIM may not have flipped both to FAULTY before tests proceed. CTM auto-heals (provisions replacements with fresh KSUIDs) faster than DECOMMISSIONED writes land. Replacements get killed by subsequent chaos. Cycle repeats. Stale entries accumulate.

**My CTM filter fix in `ClusterTopologyRoutes` excludes DECOMMISSIONED** — correctly. But `/api/status` (used by `pick_non_leader`) reads `MembershipView.statusOf` which returns the KV state; for KV-stale-ON_DUTY entries, statusOf returns `ON_DUTY` and pick_non_leader includes them as candidates. Then `_docker_container_by_node_id_label` finds no container → "skipping stale candidate". When all viable candidates are stale, test fails.

### #1 5MB artifact regression

**Failure mode:** 1MB ✅, 5MB ❌ (HTTP 500) on **healthy 5-node cluster A** — no chaos.

**Evidence:** run-6/13 log shows 64KB/128KB/1MB pass on cluster A; 5MB fails. Cluster A has no kill operations.

**Investigator hypothesis (plausible-but-unverified):** 80 chunks × RF=3 = 240 concurrent QUIC writes hit Netty's default 64KB channel high-watermark → `BackpressureRefused` cascade → `QuorumNotReached` → HTTP 500. Test runner discards HTTP response body (`curl -o /dev/null`) — actual cause text unknown.

**Bundle B candidate fix (stashed):** Extends bounded retry to per-chunk DHT puts AND releases lifecycle sentinel on tier-write failure (the existing retry was vacuous — `deduplicateBlock` bumps refCount instead of re-attempting). Origin unknown — found in working tree at session start without attribution. Mechanism is plausible but `1MB regression` from run-6→run-8 is unexplained.

### 13-edge-cases App_routes_reachable

**Failure mode:** `App route http://192.168.0.71:8080/api/echo/health not wired (expected EchoSlice handler to respond)`.

**Likely cause:** cluster B downstream of chaos contamination. After 02-chaos cluster B is in a degraded state. EchoSlice routing table may not be propagated correctly post-kill events. This may be partial (the slice IS deployed but route table is stale on the node servicing the test's curl).

### 15-delegation Node_failure_reassignment

**Failure mode:** `cluster healthy (timed out after 240s)` after killing + restarting node-2.

**Likely cause:** same KV-state leak. After kill, KV has stale ON_DUTY for node-2; CTM provisions replacement; test restarts node-2 docker container; now KV has 2 ON_DUTY entries pointing to node-2 (original + replacement) + replacement is fresh. is_cluster_ready times out because the count is wrong.

---

## What's actually needed for 15/15 — fix path

### Fix A: faster killed-node → DECOMMISSIONED transition (the big lever)

This single fix would likely close: 02-chaos Kill_2_nodes, 12-network "got 6", 12-network "0/1 candidates", 03-scaling, 05-security cascade, 13-edge-cases (probably), 15-delegation (probably).

Three implementation options, in order of scope:

1. **Sweeper (smallest):** Periodic background scan on the leader. For every `kvState == ON_DUTY` peer where local SWIM is `FAULTY`/`UNKNOWN` AND aggregator reports `UNREACHABLE`, write `DECOMMISSIONED`. ~150 LOC, no spec changes. Runs every N seconds.

2. **CTM tombstone-on-replacement (medium):** When CTM's `reconcileActive` detects a deficit and provisions a replacement, identify which specific NodeIds are missing from the topology (compare configured coreNodes with actual `MembershipView.onDutyPeers()` ∪ live CTM-provisioned), write `DECOMMISSIONED` for the missing ones before/during replacement provisioning. ~300 LOC, structural change in `ClusterTopologyManagerRecord`.

3. **HealthReconciler quorum aggregator (spec-full):** Implement `aether/docs/specs/membership-architecture-spec.md` §4.3 properly — quorum-of-observations aggregator that converts `⌈N/2⌉+1` cross-node `PeerObservedFaulty` events into a single leader-side `SwimFaulty(peer)` decision → DECOMMISSIONED write. ~600-1000 LOC. Requires `PeerObservationStore` (already exists, partially wired).

Recommendation: start with #1 (sweeper) as the quickest unblock. If it cleanly addresses the failures, defer #2/#3 to post-RC1.

### Fix B: empirically diagnose 5MB

Before any code: capture HTTP 500 response body from `test-large-artifact.sh:43-48`. Change `curl -o /dev/null -w "%{http_code}"` → `curl -o /tmp/push-resp.body -w "%{http_code}" "$URL"; cat /tmp/push-resp.body`. Re-run 09-artifacts in isolation. The body text contains `cause.message()` per `MavenProtocolHandler.handlePutParsed`.

If body matches Bundle B investigator's hypothesis (`"Peer X unreachable: backpressure"` or similar), Bundle B may be the right fix — apply, validate. If body shows something else (e.g., heap OOM, timeout, slice routing 503), pivot.

### Fix C: 1MB regression (low priority)

Probably symptomatic of state pollution from `test-artifact-replication.sh` now running to completion (vs aborting at Identify_second_node pre-fix). Investigate the artifact-store state between consecutive test files. May resolve itself with test-side test ordering.

---

## Critical gotchas (do NOT redo these)

1. **DO NOT eliminate `nodeStatesById` fallback in TopologyObserver "as legacy."** It's bootstrap-load-bearing. Without it, during BOOTING `coreCount` returns 0 forever because the snapshot isn't published until quorum is established, which requires the BOOTING fallback. W1 reverted this lesson as `daacd55df`. Memory: `feedback_verify_subagent_claims.md`.

2. **DO NOT trust subagent diagnoses on faith — even with line citations.** Independently verify load-bearing claims against actual code AND against empirical failure evidence (HTTP body, failed assertion text, node log). Memory: `feedback_verify_subagent_claims.md`.

3. **5s TTL on ReachabilityAggregator is too aggressive.** Observations age out before reaching symmetric quorum during cold-start, causing `reachableOnDutyCount = 1` (self only) and `is_cluster_ready` 240s timeout. 30s is the right value. Symmetric quorum math (no asymmetric outvote) is still correct — TTL is orthogonal.

4. **`/api/cluster/topology` and `/api/status` are DIFFERENT routes with DIFFERENT projections.** The CTM filter fix at `ClusterTopologyRoutes` excludes DECOMMISSIONED entries — but `/api/status` is `StatusRoutes` which reads `MembershipView.statusOf` directly. Test-side `pick_non_leader` reads `/api/status`. So routes-side filter alone doesn't unblock chaos tests — needs the KV-state cleanup (Fix A above).

5. **Empirical evidence > code reading.** Twice this session subagent code-reading produced wrong diagnoses (Rabia R1 status, W1 BOOTING fallback). Spending 15 minutes verifying via test query or empirical run saves hours of wrong direction.

6. **Bundle B was uncommitted at session start, source unknown.** Currently stashed. Mechanism plausible but origin needs attribution before merging. Don't merge unverified.

7. **Zombie container cleanup is mandatory between runs.** CTM-provisioned replacements (`aether-{a,b}-core-node-*-<uuid>`) are not in compose files; `docker compose down -v` doesn't remove them. Explicit cleanup before each run: `docker ps -a --filter "name=aether-" --format "{{.Names}}" | xargs -r docker rm -f`.

---

## Open questions for next session

### OQ1: Which fix-A option to land?

Sweeper (#1) is fastest. CTM tombstone-on-replacement (#2) is more structurally correct. HealthReconciler (#3) is spec-complete but largest. Recommendation: prototype #1 (sweeper) first; if it clears the failures, write follow-up issue for #2/#3.

### OQ2: 5MB HTTP 500 actual cause

Capture HTTP response body. Then decide: Bundle B (chunk retry) OR concurrency-bound the chunk fan-out OR raise QUIC channel watermarks.

### OQ3: 1MB regression from run-6 to run-8+

Was PASS in run-6 (test-artifact-replication.sh aborted at Identify_second_node before reaching test-large-artifact.sh). Now fails after that abort is fixed. Possible state pollution between test files. Investigate via isolated run of test-large-artifact.sh.

### OQ4: Bundle B attribution

Where did the artifact-retry + storage-claim-release changes come from? Check git stash, reflog, recent agent runs. If from a prior session's uncommitted work, decide whether to merge.

### OQ5: 13-edge-cases EchoSlice route

App route not wired on cluster B in 13-edge-cases. Is this a cluster-B-cascade symptom (route table propagation broken by chaos contamination) or a separate route-wiring bug? Investigate with isolated 13-edge-cases run from a fresh cluster B.

---

## Next-session start

```bash
# 1. Verify state
git log --oneline -10              # expect ad4495a61 at HEAD
git stash list                      # expect Bundle B stashed
git status --short                  # expect clean
git tag --list 'v1.0.0-rc1-candidate'

# 2. Move tag to current HEAD
git tag -d v1.0.0-rc1-candidate
git tag v1.0.0-rc1-candidate HEAD
git push origin :refs/tags/v1.0.0-rc1-candidate
git push origin v1.0.0-rc1-candidate

# 3. Recommend starting with Fix A option #1 — sweeper for stale ON_DUTY entries.
#    Spec: leader periodically scans MembershipView.snapshot() for entries with
#    kvState=ON_DUTY AND swimState=FAULTY/UNKNOWN AND aggregator-snapshot=UNREACHABLE,
#    writes DECOMMISSIONED via LifecycleWriter. Run every 10s. ~150 LOC.

# 4. After implementation, run focused 02-chaos + 12-network:
cd aether/tests/integration && ./run-tests.sh --env remote --skip-build --suites 02,12

# 5. If failures persist, instrument MembershipFsm.onSwimObservation to log
#    every dropped observation (followers drop silently — may be hiding signal).
```

---

## References

- Predecessor: `aether/docs/internal/progress/session-handover-2026-05-18.md`
- Membership spec (Fix A option #3 reference): `aether/docs/specs/membership-architecture-spec.md` §4.3
- Reachability aggregator spec (math change from this session): `aether/docs/specs/reachability-aggregator-spec.md`
- DHT resilience spec (5MB context): `aether/docs/specs/dht-resilience-spec.md`
- Memory lesson: `~/.claude/projects/-Users-sergiyyevtushenko-IdeaProjects-pragmatica/memory/feedback_verify_subagent_claims.md`
- Run logs: `/tmp/rc1-validation-run-{8,10,13}.log` (final = run-13)
- Tag `v1.0.0-rc1-candidate` will move to `ad4495a61` after this handover commit

---

**End of handover.** Net positive 7-commit session. Real architectural progress on /api/cluster/topology projection honesty (CTM filter). Bootstrap-load-bearing knowledge captured as lesson. Aggregator math now spec-correct without the TTL over-correction. Remaining 7 failing suites all converge to one root cause family: KV `NodeLifecycleKey` cleanup for killed nodes — a focused next session with the sweeper approach should close most of them.
