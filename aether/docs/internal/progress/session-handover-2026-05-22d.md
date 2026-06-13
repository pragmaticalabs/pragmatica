# Session Handover — 2026-05-22 (d)

**Branch:** `release-1.0.0-rc1` | **HEAD:** `e15cc408d` (no new commits this session — all work uncommitted)
**Predecessor:** [`session-handover-2026-05-22c.md`](session-handover-2026-05-22c.md) — Path 2 v2 + MembershipFsm root-cause fix landed; spec D2/D3 resolved, D4 drafted.
**Theme of this session:** Phase 1 PR-A scaffolding for the cluster convergence reconciler — opened in-place, ~9/13 sub-steps complete and compiling, ready for next-session H+I sweep then K/L cleanup.

---

## TL;DR for the next session

1. **Phase 1 PR-A scaffolding is ~75% complete and uncommitted.** Twenty files modified/new in the working tree, all compiling clean against `mvn -pl aether/aether-deployment install -DskipTests -am`. Sub-steps **A, B, C, D, E, F, G, ForceDrain, J** all green; **H, I, K, L** pending.
2. **The plan file lives at `/Users/sergiyyevtushenko/.claude/plans/stateless-twirling-glade.md`** — read this FIRST in the next session. It's the source of truth for what Phase 1 means, the H/I/K/L scope, and Phases 2–5 sequencing. Plan files don't survive sessions cleanly; if it's gone, the spec at `aether/docs/specs/cluster-convergence-reconciler-spec.md` (also untracked) carries most of the same content.
3. **Three user-confirmed decisions from this session, in effect:**
   - **`ForceDrain` command variant: YES.** Added as the 5th `LifecycleCommand` variant. Unblocks step K (NodeLifecycleRoutes.java drain endpoint migration).
   - **Real audit publisher wiring: NOW.** Implemented this session — `AetherNode` provisions `audit.lifecycle.commands` topic at boot via `streamPartitionManager.createStream(...)` + `DefaultStreamPublisher.streamPublisher(...)`. Null-safe + onFailure-logged.
   - **Next coding batch: GO AHEAD.** User cleared steps J + publisher fix in parallel; they're done. Next batch (H+I together OR K first) needs steer.
4. **`./build.sh` still RED on Step 2 (format-lint), pre-existing.** Task #13 (26 JBCT-RET-01 violations across aether-stream / aether-metrics / aether-deployment) is orthogonal to this work. Unblocked workflow: `mvn -pl aether/aether-deployment install -DskipTests -am`.

**Pick up from:** decide H+I (FSM record collapse + KV enum 6→4 as one coordinated sweep) vs K (OperatorDrain/Decommission migration — now unblocked by ForceDrain). Recommendation: H+I first as one agent batch, K+L as cleanup batch.

---

## What landed this session (uncommitted)

**No new commits.** HEAD unchanged at `e15cc408d`. All work is in the working tree.

### Phase 1 PR-A sub-step map

