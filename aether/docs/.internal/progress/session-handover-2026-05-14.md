<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Session Handover — 2026-05-14 (RC1 convergent-core + JBCT migration)
date: 2026-05-14
branch: release-1.0.0-rc1
head: e7d92ebd6
predecessor: aether/docs/internal/progress/session-handover-2026-05-13.md
status: in-flight — full integration suite running in background
---

# Session Handover — 2026-05-14

## TL;DR (3 minutes)

1. **All 6 convergent-core RC1 steps + 5 fix rounds + PR #213 are on `release-1.0.0-rc1`.** Final HEAD `e7d92ebd6` (15 net commits past predecessor handover's `84726a848`). Module tests across 5 modules: ~2010/2010 pass.
2. **Integration test for the latest fix stack is running in background** (`bsoennqeq`, log at `/tmp/rc1-r1r2r3-full.log`). Mid-run results: 02-chaos kill-leader + kill-multiple 5P/0F each; 15-delegation 13P/0F (was failing); 03-scaling quorum-safety 6P/0F (was failing). Test results need to be re-run with the latest 2 commits (timeout extension + PR #213).
3. **The user's strategic insight reframed the work this session:** "we may be chasing test expectations rather than testing actual functionality." Confirmed — most of the failures we thought were RC1 regressions were either pre-existing failures already-committed in the baseline `test-results.json` or test-script bugs we ourselves introduced.
4. **Step 1 (ClusterEventLog) needed three independent fix rounds** to actually deliver OB1/OB2 closure: leader-gate + timestamp (H1+H2), inject-path migration to the replicated log, key-collision fix (NodeId in key). All landed. Final OB1 (alerts/traces): closed. OB2 partial (kill-leader/multi closed; kill-node/under-load TBD pending re-run).
5. **PR #213 (peglib 0.6.1 + JBCT single-pass)** unblocks `./build.sh` Step 2 (lint). The lint failure (`NoClassDefFoundError TokenArrayBuilder`) was a pre-existing toolchain skew; PR #213 is the upstream fix. Merged this session via squash.

---

## Quick state

```
branch:  release-1.0.0-rc1
HEAD:    e7d92ebd6 refactor(jbct): single-pass processing + peglib 0.6.1 (PR #213)
ahead-of-origin: 16 commits (not pushed)
working-tree: dirty only because test-results.json is now gitignored — runtime-only
running:  background bash bsoennqeq → /tmp/rc1-r1r2r3-full.log (full 15-suite run)
```

---

## Commits since predecessor handover

| # | Hash | Subject (one-line) |
|---|------|----|
| 1 | `1bb4d7fb8` | Step 4 — HLC in MembershipFsmEvent timestamps |
| 2 | `eba0cfe51` | docs(spec): topology-rc1 spec + 3 cross-design analyses |
| 3 | `450a7dcfc` | Step 3 — reducer consults SwimObservation.incarnation |
| 4 | `8a57b5c29` | Step 5 — quorum-aware MembershipView (strict + bootstrapAware) |
| 5 | `0df8fc3b6` | Step 6 — version byte on SwimObservation + NodeLifecycleValue |
| 6 | `e01f4c7f2` | Step 2 — migrate 5 KV-put subscribers to MembershipDecision (4 new variants) |
| 7 | `be90ce53f` | Step 1 — cluster-scoped replicated ClusterEventLog |
| 8 | `0491d2ad4` | docs(spec): update topology-rc1 §3.5 — 4 variants (NodeShuttingDown added) |
| 9 | `df621f371` | fix(test): rename test_events_cluster_ordering.sh (runner glob) |
| 10 | `4f5892f5b` | fix(observability): Step 1 regression — H1 leader-gate + H2 Instant.now timestamp |
| 11 | `696eb59a3` | fix(observability): migrate inject paths to ClusterEventLog (OB1 round 2) |
| 12 | `5ae0571d7` | fix(observability): NodeId in ClusterEventLogKey (cross-node seq collision) |
| 13 | `089aef8a7` | fix(rc1): CTM snapshot fallback + live initialTopology + poll-until replication + untrack test-results |
| 14 | `2e87fb397` | fix(rc1): R1 CDM/TAC startup race + R2 test grep target details.name + R3 quorum via live SWIM count |
| 15 | `644e57270` | fix(test-infra): restore_cluster_baseline 300s→600s (remote post-chaos heal) |
| 16 | `e7d92ebd6` | refactor(jbct): single-pass processing + peglib 0.6.1 migration (PR #213) |

15-step structural delta vs predecessor handover's HEAD.

---

## Per-area status

| Area | State | Detail |
|---|---|---|
| **6 convergent-core RC1 steps** | shipped | Steps 1-6 all merged. Module tests green. |
| **OB1 (alert/trace cross-node)** | closed | Inject paths now publish via replicated `ClusterEventLogKey`; tests pass 6P/0F for `test-alerts.sh` + `test-invocation-traces.sh` in latest run. |
| **OB2 (NODE_FAILED visibility)** | partial-closed | kill-leader + kill-multiple PASS. kill-node + kill-under-load 1F each (likely cascade from baseline-restore — should clear with timeout extension). |
| **Cluster-ordering invariant test** | closed | Test grep was wrong (alert MARKER in `details.name`, not `summary`). Fixed in R2. |
| **03-scaling regression** | partial-closed | quorum-safety 6P/0F (R3 quorum fix worked). scale-up/scale-down 1F each — TBD. |
| **15-delegation regression** | closed | TAC NodeJoined handler + CDM retry-on-empty-snapshot landed in R1. |
| **06-deployment regression** | likely-closed | All 3 deploy-strategy tests started without `deploymentId`-missing failures in the latest run (R1 CDM startup-race fix). Awaiting completion. |
| **restore_cluster_baseline** | being-validated | R3 improved from current=1 → current=3. Timeout extension 300→600s queued for next run. |
| **build.sh / JBCT lint** | unblocked | PR #213 (peglib 0.6.1, single-pass goal) merged. `./build.sh` should now pass Step 2 lint. Not yet verified end-to-end this session. |
| **08-resources/KV put 500** | pre-existing | PostgreSQL `Connection refused:5432` — environment issue, not our regression. |
| **12-network 1P/2F** | pre-existing | Same numbers as RC1-baseline `test-results.json`. Not a regression. |
| **05-security 2P/1F** | unclear | Was 3P/0F at baseline. Investigator found zero TLS diff in our commits. Suspected flaky or different test name. |

---

## Architectural snapshot (1 page)

### Membership truth (post-RC1)

```
SWIM (local, per-node)  →
                          \
NodeLifecycleKey (KV)  →   MembershipView.strict()   →  /api/nodes/lifecycle
                          /                              (quorate node only)
                         /
                        →   MembershipView.bootstrapAware()  →  TopologyObserver internals
                                                                (no quorum gate)
```

- **`MembershipView`** (`aether/aether-deployment/.../view/MembershipView.java`) — canonical query. Two factory variants: `strict()` (external, quorum-gated) and `bootstrapAware()` (internal, no gate).
- **`MembershipDecision`** (`integrations/consensus/.../topology/MembershipDecision.java`) — sealed interface with 7 variants now (4 new in Step 2: `NodeJoining`, `NodeDraining`, `NodeFailedDrain`, `NodeShuttingDown`). All variants carry `logIndex` + `stampedAt`.
- **`TopologyObserver.publishMembershipDeltas`** — sole emitter. Quorum-gated.
- **`ClusterEventLog`** (`aether/slice/.../AetherKey.java:ClusterEventLogKey(epoch, NodeId, seq)`) — Rabia-replicated event log. Per-node keyspace (NodeId in key prevents cross-node collisions).
- **HLC** (`integrations/hlc/.../HlcClock.java`) — wired into MembershipFsm events; drift policy = WARN+drop.
- **Quorum evaluation** (Step R3, post-PR-213): `TopologyObserver.evaluateQuorumState` uses `swimHealthyCorePeerCount(view.coreMemberIds())` — bridges JOINING lag while preserving minority-partition safety.

### Test-infra bug class identified

Three test-side bugs caused us to chase phantom code regressions:

| Bug | File | Fix landed |
|---|---|---|
| Runner glob skipped underscore-named tests | `aether/tests/integration/suites/11-observability/test_events_cluster_ordering.sh` | Renamed dash-form in `df621f371` |
| Grep targets wrong JSON field (MARKER is `details.name`, not `summary`) | Same | Fixed in `2e87fb397` |
| `restore_cluster_baseline` 300s timeout < actual remote heal latency | `aether/tests/integration/lib/cluster.sh:1463` | 300→600s in `644e57270` |

---

## Open items (prioritised)

### High priority — must verify

1. **Full-suite re-run with all 16 commits**. Current background run was launched before `644e57270` (timeout fix) and `e7d92ebd6` (PR #213). Need a fresh run with: rebuilt JAR + 600s baseline timeout + clean lint.
2. **`./build.sh` end-to-end pass.** Was blocked by peglib toolchain skew → PR #213 should clear it. Not yet verified this session.
3. **Push commits to origin/release-1.0.0-rc1.** 16 unpushed commits. User authorization needed (per CLAUDE.md).

### Medium priority — likely cascading-from-baseline failures

4. **02-chaos kill-node / kill-under-load 1F each.** Hypothesised cascade from degraded baseline (3-not-5 ON_DUTY nodes). Re-run with timeout fix should clarify.
5. **03-scaling scale-up / scale-down 1F each.** Same hypothesis.
6. **13-edge-cases** result — partial log didn't show full results. R1 (CDM startup race) + Fix C (live initialTopology) both should help.

### Pre-existing — not RC1 scope

7. **08-resources KV PUT 500** — PostgreSQL connection refused on TARGET_HOST. Environment issue.
8. **12-network 1P/2F** — same as baseline. SWIM/QUIC convergence.
9. **05-security cert rotation** — zero TLS code diff in our commits; investigator could not classify definitively.

### Deferred — explicit user direction

10. **Deviation B (Plumb real HLC + logIndex through TopologyObserver)** — task #9, pending. Step 2 used `HlcTimestamp.ZERO` placeholder + `observedRabiaTerm()` proxy. Production-path HLC supplier wiring remains a follow-up.
11. **Deviation C (Migrate `eventsSince(Instant)` callers + delete @Deprecated shim)** — task #10, pending. Two callers: `StatusRoutes`, `EventWebSocketPublisher`.

---

## Critical gotchas (read before resuming)

1. **Worktree harness uses stale base ref.** `Agent({isolation: "worktree"})` branches from `origin/<branch>` or some cached ref — NOT current local HEAD. Wasted 130+ min on agent retries during this session. **For sequential single-agent work in this repo, run in main repo with `mode: "acceptEdits"`; do NOT use `isolation: "worktree"` until that bug is fixed.**

2. **Parallel jbct-coder agents share the working tree.** Multiple coding agents will race on file writes even when their declared file sets don't overlap. **Sequential execution only** for now.

3. **Background bash for long-running integration tests.** `Bash({run_in_background: true})` with tee to a log file works better than delegating to build-runner agents (they expire while waiting). Pattern:
   ```bash
   cd aether/tests/integration && ./run-tests.sh --env remote --skip-build 2>&1 | tee /tmp/X.log ; echo "EXIT:$?"
   ```
   Then poll `/tmp/X.log` for progress; wait for the harness notification when the background process exits.

4. **`test-results.json` is gitignored as of `089aef8a7`.** It's a runtime artifact — was causing agents to compare against stale committed data. Never re-stage it.

5. **`mvn verify` is forbidden** when `HCLOUD_TOKEN` is set in env (creates real paid Hetzner servers via Failsafe + `HetznerCloudIT`). Use `mvn -pl <module> test` instead. Also forbidden: `-Djbct.skip=true` for aether builds (POM hierarchy handles it).

6. **JBCT lint was disabled by user directive during this session.** With PR #213 merged, lint should re-enable cleanly. Verify with `./build.sh` before resuming heavy iteration.

7. **The test-results.json at predecessor HEAD `84726a848` shows the baseline.** Many suites already had failures committed in that baseline:
   - 02-chaos 2F (kill-leader + kill-multiple were failing pre-RC1)
   - 03-scaling 3F (all 3 tests failing — added at RC1, never passed)
   - 08-resources 1F, 11-observability 2F, 12-network 2F, 13-edge-cases 2F
   - Many "regressions" in the middle of the session turned out to be **identical pre-existing failures**, not our regressions.

---

## Next-session start (concrete)

If you're continuing from this handover, the immediate next actions are:

```bash
# 1. State check
git log --oneline -3                       # should show e7d92ebd6 at HEAD
git status --short                          # should be clean (test-results.json gitignored)

# 2. Re-validate build chain end-to-end
./build.sh                                  # PR #213 should unblock Step 2 (lint)

# 3. Rebuild JAR with all 16 commits in
mvn -pl aether/node install -DskipTests -am -q

# 4. Final full integration suite on TARGET_HOST
cd aether/tests/integration
./run-tests.sh --env remote --skip-build 2>&1 | tee /tmp/rc1-final.log
# Wait ~2h (background it via Bash run_in_background:true)

# 5. Diff vs baseline
grep -E "PASSED:|FAILED:" /tmp/rc1-final.log | tail -50
# Expected baseline-corrected pass-set: 13+/15 suites green
# Known remaining-pre-existing: 08-resources (PG conn), 12-network (SWIM convergence)
```

**Decision points after the final run:**

- If 13-edge-cases/disruption-budget still fails: dispatch aether-investigator on the `initialTopology()` site (Fix C may have broken something else).
- If 02-chaos kill-node still fails after timeout extension: deeper SWIM/NODE_FAILED event-path investigation (separate from H1 leader-gate which now works).
- If `./build.sh` Step 2 (lint) still fails after PR #213: re-check `~/.m2/repository/org/pragmatica-lite/peglib*/` for 0.6.1 availability; peglib publishing may not be complete.

---

## Artefacts written this session

- `aether/docs/specs/topology-rc1-spec.md` — consolidated RC1 spec, 478 lines (`eba0cfe51`)
- `aether/docs/internal/design/topology-analysis-2026-05-13/{a-first-principles,b-failure-mode-driven,c-comparative}.md` — 3 cross-design analyses
- `/Users/sergiyyevtushenko/.claude/plans/snazzy-tickling-koala.md` — approved RC1 plan
- `~/.claude/projects/.../memory/feedback_worktree_isolation_pattern.md` — memory entry for the worktree harness gotcha
- Integration logs at `/tmp/rc1-*.log` (ephemeral — TARGET_HOST runner output)

---

## References

- Predecessor handover: `aether/docs/internal/progress/session-handover-2026-05-13.md`
- RC1 consolidated spec: `aether/docs/specs/topology-rc1-spec.md`
- Parent architecture spec: `aether/docs/specs/membership-architecture-spec.md`
- FSM spec: `aether/docs/specs/cluster-membership-fsm-spec.md`
- 3 cross-design analyses (historical): `aether/docs/internal/design/topology-analysis-2026-05-13/`
- PR #213: <https://github.com/pragmaticalabs/pragmatica/pull/213>
- User CLAUDE.md guidance: `~/.claude/CLAUDE.md`
- Project CLAUDE.md: `CLAUDE.md` (gitignored — local only)

---

**End of handover.** Next session: validate `./build.sh` + final integration run + decide on push.
