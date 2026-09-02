# Session Handover — 2026-06-08 (membership machinery overhaul SHIPPED; #68 root NAILED → delete the generation-snapshot subsystem)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `1971bad36` · tree clean.
**Origin:** pushed through `236055429` (25-commit membership overhaul + tag `v1.0.0-rc1-candidate` moved). **`1971bad36` is 1 commit ahead, UNPUSHED** (the #68 TTL-parity + best-effort-routing fix — unit-validated 496/496, but its routing-NOT-DEAD change was not Docker-validated before the session pivoted; push after the next Docker run).

## TL;DR
The whole membership machinery overhaul is **done, validated, pushed**: #109 (transport is now an FSM-driven connection executor), #110 (gen-snapshot membership set → FSM), and the #68 TTL-parity + best-effort-routing fix. Then, chasing the last RC1 red (#68 post-multikill quiesce-180s), we **nailed the real root and it's bigger than a fix — it's a deletion**: the `ClusterGenerationSnapshot` persisted to KV via a **per-second Rabia consensus round** is the exact KV-persistence the membership rework was meant to remove (reintroduced by `2e0741fa9`, 2026-04-28 "KV-as-truth"). A full per-consumer audit confirms the snapshot is now **redundant** — every reader is re-sourceable from the per-node `MembershipFsm` + KV + the ping. **Next session: delete the subsystem** (plan below). That kills the per-second consensus write AND dissolves #68's root (quiescence becomes a live FSM computation, not a persisted flickering value).

---

## What's DONE + pushed this session

### Membership machinery overhaul (origin/release-1.0.0-rc1, through `236055429`)
| Item | Commits | What |
|---|---|---|
| **#109** transport connection authority (Wave D2) | `c70818b5e`(W1) `46528670b`(W2) `d5e727f92`(W3) `ed0847e77`(changelog) | FSM is now the authority that *drives* QUIC connections. W1: atomic `coreDialTarget` snapshot + `onConfirmedDeparture` DEAD-edge hook. W2: missing-peer reconciler reconciles against the FSM **desired-set** (NOT the static topology), gate-free; legacy topology path byte-identical when unwired. W3: AetherNode wires `setDesiredConnections` (NOT-DEAD `broadcastEligibleMembers`-eligible via `reachableMembers`) + co-confirmed-DEAD→`departurePermanent` + boot descriptor seed. **Docker 02-chaos 5p/1f = baseline**; self-drain S19 24s; JOINING-window replacement removed in **1s** (was ~14s zombie). Model = ADD-level + DEAD-event-REMOVE (no new cluster-to-zero pathway). |
| **#110** gen-snapshot membership set → FSM | `c3da1c067` `236055429`(changelog) | `presenceMemberSupplier` + `GenerationSnapshotPublisher` member source → `MembershipFsm.countedMembers()`. Docker 5p/1f = baseline. `PresenceSampler.currentMembers()` now test-observability-only. |
| **#68 TTL + routing** (committed, **UNPUSHED** `1971bad36`) | `1971bad36` | (a) restored TTL-parity on the SUSPECTED quiesce hint (`MembershipFsm.healthHints` decays a stale doubt after `autoHeal().swimHintsTtl()`=15s; clock+ttl injected; default `Long.MAX_VALUE` = no-decay keeps existing tests green). (b) **best-effort routing**: `reachableMembers` and DHT `livePeers` now filter on **NOT-DEAD** (`broadcastEligibleMembers`) instead of `countedMembers` — forward-routing/DHT no longer drop OBSERVED joiners / DEPARTING drainers (callers retry/timeout, so broader is strictly safer). 496/496 + 525/525 unit. **NOT Docker-validated** (session pivoted). |

Validation evidence: `/tmp/spike-109-02chaos.log`, `/tmp/spike-110-02chaos.log` (both 5p/1f). Live API probes in this session confirmed the #68 mechanism (below).

---

## ▶ THE MAIN DELIVERABLE: delete the generation-snapshot subsystem (`#114`)

### Why (root, evidence-backed)
1. **#68 mechanism, confirmed live.** `restore_cluster_baseline` does `await_generation_quiesced … "current"` which gates ONLY on `quiescence==QUIESCED` (epoch term trivially satisfied). `quiescence` flips DEGRADED if ANY counted member carries a SUSPECTED hint (`ClusterGenerationProjector.deriveClusterQuiescence:334`). Post-multikill, **never-READY ghost replacements** flap (QUIC connect/drop → SWIM SUSPECT) and **re-stamp** the SUSPECTED hint faster than the 15s TTL decays → quiescence sticks DEGRADED → 180s timeout. The TTL fix was *necessary but insufficient* (fresh doubt re-stamps).
2. **Live probes (this session, via `$AETHER_API_KEY` on a cluster-B node `:5162`):** with a healthy cluster, `await-quiesced(current+1)` returns **200 in 1s** (the check is sound). The `localCounter` advances **~1.2/sec even when fully idle** (1530→1537 over 6s) — confirmed a **content-blind per-second heartbeat**. `GenerationSnapshotPublisher.runApply` does `cluster.apply(Put(GenerationSnapshotKey,…))` — a **Rabia consensus round** — every 1s unconditionally (`AetherNode:1928` markDirty tick → `GenerationSnapshotPublisher:227` counter+1, no equality check). `/api/cluster/status` showed **3 ghost `UNKNOWN` never-READY replacements with no container** persisting.
3. **Archaeology:** the KV-as-truth snapshot was introduced by **`2e0741fa9` (2026-04-28) "feat(consensus): KV-as-truth generation snapshot subsystem (replaces HealthReconciler FSM)"** — i.e. it re-added the KV-persistence the membership rework set out to remove. The leader **already** broadcasts its view every second via the ClusterSync **ping** (ping phase) — the snapshot doesn't need consensus KV at all.
4. **Dead-end tried & reverted:** a content-aware "only-publish-on-change" patch (skip the Put when unchanged) **broke the `current+1` deploy barriers** — the per-second heartbeat is *load-bearing* for those barriers (a deploy settles at epoch N, then `await(current+1)` waits for a next epoch that never comes without the heartbeat). Confirmed live: blueprint deploy settled at `1:3`, `await(1:4)` timed out 241s while QUIESCED. **Lesson: keep the heartbeat; remove the *consensus*, not the tick.** This is also exactly why the user's framing is right: "epoch increasing every second over ping-pong is normal."

### Audit verdict (full per-consumer analysis): (B) DELETE-able except one synthetic artifact
Every snapshot field is re-sourceable; **CTM is already off the persisted snapshot** (it reads `PresenceGenerationSnapshotSource` → leader's own `MembershipFsm.countedMembers()`, not KV).

| Snapshot field | Re-source from |
|---|---|
| `coreMembers` (ids/addr/health) | `MembershipFsm` `memberDescriptors()`/`healthHints()`/`countedMembers()` |
| `quiescence`/`quiescenceDetail` | `ClusterGenerationProjector.deriveClusterQuiescence(...)` called **LIVE** over FSM `healthHints()` (it's a pure function; FSM is its declared input) |
| `partitions` / `communities` / `nodesWithoutSlices` | KV keys `DhtPartitionOwnershipKey` / `GovernorAnnouncementKey` / `NodeArtifactKey` (snapshot just *caches* them) |
| `desiredCoreSize` | `ClusterConfigKey.CURRENT` (KV) |
| `derivedMode` | `TopologyObserver.topologyMode()` (topology route already prefers this) |
| `epoch.term` | ping `epochTerm` (already carried) |
| `reason`, `committedAt` | **never read** (dead) |
| **`epoch.localCounter`** | **THE ONLY UNIQUE THING** — minted only by the publisher; ping's `epochCounter` is hard-wired `0L` at `AetherNode:1206` |

**The only thing the snapshot uniquely provides** is a **monotonic generation counter** that `await-quiesced(epoch≥target)` and NDM routing-epoch fencing gate on. **Re-mint it as a free per-second increment on the existing ClusterSyncPing `epochCounter`** (the ping is already sent every second; just bump a `long` on the leader). That gives the same heartbeat semantics the tests rely on, with **zero consensus and zero snapshot**. No consumer needs cluster-wide *agreement* (the snapshot is already read per-node from local KV, and CTM tolerates a per-node leader-broadcast FSM view) — they only need a *monotonic counter*.

### The staged plan (next session)
- **W1 — ping carries an incrementing `epochCounter`.** Change `leaderEpochSupplier` (`AetherNode:1206`, currently `Epoch.epoch(leaderTerm.get(), 0L)`) so the leader bumps a per-ping monotonic counter (reset/continue per term — term dominates `isAtLeast` ordering, so a reset on leader-change is safe). The ping already carries `epochTerm`/`epochCounter`; followers already track it via `ClusterSyncCollector.advanceObservedEpoch` (`:386,634`). This is the new local generation token.
- **W2 — `await-quiesced` + `/api/cluster/generation` off the snapshot.** `ClusterAwaitQuiescedRoute.matchesQuiesced` (`:179-181`): epoch from the local observed-ping-epoch; `quiescence` computed **live** from local FSM `healthHints()` via `deriveClusterQuiescence` (kills the #68 persisted-flicker — the verdict is computed fresh, never stored). `ClusterGenerationRoutes` (`:67-140`): assemble the response on demand from FSM (`memberDescriptors`+`healthHints`+`countedMembers`) + KV (partitions/governors/config/artifacts) + live quiescence. **Consistency rule (the one real risk):** the generation route, the CLI, and the await-gate must ALL source the epoch from the same ping-counter, or "reach generation N" semantics break.
- **W3 — CDM + BootstrapModule off the snapshot.** `ClusterDeploymentState.activeNodes()`/`activeCommunityIds()` (`:632-654`) → FSM `countedMembers()` (minus passive) + `GovernorAnnouncementKey` keyset. `BootstrapModule` (`:419-455`) → make its existing `projectFromCommittedAtoms` fallback the sole path; drop `readPublishedSnapshot`. NDM (`:372-373`) epoch → local ping-epoch.
- **W4 — DELETE.** `GenerationSnapshotPublisher` (+ the 1s `markDirty` tick `AetherNode:1928` + the dirty-wiring fan `:1955-1981`), `KvBackedGenerationSnapshotSource`, `GenerationSnapshotKey`/`GenerationSnapshotValue`, the `currentGenerationSnapshot()`/`snapshotSupplier` plumbing (`AetherNode:746-748,1209-1211`), `ClusterGenerationSnapshot` (the record — confirm no residual readers). **KEEP** `ClusterGenerationProjector.deriveClusterQuiescence` (now invoked live) and `PresenceGenerationSnapshotSource` if still feeding CTM's `currentMembershipView` (verify).
- **W5 — Docker-validate.** Formation, leader-kill re-election, the `current+1` deploy barriers (06-deployment), and the #68 `restore_cluster_baseline` post-multikill quiesce. Expect: per-second consensus round gone (watch the Rabia log/metrics), #68 restore quiesces (live FSM quiescence, no persisted flicker), deploy barriers still pass (ping heartbeat intact). Then push (`1971bad36` + the delete commits).

### Risk register
- **Counter consistency (the whole risk):** every `await-quiesced` target must come from the same ping-counter the gate reads. Audit the harness `await_generation_quiesced` usage (`generation.sh`; restore uses `"current"`, deploys use `"current+1"`) and the CLI `cluster await-quiesced`.
- **Cold-start fallbacks:** removing the persisted snapshot removes one fallback rung in CTM (`currentMembershipView()==none`) and BootstrapModule (`readPublishedSnapshot().or(projectFromCommittedAtoms)`). Ensure the FSM/committed-atom path covers the very-early boot window (before the FSM quorum latch flips).
- **Live quiescence per-node:** computed on the serving node from its FSM. For await-quiesced this is fine (1s cadence, leader's view via the leader-served route). Confirm community-degraded inputs (non-core-only) are available locally.

### Full file list (audit-cited, all absolute under repo root)
`aether/slice/.../generation/ClusterGenerationSnapshot.java` · `aether/aether-deployment/.../generation/{GenerationSnapshotPublisher,KvBackedGenerationSnapshotSource,PresenceGenerationSnapshotSource,ClusterGenerationProjector,BootstrapModule}.java` · `aether/aether-deployment/.../membership/fsm/MembershipFsm.java` · `aether/aether-deployment/.../cluster/ClusterTopologyManagerRecord.java` · `aether/aether-deployment/.../cluster/fsm/ClusterDeploymentState.java` · `aether/aether-deployment/.../node/NodeDeploymentManager.java` · `aether/node/.../AetherNode.java` (snapshot supplier `:1209`, source wiring `:406-445`, epoch supplier `:1206/1224`, publisher wiring `:1903-1981`) · `aether/node/.../api/routes/{ClusterGenerationRoutes,ClusterAwaitQuiescedRoute,ClusterTopologyRoutes}.java` · `integrations/cluster/.../metrics/ClusterSyncMessage.java` · `aether/aether-metrics/.../ClusterSyncCollector.java` · `integrations/consensus/.../topology/GenerationSnapshotSource.java`

---

## Current state
- HEAD `1971bad36`, tree clean. Inert "move-to-ping" W1 plumbing was **reverted** (the decision is delete, not move).
- Cluster A + B **torn down + cleaned** (`docker rm -f`, networks removed, `aether_pgdata` removed). Next run clean-slates anyway.
- `$AETHER_API_KEY` is set in the env (used for live `/api/cluster/generation` + `/api/cluster/await-quiesced` probes). Node mgmt ports map host `docker port <name> 8080/tcp`; original nodes 5161/5163/5165/5167/5169, replacements get random high ports (resolve via `docker ps`).

## Remaining RC1 issues (after the delete lands)
- **#94** NODE_FAILED-within-60s under load (02-chaos `kill-under-load` 3p/1f) — SEPARATE root (SWIM-under-load detection latency); present in baseline, unchanged by this session.
- **Ghost UNKNOWN never-READY replacements** (over-provisioning churn that creates container-less phantom members) — surfaced during #68 probing; likely #94-adjacent / a CTM provisioning issue. Worth its own investigation (a `JoinGraceExpiredNeverHealthy` joiner should evict to DEAD, not linger).
- Pre-existing deferred: #91 DHT durability, #93 drain-budget 500/409, #95 05-security secure-mode, #97 budget-stress suite, 03-scale-down, 13-edge — per `v1-roadmap.md`.

## Standing directives (unchanged)
HCLOUD-safe builds (`env -u HCLOUD_TOKEN`, never `mvn verify`/`./build.sh` with token set; `build-runner` owns maven). Single-line commits, no trailers. Commit on `release-1.0.0-rc1`; push only when asked. Docker: `docker rm -f aether-*` + network rm + `pgrep run-tests.sh` before runs; capture logs before teardown; `ssh -n` in loops. **Instrument before fixing** (the TTL miss cost a Docker cycle — the live API probes nailed it). **Verify subagent claims** (the audit's strong chain still needs the W5 Docker proof).