| Step | Description | Status | Key files |
|---|---|---|---|
| A | `StopReason` enum + `NodeLifecycleValue.stopReason` sidecar | ✅ | `aether/slice/.../AetherValue.java` |
| B | `MembershipFsmInput` sealed root + `MembershipFsmEvent` rebase | ✅ | `aether-deployment/.../membership/fsm/MembershipFsmInput.java` (new), `MembershipFsmEvent.java` |
| C | `LifecycleCommand` sealed interface, 5 variants | ✅ | `aether-deployment/.../membership/fsm/LifecycleCommand.java` (new) |
| D | Reducer `apply(state, input, gate)` overload + `applyCommand` + 5 per-command handlers | ✅ | `aether-deployment/.../membership/fsm/ClusterMembershipReducer.java` |
| E | `LifecycleWriter.applyCommand` default API + `DirectLifecycleWriter` override with `StopReason` sidecar | ✅ | `aether-deployment/.../cluster/LifecycleWriter.java` |
| F | 3 production call sites migrated to `applyCommand` | ✅ | `ClusterTopologyManagerRecord.java:1076,1182`, `ConsensusDrainCoordinator.java:156` |
| G | Audit stream wiring (real publisher, not stub) | ✅ | `aether-deployment/.../audit/*` (new), `AetherNode.java`, `LifecycleWriter.java` |
| ForceDrain | 5th `LifecycleCommand` variant + reducer dispatch + writer routing | ✅ | `LifecycleCommand.java`, `ClusterMembershipReducer.java`, `LifecycleWriter.java` |
| J | KV deadline atoms (JoinDeadlineKey/DrainDeadlineKey + values) | ✅ | `AetherKey.java`, `AetherValue.java`, `EphemeralKeys.java`, `KVStoreSerializer.java`, reducer entry/exit helpers |
| H | `MembershipFsmState` record collapse (Decommissioned + FailedDrain → Stopped) | ⏸ | Reducer-internal records + helpers; coupled to I |
| I | `NodeLifecycleState` enum collapse 6→4 (JOINING/ON_DUTY/DRAINING/STOPPED) | ⏸ | 13 architecturally-significant case-arms + ~1072 secondary refs across the tree |
| K | `OperatorDrain`/`OperatorDecommission` event → command migration | ⏸ | 2 production sites in `NodeLifecycleRoutes.java:215,452` + 27 test sites. UNBLOCKED by ForceDrain |
| L | Pattern-match consumer updates for collapsed state enum | ⏸ | Cleanup follow-on to I |

### Sub-step details (what to expect when reading the diffs)

- **A — `StopReason` enum:** `AetherValue.java` adds `enum StopReason { GRACEFUL, FORCED, DRAIN_FAILED }` and a `stopReason` field on `NodeLifecycleValue` with `withStopReason(Option<StopReason>)`. Existing constructors preserved as backward-compat overloads — the new field defaults to `Option.none()`.
- **B/C — Sealed input hierarchy:** `MembershipFsmInput` is the new sealed root (permits `MembershipFsmEvent | LifecycleCommand`). Existing event variants unchanged in shape but `extends MembershipFsmInput` now lives on the event interface, not on each variant.
- **D — Reducer command dispatch:** new `apply(state, input, gate)` overload switches on `MembershipFsmEvent | LifecycleCommand`. Each command variant gets a dedicated `applyForce*` / `applyRecord*` / `applyRequest*` handler that exhaustively switches on all 7 `MembershipFsmState` records. Reused existing transition helpers (`enterJoining`, `onDutyToDecommissioned`, etc.) wherever possible; added 3 new `state→Decommissioned` helpers + 1 `state→Draining` for cases that didn't have an event analog.
- **E — `LifecycleWriter.applyCommand`:** default interface method dispatches via `switch (command)` to legacy `request*` methods. `DirectLifecycleWriter` overrides for `ForceDecommission` specifically (to carry the `StopReason` sidecar through to `NodeLifecycleValue`). Audit publishing happens inside `applyCommand` (see step G).
- **G — Audit publishing chain:** `applyCommand` calls `publishReceived(command)` before dispatch, then `publishApplied(command, accepted=true)` on `.onSuccess` or `publishApplied(command, accepted=false)` on `.onFailure`. Payload uses surrogate fields (`commandType`, `peerId`, `reasonTag`, `justificationMessage`, `timestampMs`, `accepted`) — **path B** chosen because `Cause` is not `@Codec`-able. `LifecycleCommand` itself was NOT annotated `@Codec` — audit path doesn't serialize it.
- **G — Real publisher at `AetherNode`:** `streamPartitionManager.createStream(AuditLifecycleStreams.AUDIT_LIFECYCLE_COMMANDS)` provisions the topic (`partitions=4, retention=time/7d, max-event-size=16KB`). `DefaultStreamPublisher.streamPublisher(...)` builds the publisher. The lambda passed to `directLifecycleWriter(...)` is null-safe: `event -> Option.option(ref.get()).fold(Promise::<Unit>unitPromise, p -> p.publish(event))`. `createStream` failures are logged via `logAuditStreamProvisionOutcome(Cause)` (skips benign `STREAM_ALREADY_EXISTS`).
- **F — Call site migrations:**
  - CTM line 1076 `writeDecommissionedAtom`: `ForceDecommission(nodeId, StopReason.FORCED, Causes.cause("CTM: terminate-success decommission for " + nodeId), HlcTimestamp.ZERO)`
  - CTM line 1182 `tombstoneAssignedNodeOnExpiry`: same shape, justification `"CTM: expired slot owner tombstone for " + assignedId`
  - ConsensusDrainCoordinator line 156 `markDrainComplete`: `ForceDecommission(nodeId, StopReason.GRACEFUL, Causes.cause("Drain: markDrainComplete for " + nodeId), HlcTimestamp.ZERO)`
