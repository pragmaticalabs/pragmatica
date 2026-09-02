<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-28 (cluster-B wedge is a STACK of bugs; peeled 3 layers, exposed SWIM-resurrection as the now-dominant cause)

**Branch:** `release-1.0.0-rc1`. **HEAD `295625c36`. All work LOCAL/unpushed — DO NOT push** (cluster-B not green; the dominant wedge cause is now a different, un-fixed layer).

## 0. TL;DR
The cluster-B chaos-recovery wedge is **not one bug — it's a stack**, each masking the next. This session peeled three layers (all committed, all unit-proven), then end-to-end validation revealed the next dominant layer:
1. **Consensus-bootstrap deadlock** → SWIM/join-ANNOUNCE was quorum-gated → **ungated** (`309a4cb1d`, spec-mandated). A healthy replacement can now reach ON_DUTY. Validated: recovery to 5 ON_DUTY on a clean run (never happened before).
2. **Test-harness debt** (chaos victim/survivor selection hardcoded static compose ordinals, broke on KSUID-named CTM replacements) → **membership-based enumeration** (`c47c1aa69`) + charter citations repointed to real specs.
3. **Slot-reclaim flap re-stamp race** (a terminal/flapping occupant re-claimed its slot and re-stamped the FILLING deadline, starving `freeStaleFillingSlots`; leader-handoff widened the window by dropping the one-shot `JoinDeadlineExpired` on the non-leader) → **fixed** (`14317ac8b`) + TimeSpan remodel (`695e3139f`) + RequestReJoin coverage (`295625c36`). Deterministic unit test RED→GREEN.
4. **← NOW-DOMINANT: SWIM-gossip RESURRECTION (#231).** End-to-end re-run STILL wedges at `current=3`. The flap-fix is confirmed ACTIVE+correct, but **dead nodes are re-admitted/retained ON_DUTY**, inflating CTM's healthy count so it never provisions the real 5th core. **This is the next target. NOT auto-fixed — membership-safety-critical.**

**Contamination hypothesis (raised + REFUTED):** `--suites 02` is NOT contaminated — `deploy_docker` runs the cluster-A deploy block unconditionally, which wipes `aether_pgdata` (the shared consensus/KV volume). Verified in the run log (`Deploying Cluster A` + `aether_pgdata` wipe). Both runs started clean.

## 1. Commit stack (local, unpushed, on top of prior `f8b7f85dd`)
```
295625c36 test(membership): cover RequestReJoin-remove→UNTRACKED path; fix inaccurate terminal-only justification (#230)
695e3139f refactor(cluster): derive slot FILLING expiry from auto-heal provisioningTimeout (TimeSpan), drop stored deadlineMs instant (#230)
14317ac8b fix(membership): stop terminal peer re-claiming its slot (re-stamping FILLING deadline) so stale-fill reclaim can free+refill — fixes flaky stuck-at-3 (#230)
c47c1aa69 test(chaos): select 02-chaos victims/survivors by live membership not static ordinals; repoint charter citations to existing specs (#230)
309a4cb1d fix(membership): start SWIM + join-ANNOUNCE at transport-ready, not quorum-gated — fixes sub-quorum auto-heal deadlock (#230)
1f0aa42f1 fix(consensus): Paused Rabia responder serves live-equivalent sync snapshot with pendingBatches (#230)
```
Tag `v1.0.0-rc1-candidate` on HEAD. Uncommitted: only the 2 forge spikes (`MembershipMultiKillSpikeTest`, `MembershipQuorumMaskSpikeTest`) — intentionally untracked.

## 2. The layered model (the key mental shift)
cluster-B recovery requires a chain: **kill → detect-dead → decommit slot → free slot → provision replacement → replacement connects consensus → reaches ON_DUTY → converge to N.** Each session-fix repaired one link; the wedge persisted because the NEXT broken link dominates. So a fix being "correct + unit-proven" yet "cluster-B still red" is EXPECTED — you're peeling layers. Validate each fix at its own layer (unit/log signature), not solely by "is 02 green."

## 3. THE NOW-DOMINANT BUG — SWIM resurrection (#231) — NEXT TARGET
**Mechanism (investigator, clean run 22:xx, container logs):**
- **Bare-join re-admit:** `ClusterMembershipReducer.java:129` — `(Untracked, SwimHealthy) → untrackedDirectToOnDuty → ON_DUTY`. A vanished node (node-2, **no container** — node-5 logs `DNS resolution failed for aether-b-node-2`) is re-stamped ON_DUTY from a **stale SWIM gossip** entry: `SwimHealthy peer=node-2 priorState=Untracked → newState=OnDuty writes=1 applied=true`.
- **Phantom retention:** node-4 is `Exited(137)` yet the leader logs `SwimFaulty peer=node-4 priorState=OnDuty → newState=OnDuty` + `re-dialing configured peer node-4 (phase=CONNECTING)` — the decommission cell vetoes (re-gated 2-plane gate `2bf283a4e` / φ-accrual `5a9885ecd` not firing for it), so it stays ON_DUTY.
- **Result:** CTM steady-state `reconcile(slot): configured=5 healthy=4 freedDead=0 freedStale=0 emptyToFill=0 occupancy={HEALTHY=4, FILLING=1}` — `healthy=4` = {node-3, node-5, real KSUID-replacement, **node-4-phantom**}; the lone `FILLING=1` is node-2's orphaned slot. CTM thinks it's satisfied → **never provisions the real 5th**. The harness's transport-reachable probe sees only the 3 live containers → `current=3` → 600s timeout.

**Prior art:** memory `project_cluster_b_collapse_root_cause` says this resurrection was root-caused 2026-05-24 with a **"SWIM bare-join-no-HEALTHY"** fix that was **STAGED/held, never committed**. It is **NOT in git stash or history** (checked) — discarded; must be re-derived.

**Why NOT auto-fixed this session:** membership-safety-critical. This is exactly the area where prior SWIM changes caused cluster-wide eviction storms that passed unit + leader-kill tests and only the full Docker suite exposed (see handover 2026-05-27 §4). The history is tangled — φ-accrual (`5a9885ecd`), the re-gated 2-plane decommission cells (`2bf283a4e`), the removed `decommissionedSwimHealthy` revival (reducer §H.4 `:256`). A wrong cut here risks split-brain / eviction storm. **Needs design + the user's eyes before committing/pushing.**

**Fix direction (for discussion, not yet done):** (a) `(Untracked, SwimHealthy)` must NOT go directly ON_DUTY on bare gossip — require a real transport connection / JOINING handshake (the "bare-join-no-HEALTHY" idea); (b) a `SwimFaulty`/black-holed ON_DUTY occupant whose transport is gone must actually decommission (the #231 forward-decommission gap) so CTM's `healthy` count reflects reality and it provisions the real replacement.

## 4. Validation evidence (this session)
- **Flap-fix ACTIVE+correct:** new log `lifecycle-removed peer=… → UNTRACKED; slot=N left for CTM stale-FILLING reclaim (#230)` fires (slot=1 @22:14:07, slot=0 @22:16:07); old `retained slot=… → PROVISIONING` GONE. Unit: `MembershipFsmTerminalSlotReclaimTest` 6/6 (RED→GREEN), 218 FSM/reducer/lifecycle + 622 deployment tests green.
- **Ungate:** clean first full-run 02 reached `cluster at baseline (5 ON_DUTY healthy cores, generation quiesced)` ×2; `Backpressure exceeded` count 0 (vs the 28-min wedge before).

## 5. Remaining items (ordered)
1. **★ SWIM resurrection (§3)** — the now-dominant cluster-B wedge cause. #231. Design-first.
2. **Why do replacements/static nodes flap `Provisioning→Joining→Stopped` and never stabilize to ON_DUTY?** Upstream of the slot-reclaim; likely entangled with §3 (a replacement that can't get/keep ON_DUTY). Worth a dedicated trace.
3. **Harness fragility:** `lib/common.sh:422` `wait_for` predicates do `[ $count -eq N ]` with no empty-`$count` guard → `unary operator expected` spam/spin when the API returns empty during degraded states. Real robustness bug; guard it.
4. **#236 quorum-mask (RC2):** liveness/quorum gates count transport-reachable peers, not synced voters — directly feeds the §3 phantom-OnDuty inflation.

## 6. Dev-loop notes
- Build node JAR (picks up aether-deployment + aether/slice fixes): `mvn -pl aether/node install -DskipTests -am` (bypasses the lint-blocked build.sh).
- Clean 02 run: `cd aether/tests/integration && ./run-tests.sh --env remote --suites 02 --skip-build --skip-teardown`. `deploy_docker` ALWAYS deploys cluster-A first → wipes `aether_pgdata` → clean start (NOT contaminated). Lint baseline updated this session (the harness-fix line shift); lint gate passes.
- CTM/membership internals (occupancy, freedStale, SwimHealthy/SwimFaulty, the UNTRACKED log) are in the **container** logs (`docker logs aether-b-node-*`), NOT harness stdout. Grep via ssh (`$TARGET_HOST`/`$AETHER_SSH_KEY`/`$AETHER_SSH_USER`).
- **The membership/resurrection bugs ARE unit-testable** (pure FSM/reducer/CTM logic) — prefer deterministic RED→GREEN tests over the flaky Docker loop. The flap-fix this session is the template.

## 7. Remote state
cluster-B left dissolved/wedged on `$TARGET_HOST` (`--skip-teardown`); all cores Exited (137/2). The next run's `deploy_docker` redeploys clean (compose down -v + zombie sweep + cluster-A pgdata wipe) — no manual cleanup needed.

## 8. References
- Issues: **#230** (cluster-B recovery), **#231** (φ-accrual / failure-detection — the resurrection lives here), **#236** (RC2 bounded dissolution / quorum-mask).
- Specs: `membership-architecture-spec.md §16` (S-rows + §16.1 self-drain), `slot-based-membership-convergence-spec.md §2/§5`, `swim-driven-topology-spec.md §canonical-ordering` (mandates the ungate).
- Charter: `aether/tests/integration/suites/02-chaos/CHARTER.md` (refreshed this session).
- Prior handover: `session-handover-2026-05-27b.md`.
