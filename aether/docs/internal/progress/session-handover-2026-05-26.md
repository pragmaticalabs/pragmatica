<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-26 (#230 slot-membership REDESIGN: collapse + false-drain fixed; recovery-convergence remains)

**Branch:** `release-1.0.0-rc1`. **Local HEAD = `c970c4780`.** **origin HEAD = `75519d174`** — the entire stack below (12 functional + design/test commits) is **LOCAL and UNPUSHED. DO NOT PUSH until Docker suite-02 is green** (it isn't yet — see §4).

## 0. TL;DR
#230 began as a Docker suite-02 flood (CTM over-provisioning to 18–28 containers). This session diagnosed it through **four** layers and ended up **redesigning the slot-based core-membership model** (design note: `aether/docs/specs/slot-based-core-membership-redesign.md`). The redesign **eliminated the catastrophic failure classes** (flood, collapse, false self-drain — all validated on Docker). One distinct issue remains: the **recovery path does not converge** (after kills the cluster wedges under-provisioned with slots stuck FILLING). That is the start point for next session.

## 1. VALIDATED on Docker (the wins)
- **No flood.** CTM provisioning is gated on `inQuorum()` (committed-healthy bit, not transport `connectedPeers`). Below quorum it stops provisioning and the already-wired `SelfDrainCoordinator` dissolves the minority. (Confirmed: 28→bounded; SelfDrain `recordBelowQuorum→debounce→recover` observed live.)
- **No collapse.** The leader-side surplus reaper (which reaped 4 live nodes at once → cascade) is **deleted**. Bindings are **stable across leader change** (`seedOrReseedSlots` = create-once/preserve; no more wipe-and-rebind, which was the OQ4 defect that orphaned live seeds).
- **No false self-drain.** The §5 orphan self-drain uses a **dynamic** predicate — `core && isActive() && inQuorum() && liveFilled==configured && self-not-a-live-occupant`, where `liveFilled` counts slots whose occupant is in the connected set. In the final run: **zero `exit 2`, zero orphan-drain firings** through the kills. (Phase 2a, which used a fixed grace + `boundSet.size()==configured`, drained the WHOLE cluster — that is fixed.)
- **Reducer throw fixed** (`(Stopped|Draining, SlotClaimed) → nop`), **reserve-then-provision** (spawn chained to slot reservation + in-flight slot guard), **dissolve on quorum loss** all hold.

## 2. The redesigned model (design note is authoritative)
`aether/docs/specs/slot-based-core-membership-redesign.md`. Core-nodes-only (workers separate). Invariants:
- Exactly `clusterSize` durable integer slots; **KV is the source of truth**; a core node with **no slot binding is an orphan**.
- **Coherence keystone: core `ON_DUTY ⟺ holds a slot`.** Binding drives lifecycle.
- **Create-once/preserve:** first formation (empty KV) creates slots; leader change reads & continues, **never wipe/rebind**.
- **Universal slot-fill (§3):** empty slot → **bind a connected/joined/unbound/non-draining node if one exists**, else **provision** (fallback). Provisioning suppressed during initial formation. This subsumes formation, late-join, re-provision, and surplus.
- **Self-drain (§5):** node removes itself only when every slot is live-filled by connected members and it is not one of them; while any slot is unfilled (empty OR stale/disconnected occupant) it WAITS. Synced==converged (Rabia sync pulls the quorum's highest committed snapshot; gate on `isActive() && inQuorum()`).
- **No leader-side reaper.** (Removed deliberately — two orphan-removal actors interfere; the c525c9116 reaper caused the collapse.)

## 3. Commit stack (local, unpushed; HEAD `c970c4780`)
```
c970c4780 feat(membership): Phase 2b universal slot-fill + dynamic self-drain (#230)
b85a67428 docs: universal slot-fill + ON_DUTY⟺slot coherence + dynamic self-drain (#230)
a60f900e0 feat(membership): orphan self-drain — core-only KV-slot checker → SelfDrainCoordinator (#230 S5)
1038b9ce5 refactor(membership): Phase 1 — create-once/preserve seed + remove surplus reaper (#230)
91b50e672 docs: slot-based core membership redesign note (#230)
b47743f48 test(integration): discover live mgmt endpoint by aether.cluster label (survives seed replacement)
8298fccae fix(membership): short bounded drain for surplus orphans (#230)   [reaper — code later deleted by 1038b9ce5; commit retained in history]
c525c9116 fix(membership): reap surplus ON_DUTY occupants + drop provenance shield (#230)   [reaper — caused collapse; deleted by 1038b9ce5]
726998dc9 fix(ctm): reserve-then-provision — chain spawn to reservation + in-flight guard (#230)
0f7877cc5 fix(ctm): gate provisioning on quorum + skip STOPPED occupants in reseed (#230)
504d2ce06 fix(membership): reducer (Stopped|Draining, SlotClaimed) idempotent nop (#230)
60151df21 fix(membership): durable slots persist on lifecycle exit — remove legacy removeSlot deletions (#230)
```
(Plus the prior `238ef4c9d`/`b613cee53`/… slot-conv base already on the branch.) All unit tests green (aether-deployment 613 pass at HEAD). **Consider squashing before push** (per CLAUDE.md single-squash-per-feature; the reaper commits c525c9116/8298fccae are superseded by 1038b9ce5).

## 4. THE REMAINING ISSUE — recovery does not converge (start here)
**Symptom:** after the chaos kills, the cluster wedges **under-provisioned** and fails `restore_cluster_baseline: converge to 4+ ON_DUTY within 600s (current=3)`. Occupancy sticks at `healthy=3 occupancy={FILLING=2, HEALTHY=3} emptyToFill=0` with only 3 running containers (the 3 surviving seeds; node-1/2 killed and never replaced).

**Two intertwined clues (next session's leads):**
1. **FILLING slots wedge.** Two slots stay `FILLING` indefinitely — bound to nodes whose containers aren't running, yet classified FILLING (not EMPTY), so `emptyToFill=0` and **nothing re-provisions them**. → The **FILLING→EMPTY-on-deadline reclassification is not firing** (or the slots are bound to "connected-but-not-healthy" occupants that never time out). Look at `classifyEmptyOrFilling`/the FILLING deadline + how the universal-fill bind-existing path stamps FILLING (commit c970c4780 in `ClusterTopologyManagerRecord`).
2. **Leader / JoinDeadline churn.** `JoinDeadlineExpired` fires for the *live surviving* nodes (node-3/4/5), all no-op'd as "received on non-leader node-3 — possible leader-handoff race." Phase flapped COLD_BOOT↔NORMAL at formation. → Suspect the **Part C change** (`MembershipFsm` synthesizes `SwimHealthy` on slot-bind to land an existing node ON_DUTY) interacting badly with the join-deadline / leader-handoff machinery, OR formation phase instability. Check `MembershipFsm` SwimHealthy synthesis (c970c4780) + JoinDeadline handling.

**Hypotheses to test (unverified):** (a) FILLING deadline not honored for bind-existing slots → add/verify timeout→EMPTY→refill; (b) the SwimHealthy-on-bind synthesis double-fires or races JoinDeadline; (c) #231 dependency — a killed node's slot isn't freed (failure detection latency) so the replacement is never triggered. Note the design explicitly inherits #231 for dead-slot-free latency.

## 5. Validation method (Docker is the only arbiter)
In-process Ember harness is synchronous and CANNOT reproduce these async/cross-plane bugs. Loop (~20 min/run):
- Rebuild JAR: `mvn -pl aether/node install -DskipTests -am` (build-runner agent). NEVER `mvn verify` (HCLOUD_TOKEN set → real paid servers).
- Run: `cd aether/tests/integration && ./run-tests.sh --env remote --suites 02 --skip-build` (rebuilds remote image from the JAR via `--no-cache`).
- Monitor the log for `\[FAIL\]|FAILED:|PASSED:|Orphan self-drain|exit code|exited within|restores to 5|S19|S20`. Spot-check the host after the first kill: `ssh $AETHER_SSH_USER@$TARGET_HOST docker ps --filter name=aether-b` (exit-2 = self-drain; 137 = test kill). **Tear down between runs:** `docker compose -f ~/docker-compose-b.yml down -v` then `docker rm -f $(docker ps -aq --filter name=aether-b)`.
- Env: `TARGET_HOST`/`AETHER_SSH_USER`/`AETHER_SSH_KEY`/`HCLOUD_TOKEN` exported (reference by name). Mgmt endpoint now survives seed replacement via label-discovery (b47743f48) — no more `got ''` cascade.
- Last run log: `/tmp/docker-s02-phase2b.log`. Known-good signal target: `Initial 5 ON_DUTY`, `Auto-heal restores to 5`, S19 survivors `exit code 2` + S20 recover. **#231-class failures (S01 budget, NODE_FAILED-within-90s) are a SEPARATE track and will still fail** — don't chase them under #230.

## 6. NEXT STEPS (ordered)
1. Diagnose the FILLING-wedge: re-run suite-02, capture the leader's CTM logs around a kill (`Provisioning new instance`, `stamped/committed FILLING`, `classify`, `freedDead`, the FILLING deadline). Determine why a killed node's slot isn't freed→refilled. Delegate to aether-investigator with the §4 clues.
2. Determine if Part C (SwimHealthy-on-bind) is causing the JoinDeadline/leader churn — consider whether bind-existing should drive ON_DUTY differently.
3. Fix per confirmed mechanism (jbct-coder), re-validate on Docker.
4. Only when suite-02 converges to 5 + S19/S20 pass: squash the stack, CHANGELOG, push, close #230.

## 7. Lessons (reinforced)
- **Verify subagent diagnosis with RUNTIME evidence, not code claims** — three confidently-wrong root causes this session (deadline-vs-boot, slot-deletion, double-provision) all survived code-level review; only Docker logs (`Consensus apply timed out`, `reapReseedSurplus NOT auto-reaped`, the all-nodes `Orphan self-drain`) exposed the truth. See [[feedback_verify_subagent_claims]].
- **Self-termination is a footgun** — §5 drained the whole cluster once (formation gap) before the dynamic predicate fixed it. Gate hard; prefer state-based (wait-while-unfilled) over time-based.
- **Sub-quorum minority must dissolve, not auto-heal** — [[project_subquorum_must_dissolve]].
- Docker is the only arbiter for this subsystem (~20 min/iteration); budget accordingly.