- **ForceDrain:** added to `permits`, record `ForceDrain(NodeId peer, DrainReason reason, Cause justification, HlcTimestamp at)`. Reducer handler: `OnDuty → enterDraining`, all other states no-op (idempotent). Writer route: falls through to legacy `requestDrain(cmd.peer())` via default switch.
- **J — KV deadline atoms:** `JoinDeadlineKey(NodeId)` / `DrainDeadlineKey(NodeId)` records added to `AetherKey`. `JoinDeadlineValue(long deadlineMs, HlcTimestamp setAt)` / `DrainDeadlineValue(...)` added to `AetherValue`. Both registered in `EphemeralKeys` (per-runtime observability, not persisted) and `KVStoreSerializer` (sealed-switch exhaustiveness mandatory). Reducer emits `KVCommand.Put` on `enterJoining` / `enterDraining` and `KVCommand.Remove` on every exit (`joiningToOnDuty`, `joiningToDecommissioned`, `drainingHardDeparted`, `drainingOperatorDecommission`, `drainingDrainOutcome`, `drainingToDecommissioned`) plus `applyRequestReJoin`. **Replay-path KV READ deferred** — existing `MembershipFsm.resumeJoinDeadline` / `resumeDrain` recompute correctly from `NodeLifecycleValue.updatedAt()`; atoms are observability-only for now.

---

## File inventory (working tree as of handover)

### This session — modified

```
M aether/aether-deployment/pom.xml                                   (+5)   aether-stream dependency
M aether-deployment/.../cluster/ClusterTopologyManagerRecord.java    (+25)  2 call-site migrations + imports
M aether-deployment/.../cluster/LifecycleWriter.java                 (+178) applyCommand default + ForceDrain arm + audit pub + surrogate helpers
M aether-deployment/.../drain/ConsensusDrainCoordinator.java         (+11)  1 call-site migration + imports + javadoc fix
M aether-deployment/.../membership/fsm/ClusterMembershipReducer.java (+251) input dispatch + 5 command handlers + KV deadline writes + 3 state→Decommissioned helpers
M aether-deployment/.../membership/fsm/MembershipFsmEvent.java       (+2)   extends MembershipFsmInput
M aether/node/.../AetherNode.java                                    (+54)  audit publisher provisioning + AtomicReference indirection + onFailure log helper
M aether/slice/.../AetherKey.java                                    (+61)  JoinDeadlineKey + DrainDeadlineKey records + factories
M aether/slice/.../AetherValue.java                                  (+74)  StopReason enum + stopReason sidecar + JoinDeadlineValue + DrainDeadlineValue
M aether/slice/.../EphemeralKeys.java                                (+4)   2 key types + 2 section names
M aether/slice/.../KVStoreSerializer.java                            (+4)   2 sectionForKey arms + 2 serializeValue arms
```

### This session — untracked (new files)

```
?? aether-deployment/.../audit/AuditLifecycleCommandPublisher.java    qualifier annotation, currently unused (kept for slice-DI consumers)
?? aether-deployment/.../audit/AuditLifecycleStreams.java             StreamConfig AUDIT_LIFECYCLE_COMMANDS constant holder
?? aether-deployment/.../audit/CommandLifecycleEvent.java             sealed payload, surrogate fields, @Codec
?? aether-deployment/.../membership/fsm/LifecycleCommand.java         sealed interface, 5 variants
?? aether-deployment/.../membership/fsm/MembershipFsmInput.java       sealed root
```

