# Session Handover — 2026-05-22 (c)

**Branch:** `release-1.0.0-rc1` | **HEAD:** `e15cc408d` (3 new commits this session)
**Predecessor:** [`session-handover-2026-05-22b.md`](session-handover-2026-05-22b.md) — structural diagnosis + reconciler spec draft.
**Theme of this session:** Pre-reconciler hygiene + walked D2/D3, and discovered the spec's phase model was wrong (no `DEGRADED` exists).

---

## TL;DR for the next session

1. **Path 2 v2 + the MembershipFsm root-cause fix are now committed** (3 commits). Working tree retains only test-script + lib + lint-baseline edits, plus two untracked docs (this handover and the spec).
2. **D2 and D3 resolved** in the spec. **D4 has a draft answer (NORMAL only) but is not yet ratified by the user** — that's where to pick up. D5–D8 still open.
3. **Critical structural correction surfaced this session:** `ClusterPhase` has only THREE values — `COLD_BOOT / NORMAL / RECOVERING`. There is **no `DEGRADED`**. The reconciler spec's references to DEGRADED are wrong and need scrubbing (§1, §4.3, D4). Empirically verified — reconciler activity does NOT trigger RECOVERING (writes to `NodeLifecycleKey.updatedAt` don't move `oldestOnDutyAt` backward; only sub-quorum / no-leader / unsettled-stability-window do).
4. **Build is red on `./build.sh` Step 2 (format-lint)** due to 26 pre-existing JBCT-RET-01 violations across `aether-stream` (14), `aether-metrics` (3), `aether-deployment` (9). NONE caused by this session's edits — empirically verified via baseline-vs-staged diff. Tracked as task #13. Focused `mvn -pl aether/aether-deployment install -DskipTests -am` is the unblocked path until #13 is resolved.

**Pick up from:** Ratify D4 (recommendation: NORMAL only, with rationale below), then scrub DEGRADED from spec, then walk D5.

---

## Commits landed this session

```
e15cc408d  fix(ctm): gate leader-failover provisioning on phase!=COLD_BOOT to avoid ghost replacements
1846a618c  fix(membership-fsm): apply reducer effects on shadow path and warn on dropped writes
c8d6f6faa  fix(membership-fsm): route SlotClaimed through leader-writing path so JOINING write + timer reach consensus
```

### Why these three matter

- **`c8d6f6faa` (A)** is the **root-cause fix for task #12** (JOINING-window-kill never reaches DECOMMISSIONED within S01 budget). `SlotClaimed` was misclassified as a shadow-only event — the reducer's `(Untracked, SlotClaimed) → Joining` transition emits `Put(NodeLifecycleKey, JOINING)` + `ScheduleTimer(JOIN_DEADLINE)`, and the shadow path silently dropped both. Fix: add `SlotClaimed` to `isLeaderWritingEvent()`, route `applySlotPutAssigned` dispatch through `processOperatorEventLocked`.
- **`1846a618c` (B)** is a defensive companion. `processFsmEventLocked` (the shadow path) now calls `applyEffectsLocked` so reducer-emitted timers/events don't disappear, AND logs a `WARN` if a shadow-only event produces writes. Would have caught bug A at runtime.
- **`e15cc408d` (C)** is the prior session's Path 2 v2 (the CTM cold-boot phase gate). Commit comment in the previous handover.

**Task #12 status:** root-cause fix landed; awaiting validation by a fresh 02-chaos run. Per the rule "always write a regression test after a bug fix" — the existing `test-joining-window-kill.sh` IS the regression test for A. The next session should run 02-chaos and confirm #12 passes (specifically the "S01 budget" assertion), then mark #12 completed.

---

## Spec updates this session

File: [`aether/docs/specs/cluster-convergence-reconciler-spec.md`](../../specs/cluster-convergence-reconciler-spec.md) (still untracked — commit when D-walks finish).

### D2 — Command persistence — RESOLVED
**Choice: in-memory + audit-log every received command.**

- §4.2 updated: API is synchronous-on-consensus (2xx only after Rabia accept); 5xx/timeout → operator retry. Standard HTTP semantics. Failure window (leader dies after receive, before consensus) recoverable: reconciler-emitted commands are idempotent and re-emitted next tick; operator-emitted commands rely on retry.
- §4.5 updated: TWO audit-log events per command lifecycle — `CommandReceived (timestamp, command, justification, source, decision: APPLIED | REJECTED_ILLEGAL_TRANSITION | LOST_LEADER_DIED)` and `CommandApplied (timestamp, command, resulting_state, writes)`. `LOST_LEADER_DIED` cases are recorded by the *next* leader during reconciler observation — the original leader can't record its own death.

### D3 — Tick rate + per-rule budgets — RESOLVED
**Choice: all four sub-decisions adopted.**

