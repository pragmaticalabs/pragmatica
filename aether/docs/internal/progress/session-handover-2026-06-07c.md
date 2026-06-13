# Session Handover — 2026-06-07c (membership-FSM unification MERGED; next: #109 → #110 → #68)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `63007b648` · **18 commits ahead of origin — UNPUSHED** (user said "merge," not "push"; ask before pushing). Tree clean. The `spike/membership-fsm-unification` branch was fast-forward-merged and deleted.

## TL;DR
The **membership-FSM unification** is complete, validated, and merged to rc1. The FSM is now the **single per-node membership authority** every consumer reads; consensus broadcasts to FSM membership (`broadcastEligibleMembers()`) instead of the transport's `peers.values()` cache — **the #68 consensus dead-ULID retry-storm root is FIXED** (Docker-validated: give-up count 0, no perpetual loop). `NodeTopologyTracker` → `PresenceSampler`. Three deliberately-deferred items remain, to be done **one by one next session in this order: #109 (D2 transport executor) → #110 (gen-snapshot membership) → #68 (post-auto-heal quiesce root)**.

## Spec & design (READ FIRST)
- **`aether/docs/specs/membership-fsm-unification-spec.md`** — the complete design. The 4 settled decisions: (1) full-visibility track-all / connect-bounded; (2) self-described SWIM descriptor `(NodeId, incarnation, address, role, source)`; (3) **Option-A transport = dumb executor, level-triggered** (FSM publishes desired connection-set, executor reconciles); (4) single per-node authority, only scaling leader-gated, NTT→PresenceSampler. The 2-state-machine seam (§3), executor interface (§4), descriptor wiring (§5), consumer migration (§6) are the blueprint for #109/#110.
- Background: `membership-convergence-fsm.md` (the FSM model), `membership-architecture-v2-spec.md` (derive-from-reality).

## What's DONE + merged (this session)
Earlier (already on rc1, pushed): **Phase-2 cutover** — `LeaderReconciler` counts `MembershipFsm.countedMembers()` (commits `43f79598e`..`282d4f896`). Fixed the over-provisioning churn.