### Carryover from session c (UNCHANGED this session — still uncommitted)

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
```

Session c's recommendation stands: review per-file before committing — some are noise-cleanup, some are semantic patches from Path 2 attempts. Don't bulk-commit.

### Untracked docs

```
?? aether/docs/internal/progress/session-handover-2026-05-22b.md   from session b
?? aether/docs/internal/progress/session-handover-2026-05-22c.md   from session c
?? aether/docs/internal/progress/session-handover-2026-05-22d.md   this file
?? aether/docs/specs/cluster-convergence-reconciler-spec.md         from session b, spec form
```

Commit suggestion when the D-walk + Phase 1 PR-A complete: single `docs:` commit for all four handovers + the spec.

### Git remote state

3 commits ahead of `origin/release-1.0.0-rc1` (from session c — `c8d6f6faa`, `1846a618c`, `e15cc408d`). Not yet pushed. Push when comfortable; nothing on origin should conflict.

---

## Open follow-ups (deferred, all tracked)

1. **MembershipFsm replay paths read JoinDeadlineKey/DrainDeadlineKey directly** instead of recomputing from `NodeLifecycleValue.updatedAt()`. Required when in-process JOINING/DRAINING entry HLC and `NodeLifecycleValue.updatedAt()` diverge (out-of-order replication, generation-snapshot backfill). Touches `MembershipFsm.java` around lines 1066/1086/1124 and would introduce `JoinDeadlineSnapshotReader` / `DrainDeadlineSnapshotReader` interfaces.
2. **`MembershipFsmConfig.drainTimeout()` vs spec name `drainDeadline()`** — single-call-site rename. Cosmetic; non-blocking.
3. **TOML round-trip parser for deadline atoms** — additive (RC2 if needed). Atoms are marked ephemeral so `fromToml` skips them; restore from TOML would need new `parseJoinDeadlineEntry` / `parseDrainDeadlineEntry` arms in `KVStoreSerializer.parseKeyValue`.
4. **`LifecycleCommand` records not `@Codec`** — fine for current audit path (uses surrogate fields). Blocks any future consumer that wants the full structured command on the stream. Path A (annotate `LifecycleCommand` + its 5 records) would require `Cause` to also be `@Codec`-able, which it isn't today.
5. **`AuditLifecycleCommandPublisher` qualifier annotation currently unused** — kept for future slice-DI consumers. Remove if slice-DI migration is confirmed unneeded.
6. **`HlcTimestamp.ZERO` placeholders in F migration call sites** — CTM and ConsensusDrainCoordinator pass `HlcTimestamp.ZERO` since `applyCommand` currently routes through legacy `request*` methods (which use `System.currentTimeMillis()`). When `applyCommand` is rewired to route through the reducer directly (future sub-step, possibly after H+I land), real `HlcClock`-derived timestamps must be threaded through these call sites. Grep for `HlcTimestamp.ZERO` to find them.
7. **Audit publisher provisioning failure mode** — `provisionAuditLifecycleCommandPublisher` chains `.onFailure(this::logAuditStreamProvisionOutcome)`. Real failures (e.g., `STREAM_MEMORY_EXCEEDED`) leave the `AtomicReference` empty forever and the lambda silently drops events. Currently observable via one `log.warn`. If we want stronger guarantees, install a fallback in-process buffer or retry the provisioning on next leader takeover.
8. **Topic registration consolidation** — `AuditLifecycleStreams.AUDIT_LIFECYCLE_COMMANDS` is a code constant. If/when slice-level `resources.toml` provisioning lands for deployment, move this to TOML for consistency with other streams (`streams.test-events`, etc.).
9. **Pre-existing JBCT-RET-01 baseline (Task #13)** — still 26 violations across aether-stream (14), aether-metrics (3), aether-deployment (9). Empirically verified: nothing added by this session. Spawn a focused session before Phase 4 (LifecycleReconciler) lands, so that module is clean.

---

## Next-step options for the next session

The user cleared the H+I vs K decision deliberately — they want a steer next session. Recommendation surfaced this session was H+I together (architecturally one decision: FSM record + KV enum both represent terminal states; collapse atomically).

### Option 1 — H+I as one coordinated sweep (recommended)

**Scope:** Collapse `MembershipFsmState` records (Decommissioned + FailedDrain → Stopped with `StopReason`) AND `NodeLifecycleState` enum (6→4: JOINING/ON_DUTY/DRAINING/STOPPED). The two collapses are tightly coupled — the boundary mapping (FSM state record → KV value enum) must update atomically or the reducer's lifecycle-write paths break.

**Blast radius:** 13 architecturally-significant case-arms + ~1072 secondary refs (mostly imports + constructs, mechanically rewritable). Major case-arm sites enumerated in session memory; key ones:
- `MembershipView.java:287` — MemberStatus mapping
- `MembershipFsm.java:1066, 1124` — KV replay
- `SnapshotMembershipView.java:70` — snapshot projection
- `ClusterGenerationProjector.java:196, 220` — guard exclusions
- `NodeLifecycleRoutes.java:164, 270` — route guards
- `BootstrapModule.java:298` — pre-allocation filter
- `ClusterEventAggregator.java:227`, `TopologyObserver.java:709/713/717`

**Recommended agent:** jbct-coder, single-shot. Brief it with the explicit 13-arm list and ask it to mechanically rewrite, then targeted re-review of any string-literal / log-message references that should also collapse.

**Risk:** large diff, but build is the safety net — sealed-switch exhaustiveness will catch every miss at compile time.

### Option 2 — K first (smaller, mechanical)

**Scope:** Migrate 2 production producers of `OperatorDrain` (`NodeLifecycleRoutes.java:215`) and `OperatorDecommission` (`NodeLifecycleRoutes.java:452`) to `LifecycleWriter.applyCommand(new ForceDrain(...))` / `applyCommand(new ForceDecommission(...))`. Then delete the 2 event variants (and their reducer handlers) and rewrite 27 test sites that construct them.

**Blast radius:** smaller and bounded. Test rewrites are mechanical: `new OperatorDrain(peer, reason, at)` → `new ForceDrain(peer, reason, Causes.cause("test"), at)`.

**Risk:** very low. Mostly find-and-replace.

### Option 3 — both in parallel (high risk)

Possible but risky: H+I touches the reducer's transition helpers; K touches the reducer's event handlers (to remove OperatorDrain/Decommission). Same file, different sections — edit collision likely. Don't recommend.

**My recommendation when picking up:** start with K (smaller, builds confidence in the migration mechanics), then attack H+I as one sweep. If user wants speed, swap to H+I first and accept the larger diff.

---

## Verification recipe (run on session start to confirm checkpoint)

```bash
# Confirm git state
git rev-parse HEAD     # expect e15cc408d
git status --short | head -25