- D3.1 — Tick: 10s default, configurable via `aether.toml [reconciler]`, bounds `[5s, 60s]`.
- D3.2 — Budgets calibrated to protocol constants (not absolute numbers): `JoiningTimeout` = `JOIN_DEADLINE × 1.5`, `OnDutyFaulty` = `SWIM_FAULTY_DECLARATION × 3`. Reconciler stays a backstop, never a competitor of timer paths.
- D3.3 — Precondition guards:
  - `OnDutyFaulty` requires SWIM positive `Faulty` declaration, not mere absence from `Alive`.
  - `SwimLifecycleGap` requires no historical NodeLifecycleKey entry for that nodeId in the last hour (prevents resurrecting GC'd DECOMMISSIONED entries).
- D3.4 — Per-rule enable flags in `[reconciler.rules]` of aether.toml for granular Phase 3 rollout.

§4.3 rewritten with the new rule table including precondition column and calibration formulas.

---

## D4 — DRAFT, NOT YET RATIFIED

User asked: *"doesn't start of reconciliation switch to RECOVERING?"*

That question forced a verification pass and surfaced that **my mental model was wrong on a load-bearing point.**

### Empirically verified (Explore investigation, this session)

- `ClusterPhase` defines exactly THREE values: `COLD_BOOT`, `NORMAL`, `RECOVERING`. Source: `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java:614-618`.
- **There is no DEGRADED.** The reconciler spec's references to DEGRADED (§1 "Aether maintains four parallel...", §4.3, D4 itself) are wrong and need scrubbing.
- RECOVERING triggers from `ClusterPhaseView.recoveringBranch()` (`aether-deployment/.../ClusterPhaseView.java:165-170`) on:
  1. Sub-quorum (`onDutyCount < quorum`)
  2. No leader (`haveLeader == false`)
  3. Stability window not satisfied (`nowMs - oldestOnDutyAt < recoveryStableWindowMs`)
- **NodeLifecycleKey writes from reconciler commands do NOT trigger RECOVERING by themselves.** Removing a stale ON_DUTY entry doesn't make `oldestOnDutyAt` newer (it's a `min` aggregate). Phase stays NORMAL while quorum + leader + stability hold.
- CTM already gates on `phase == NORMAL` only (`ClusterTopologyManagerRecord.java:307-319`).

### The draft answer

**Run in NORMAL only. No-op in COLD_BOOT and RECOVERING.**

- COLD_BOOT no-op: baseline still booting, severe false-positive risk.
- RECOVERING no-op covers two sub-cases:
  - Sub-quorum / no-leader: commands would fail consensus anyway.
  - Stability-window-pending: state machines settling through other paths; reconciler racing them is noise; budgets are backstops not first responders.
- Matches CTM's existing gating model (same line).
- For the genuinely-stuck-in-RECOVERING case (e.g. quorum permanently lost), the watchdog (§4.4) surfaces stalls to operator alerts; operator uses the synchronous `ForceDecommission` API (D2-resolved) to drive recovery manually.

### Why this isn't yet ratified

User asked for the handover before confirming. Three sub-options were on the table:
- **(a)** NORMAL only as revised (recommended)
- **(b)** NORMAL + RECOVERING-with-quorum (gate on `haveLeader && quorum`, ignore stability window)
- **(c)** Push back on the framing

Next session: present this and get the call.

### Scrub work after D4 ratifies