This session (the unification spike, now merged, commits `bb27864ed`..`63007b648`):
| Wave | What | Key commits |
|---|---|---|
| A | Self-described `source`/`role` SWIM labels (`AETHER_SOURCE`/`AETHER_ROLE`→`ClusterIdentityEnv`; Announce→`SwimMember.labels`→`dialInfoFor`). **`@Codec` is positional/no-framing → CANNOT append fields to `MembershipUpdate`; rode Announce labels instead.** Transitive-gossip-only peers lack role/source (acceptable for all-core; #241 follow-up). | `bb27864ed`,`b3f0c3528` |
| B | `MembershipFsm` **always-on per node** (deleted `active`/`activate`/`deactivate`/`members.clear`; `seed(Set)` at boot; only `LeaderReconciler` leader-gated). | `d1745e5ce`,`5ee6087c2` |
| C | FSM stores per-member `MemberDescriptor(address,role,source)` (retained through DEAD); projections `desiredConnections()`/`coreMembers()`/`reachableMembers()`/`broadcastEligibleMembers()`/`memberDescriptor()`/`memberDescriptors()`/`healthHints()`. `PeerTarget` record. | `f7f6f8e01`,`761591e59` |
| D1 | **Storm fix:** `QuicClusterNetwork.broadcastPayload` filters by FSM `broadcastEligibleMembers()` (not-DEAD set) via injected `setBroadcastMembership` supplier. NOT the full executor — minimal broadcast filter. | `91243e1b7`,`010b7fa63` |
| E | Consumers → FSM: `QuorumLossDetector` (countedMembers.size, NOT strict MEMBER-only), forward-routing (`reachableMembers`), DHT liveness (`countedMembers∪self`), quiesce-**health** (`healthHints()`, equivalence-tested). | `955638817`,`94eebc968` |
| F | `NodeTopologyTracker` → **`PresenceSampler`** (16 files); removed dead `keepOnlyAccessible`. Package kept `...membership.ntt`. | `0d5a0b3e7`,`264106298` |
| review | JBCT review fixes: synchronize `MemberTracking` reads; `Option` not null-sentinel in broadcast; `PeerTarget` factory; `HashSet` for broadcast set; role constant; "NTT"→"PresenceSampler" log strings; AssertJ test idioms. | `2152dcf6b`,`481fe051d`,`7d820255f`,`bd3790775` |
| docs | spec + changelog + feature-catalog. | `2ad5e30ee`,`63007b648` |

**Validation (all green):** full unit suite **5904 tests / 66 modules — no new regressions** (only 4 known pre-existing flakes: 3 swim awaitility + `MavenProtocolHandlerTest.handlePut_accepts_pom_file`). Docker 02-chaos A–D1 and A–F both **5p/1f = baseline**, storm fixed (give-up=0, byte-identical image gate), no formation/self-drain/DHT/quiesce regression. Evidence: `/tmp/spike-d1-probe/`, `/tmp/spike-af-probe/`, `/tmp/spike-af-02chaos.log`. **Live cluster B (5 nodes) was left UP on `$TARGET_HOST`** from the last run (clean-slate before reuse).

---

## ▶ NEXT, IN ORDER

### #109 — Wave D2: full level-triggered transport executor (HIGHEST RISK)
**Goal:** make the FSM the authority that *drives connections*, not just what's broadcast to. The FSM already publishes `desiredConnections() : Set<PeerTarget(NodeId, NodeAddress)>` (Wave C). D2 = **consume it** + **rip the membership-decision logic out of `QuicClusterNetwork`**, leaving a dumb executor.

- **Consume:** the executor continuously reconciles actual connections → `desiredConnections()` (dial missing via single-dialer, drop extras). Level-triggered (no edge commands).
- **DELETE from `QuicClusterNetwork`** (membership decisions move to FSM): `considerPeerForReconcile`/`reconcileMissingPeersTick` *membership judgment*, `swimMembershipAllows`/`swimHealthAllows` gates, `departurePermanent` *independent* REMOVED decision, the readmit block in `onPeerConnected`. Anti-resurrection/incarnation fencing → FSM `rejoinIfNewer` (already present).
- **KEEP (mechanical):** dial/bind, Hello handshake, per-lane streams, write + backpressure retry, offline-buffer (EVICTED transient-tolerance), reconnect-backoff, and **single-dialer `ConnectionDirection.shouldInitiate(self,peer) = self.compareTo(peer)<0`** (+ the 60s dual-dial grace) — this is LOAD-BEARING.
- **Scoping already done** — the full PeerState phase map (INIT/CONNECTING/CONNECTED/EVICTED/REMOVED), MECHANICAL-vs-DECISION table, executor interface, and deletion list are in this session's transcript (the "Scope transport executor (Option A)" Explore report). Methods by name: `connectPeer` (dial, `peer.resolvedAddress()`), `disconnect(DisconnectNode)` (soft-evict + protection window `helloTimeout×3`), `departurePermanent` (REMOVED), `evictStaleConnection`, `onPeerConnected` (ADD/RECONNECT feedback). Line numbers shifted after D1 — search by method name.
- **REQUIRED fix folded in (deferred from JBCT review):** `desiredConnections`/`coreMembers` currently take **two separate per-member monitor acquisitions** (`isCoreCountedMember` + `address`) → a `PeerTarget` can pair an is-core decision with a newer descriptor. Add one `synchronized MemberSnapshot snapshot()` on `MemberTracking` capturing `(state, descriptor)` atomically and build the projection from it. Harmless today (desiredConnections unconsumed); **mandatory once D2 acts on it.**
- **⚠️ CLUSTER-TO-ZERO HISTORY:** this is the exact `writeToStream`/`isActive()` territory that once drove the cluster to 0 nodes (memory + 2026-06-03 handovers). Guardrail: a peer leaves the desired set **only on co-confirmed DEAD** (SUSPECT/OBSERVED keep it). Preserve single-dialer. **Build incrementally + Docker-validate exhaustively** (formation, re-election, kill-under-load, self-drain). Value = structural purity (eliminates the transport as a 3rd authority + the ~11-14s zombie-connection lingering until SWIM FAULTY); **NO new bug fix** (D1 already defused the storm) — so it's optional-but-clean.

### #110 — migrate generation-snapshot membership source PresenceSampler→FSM
Wave E migrated the generation path's **health** source to the FSM but left its **membership SET** on `PresenceSampler`. Three live callers of `currentMembers()`/`currentMemberCount()`:
- `PresenceGenerationSnapshotSource` — `AetherNode` `presenceMemberSupplier` (~:423), the **BOOTING→NORMAL latch**.
- `GenerationSnapshotPublisher` — `AetherNode` ~:1871 `presenceSampler::currentMembers`.
- `propagateMemberCount` boot fallback — `AetherNode` ~:538.

Migrate to `membershipFsm.countedMembers()`. **BEHAVIOR-AFFECTING** (presence-set vs FSM-counted semantics differ; BOOTING→NORMAL latch *timing* shifts). Do it with an equivalence check like Wave E's. Once done, `PresenceSampler.currentMembers()`/`currentMemberCount()` are dead → remove them (the **last** membership-authority read on PresenceSampler), then the `...membership.ntt` package can be tidied → `...membership.presence` (also moves `LeaderReconciler`/`QuorumLossDetector` — bigger, optional). `peakMembershipCount()` (reconciler cold-start latch) stays.

### #68 — post-auto-heal quiesce root (the actual remaining RED)
The unification did NOT fix this — it's a **separate root**. `restore_cluster_baseline: generation did not quiesce within 180s` fails 3/3 **only post-auto-heal** (fresh formation quiesces in 1000ms every time). Root = **CTM/replacement churn** (membership cycling 5→4→5 as replacements take time to READY) + **leader mgmt API `rc=7`** (connection-refused during restore) + **SWIM SUSPECT→FAULTY latency** (~11-14s, also causes the bounded per-kill backpressure bursts).
- **NEW LEAD (from the JBCT review of this work):** `MembershipFsm.healthHints()` has **no TTL expiry**, whereas the legacy `SwimHintsRegistry.currentTtlFiltered()` DID — so a long-SUSPECT member keeps the quiesce projector DEGRADED *longer* under the FSM. The projector reads FSM health-hints (Wave E) but PresenceSampler membership SET (until #110), so a DEAD member's retained FAULTY hint is dead-code, but an **in-set SUSPECT member with no TTL** is a real candidate contributor. **Try: an I4 suspect-budget timeout** (SUSPECT→DEPARTING after a bound) in the FSM, or a TTL on the health-hint. **Note:** do #110 first so the member SET is also FSM-sourced — then the quiesce path is fully FSM and the investigation is clean.
- The quiesce equivalence test (`MembershipFsmQuiescenceEquivalenceTest`) is a **shape-regression guard, NOT a legacy-path proof** (TTL divergence intentionally untested) — strengthen it if you touch this.
- Validate against **SUSPECT/refute-count → 0 + `ClusterQuiescence.QUIESCED`**, not artifact/connection counts. Evidence: `/tmp/spike-af-probe/`.

---

## Standing directives / gotchas (unchanged, apply to all)
- **Builds HCLOUD-safe:** `env -u HCLOUD_TOKEN`; NEVER `mvn verify`/`./build.sh` with HCLOUD_TOKEN set (creates a real paid Hetzner server); `build-runner` owns maven.
- **Commits:** single-line, no body/trailers/Co-Authored-By. Commit directly on `release-1.0.0-rc1` (the spike was a sanctioned exception). **Push only when asked.**
- **Docker infra:** ALWAYS `docker rm -f aether-*` + `docker network rm aether-{a,b}-network` + `pgrep run-tests.sh` before a run. **Capture `docker logs` to disk BEFORE teardown.** `ssh -n` inside loops (harness FD-3 stdin-stealing bug). Verify the image carries your change (javap/md5) before trusting results.
- **Background validation agents PARK on Monitors** — don't rely on them to self-complete; set your own `until pgrep run-tests.sh` wait, or capture+analyze yourself.
- **Verify subagent claims** — my own quick grep used the wrong backpressure format and false-cleaned; the agent's proper check (`attempt N/200`) was authoritative. Demand real evidence.
- Worktree agents branch from `origin/main` (stale) — reset to rc1 HEAD before editing.
- Untracked `aether/tests/integration/suites/02z-killonly/` was accidentally committed into rc1 (`0d5a0b3e7`) — inert test scaffolding, left in place; remove if undesired.

## Open follow-ups (lower priority, not the #109/#110/#68 line)
#241 (community topology — source-as-community-key, worker connect-set, source-aware replacement provisioning, operational `(role,source)` visibility — all enabled by the descriptor now); #93 (drain 500/409); #95 (05 secure-mode); #91 (DHT durability); #97 (#96 budget-stress suite); #94 (recovery latency, #68-adjacent).