# Confirm compile (focused — avoids #13 RED on Step 2)
mvn -pl aether/aether-deployment install -DskipTests -am -q

# Confirm the new files exist
ls aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/audit/
ls aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/LifecycleCommand.java
ls aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsmInput.java

# Confirm no stray legacy calls in migrated files
grep -n "requestDecommission" aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java
grep -n "requestDecommission" aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/drain/ConsensusDrainCoordinator.java
# Both should return zero matches
```

All four should succeed cleanly. If `mvn` fails, something drifted — re-read the file inventory and reconcile.

---

## Active tasks

- **#5** [in_progress] Make 02-chaos pass end-to-end — root-cause fix #12 landed in session c; validation pending fresh 02-chaos run
- **#8** [in_progress] Investigate test-kill-multiple lifecycle staleness cascade — reconciler subsumes this; effectively waiting on Phase 4
- **#12** [in_progress] JOINING-window kill: FSM doesn't demote to DECOMMISSIONED within S01 budget — ROOT CAUSE FIXED in session c (commit `c8d6f6faa`); validation pending
- **#13** [pending] Pre-existing JBCT-RET-01 violations (26 total) — blocks `./build.sh` Step 2 but not focused compiles
- **#15** [in_progress] Phase 1: FSM collapse + Command primitive + migration (PR-A) — **THIS SESSION'S WORK; A-G + ForceDrain + J done; H/I/K/L pending**
- **#16–#19** [pending] Phases 2–5 of the reconciler — sequenced per plan, not yet started
- **#20** [completed] Preflight: aether-stream API reachability + JBCT-RET-01 baseline — done in earlier sub-session

Task #15 description carries the granular sub-step state — `TaskGet 15` for the latest.

---

## Files/lines to read first when picking up

1. **The plan** — `/Users/sergiyyevtushenko/.claude/plans/stateless-twirling-glade.md` (read entirely; ~360 lines)
2. **The spec** — `aether/docs/specs/cluster-convergence-reconciler-spec.md` (still untracked; intersects with plan but adds rationale)
3. **This handover** — `aether/docs/internal/progress/session-handover-2026-05-22d.md`
4. **Predecessor handover** — `aether/docs/internal/progress/session-handover-2026-05-22c.md` (root-cause fixes + D2/D3/D4 walks)
5. **Reducer changes** — `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/ClusterMembershipReducer.java` (skim the new `applyCommand`, `applyForce*`, `applyForceDrain`, `applyRecordJoining`, `applyRequestReJoin` handlers + KV deadline writes in `enterJoining` / `enterDraining`)
6. **Writer changes** — `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/LifecycleWriter.java` (skim `applyCommand` default + `DirectLifecycleWriter` override + `publishReceived` / `publishApplied`)
7. **AetherNode publisher wiring** — `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` around lines 1212/1774, search for `auditLifecycleCommandPublisherRef`
8. **`MembershipFsmState` (for the upcoming H collapse)** — find via `grep -rn "sealed interface MembershipFsmState" aether/aether-deployment/`
9. **`NodeLifecycleState` (for the upcoming I collapse)** — `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` around line 591

---

## Constraints carry-over (still in effect)

- **NEVER run `mvn verify` with `HCLOUD_TOKEN` set** — creates real Hetzner servers
- **NEVER pass `-Djbct.skip=true` for aether builds** — POM hierarchy handles it
- **NEVER create feature branches on `release-1.0.0-rc1`** — commit directly
- **Single-line commits only**, no body, no `Co-Authored-By` trailers
- **Aether code is BSL-1.1**, SPDX short header required on new files (markdown docs exempt)
- **AETHER_INSECURE_DEV_MODE=true** set in cluster A+B compose env
- **Tests must be self-contained** — assume nothing about cluster state from prior runs
- **PEERS uses 3-part format** `nodeId:host:port` — never 2-part
- **`./build.sh` is RED pre-existing (#13)** — use focused `mvn -pl <module> install -DskipTests -am` until resolved
- **Build/test/verify → `build-runner` agent**; 2+ file reads / open-ended hunts → Explore or domain agent; main thread for synthesis + single targeted edits (re-read `~/.claude/CLAUDE.md` + project `CLAUDE.md` on session start)
- **User mode:** auto-mode active; bias toward action but stop when unclear direction
- **User explicitly directed:** use more delegation; the main context is the scarce resource

---

## One-line summary for `git log` (when commits land)

This session: zero commits, ~75% of Phase 1 PR-A scaffolding in working tree — `LifecycleCommand` sealed (5 variants incl. ForceDrain), `MembershipFsmInput` root, reducer command dispatch with 5 handlers, `LifecycleWriter.applyCommand` + audit publisher chain, 3 production call sites migrated, real audit-stream provisioning at node boot, KV deadline atoms with reducer entry/exit writes, all compiling. H+I+K+L pending. Next session: pick H+I sweep or K-first cleanup; user steer needed.