Regardless of D4 outcome, the spec needs DEGRADED scrubbed from:
- §1 table (Aether's four parallel state machines — text mentions DEGRADED implicitly via spec's earlier wording? double-check)
- §4.3 phase gate sentence ("No-op during `COLD_BOOT` / `RECOVERING`" — already correct now, but check earlier wording in the file isn't stale)
- §8 D4 itself

Grep before editing: `grep -n DEGRADED aether/docs/specs/cluster-convergence-reconciler-spec.md`.

---

## Remaining open decisions

- **D4** — phase gate scope (draft above, not ratified)
- **D5** — watchdog: dedicated component or shared with FSM hooks?
- **D6** — CTM relationship (leave / document contract / refactor under primitive)
- **D7** — test surface (API ring buffer vs log-based)
- **D8** — migration of existing direct-write call sites (`lifecycleWriter().requestFailedDrain(...)` etc.)

Walk one at a time. User prefers deep Q&A per decision, not batched lists.

---

## Working tree state (uncommitted)

```
 M aether/tests/integration/lib/cluster.sh
 M aether/tests/integration/lib/common.sh
 M aether/tests/integration/lint-baseline.txt
 M aether/tests/integration/suites/02-chaos/test-joining-window-kill.sh
 M aether/tests/integration/suites/02-chaos/test-kill-leader.sh
 M aether/tests/integration/suites/02-chaos/test-kill-multiple.sh
 M aether/tests/integration/suites/02-chaos/test-kill-node.sh
 M aether/tests/integration/suites/02-chaos/test-kill-under-load.sh
 M aether/tests/integration/suites/02-chaos/test-self-drain-quorum-loss.sh
?? aether/docs/internal/progress/session-handover-2026-05-22b.md
?? aether/docs/internal/progress/session-handover-2026-05-22c.md  (this file)
?? aether/docs/specs/cluster-convergence-reconciler-spec.md
```

Per-file recommendation:
- **Test scripts + lib/*.sh + lint-baseline.txt:** review per-file before committing. They're mixed (some noise-cleanup, some semantic changes from the patching attempts). Don't bulk-commit. Path 2 v1 baggage may be in there.
- **Spec + this handover:** untracked but stable artifacts. Commit when the D-walk completes and the DEGRADED scrub is done (single `docs:` commit for both makes sense).
- **Predecessor handover (`session-handover-2026-05-22b.md`):** also untracked. Commit it now even though work continued — the historical record is the handover, and rebasing it would confuse the timeline. Suggest: commit `b.md` and `c.md` together in a single `docs:` commit at end of next session.

---

## Build status

`./build.sh` is RED on Step 2 (format-lint), pre-existing. Task **#13** tracks the 26 JBCT-RET-01 violations across three modules:
- `aether-stream` — 14 violations (StreamPartitionManager, ConsumerRuntimeState, ThresholdStrategy)
- `aether-metrics` — 3 violations (ClusterSyncCollector, InvocationMetricsCollector + 1)
- `aether-deployment` — 9 violations (NodeLifecycleManager ×2, TaskAssignmentCoordinator ×5, SelfDrainEventPublisher, MembershipView)

Empirically verified this session: my edits to `MembershipFsm.java` and `ClusterTopologyManagerRecord.java` introduce ZERO new lint violations vs HEAD baseline. Safe to commit; build red is orthogonal.

**Unblocked workflow:** `mvn -pl aether/aether-deployment install -DskipTests -am` (focused compile, bypasses Step 2) until #13 is resolved.

Most violations are likely missing `@Contract` annotations for intentional void-side-effect methods, not real bugs. Triage candidate, not foundational rewrite. Suggest: spawn a focused session for #13 after D-walk completes, before Phase 1 implementation.

---

## Phase 1 implementation prep (NOT started)

After D5–D8 are walked and DEGRADED is scrubbed, Phase 1 begins:

1. `LifecycleCommand` sealed interface in `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/`. Same package as `MembershipFsm.java` (already modified this session). BSL header required.
2. Extend reducer signature `(state, event) → outcome` to `(state, input) → outcome` where `input = event | command`.
3. `applyCommand` method on `lifecycleWriter`.
4. Unit tests per command per state.

Files to read before touching code (carried from predecessor handover):
- `aether/docs/specs/cluster-convergence-reconciler-spec.md` (this session's design — finished form after D-walk)
- `aether/docs/specs/cluster-membership-fsm-spec.md` (existing reducer model)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsm.java` (already modified — review the 3 commits A/B before extending)
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/NodeLifecycleRoutes.java` (where the new command endpoint lands in Phase 2)

---

## Active tasks

- **#5** [in_progress] Make 02-chaos pass end-to-end — root-cause fix #12 landed; next 02-chaos run validates
- **#8** [in_progress] Investigate test-kill-multiple lifecycle staleness cascade — same structural gap, reconciler subsumes
- **#12** [in_progress] JOINING-window kill: FSM doesn't demote to DECOMMISSIONED within S01 budget — ROOT CAUSE FIXED commit `c8d6f6faa`, validation pending
- **#13** [pending] Pre-existing JBCT-RET-01 violations across aether-stream / aether-metrics / aether-deployment (26 total)
- **#14** [in_progress] Finalize convergence reconciler spec: walk D4-D8 decisions

---

## Constraints to remember (carry over)

- **NEVER run `mvn verify` with `HCLOUD_TOKEN` set** — creates real Hetzner servers
- **NEVER pass `-Djbct.skip=true` for aether builds** — POM hierarchy handles it
- **NEVER create feature branches on `release-1.0.0-rc1`** — commit directly
- **Single-line commits only**, no body, no `Co-Authored-By` trailers
- **Aether code is BSL-1.1**, SPDX short header required on new files (markdown docs exempt)
- **AETHER_INSECURE_DEV_MODE=true** is set in cluster A+B compose env
- **Tests must be self-contained** — assume nothing about cluster state from prior runs
- **PEERS uses 3-part format** `nodeId:host:port` — never 2-part
- **`./build.sh` is RED pre-existing (#13)** — use focused `mvn -pl <module> install -DskipTests -am` until resolved
- **User mode:** auto-mode active; bias toward action but stop when unclear direction

---

## One-line summary for `git log` (when commits land)

This session: three real commits (`c8d6f6faa`, `1846a618c`, `e15cc408d`) — SlotClaimed routing root-cause fix, shadow-path effect symmetry guard, CTM cold-boot phase gate. Spec D2/D3 resolved, D4 drafted (NORMAL only) pending ratification, D5-D8 open. Phase model corrected: no DEGRADED.
