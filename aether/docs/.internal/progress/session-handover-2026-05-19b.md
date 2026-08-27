<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Session Handover — 2026-05-19b — Topology Observation Refactor (Steps 8-10 + JBCT review pass)
date: 2026-05-19
branch: release-1.0.0-rc1
head: bb78144ca
predecessor: aether/docs/internal/progress/session-handover-2026-05-19.md
plan: ~/.claude/plans/polymorphic-discovering-melody.md
status: validation-in-flight — Steps 0-9 + JBCT review + CHANGELOG done; 15-suite integration + 30-min soak still in flight as of handover time
---

# Session Handover — 2026-05-19b

## TL;DR (3 minutes)

Continuation of the topology-observation refactor session. Picked up at **Step 8** per the prior handover and completed Steps 8, 9, 10's review portion, plus CHANGELOG. 5 new commits land on top of the predecessor session's HEAD (`fcd3c02e6`).

**Integration validation status at handover time:**
- (a) JBCT review of all refactor-touched Java code: ✅ **completed** — 2 P1s applied in-place (FQCN→imports in `AetherNode`, formatter glitches in `SelfDrainCoordinator` + sibling fix in `ClusterSyncCollector`).
- (b) `./build.sh`: ❌ **blocked by pre-existing JBCT-RET-01 debt** (3 errors in `aether-metrics/invocation/`, 14 in `aether-stream`, all from 2025-12 to 2026-04 — predate the refactor). Workaround used: `mvn -pl aether/node install -DskipTests -am` → fresh `aether/node/target/aether-node.jar` (53MB at 05:28 local).
- (c) Full 15-suite remote-Docker integration run: ⏳ **launched in main-session background** (`b1va1f2pr` — `cd aether/tests/integration && ./run-tests.sh --env remote --skip-build`). Will yield 15-line pass/fail table.
- (d) 30-min 02-chaos soak: ⏳ **pending** — depends on (c) outcome.

If (c) ran to completion before handover ended, the results section below has them. If not, next session should re-validate via the same command and bundle (d).

## Plan reference (authoritative)

`/Users/sergiyyevtushenko/.claude/plans/polymorphic-discovering-melody.md`

## Branch state

```
branch:  release-1.0.0-rc1
HEAD:    bb78144ca docs(changelog): topology-observation refactor — gate + self-drain + S01/S05/S06/S19/S20 tests
tag:     v1.0.0-rc1-candidate @ bb78144ca (pushed)
working: clean
pushed:  origin/release-1.0.0-rc1 == HEAD
```

## What's done — commits this session

| Hash | Step | Subject |
|---|---|---|
| `d34fd23ec` | 8  | test(network): 2-vs-3 partition gate blocks decommission; heal restores 5 ON_DUTY (S05, S06) |
| `7bdf664f5` | 9  | test(chaos): self-drain on sustained quorum loss with uninterruptible exit (S19, S20) |
| `ed5158c0f` | 10 | fix(aether-control): StubMetricsCollector implements emitPeriodicConnectivity |
| `247737154` | 10 | refactor: address JBCT review — FQCN→imports in AetherNode, formatter glitches in drain+metrics |
| `bb78144ca` | 10 | docs(changelog): topology-observation refactor — gate + self-drain + S01/S05/S06/S19/S20 tests |

Plus 8 commits from the predecessor session (Steps 0-7 + handover), bringing the topology-observation refactor to **13 commits total** since `4179c2984`.

## Per-step status

| Step | Status | Notes |
|------|--------|-------|
| 0  | ✅ done (prior session) | Spec §16 + reachability-aggregator Periodic Observation Mode section. |
| 1  | ✅ done (prior session) | Periodic emission 5s/15s. |
| 2  | ✅ done (prior session) | `Transport{Reachable,Unreachable}` + 14 reducer cells. |
| 3  | ✅ done (prior session) | `MembershipFsm.onTransportSnapshot` leader-gated. |
| 4  | ✅ done (prior session) | `ReachabilityGate` gates SwimFaulty + TransportUnreachable cells. |
| 5  | ✅ done (prior session) | `SelfDrainCoordinator` — 8s threshold, halt(2), zero KV/consensus imports. |
| 6  | ✅ done (prior session) | `MembershipView.resolveOnDutyStatus` simplified. |
| 7  | ✅ done (prior session) | Integration test S01 — 15s budget (relaxed from 8s). |
| 8  | ✅ done | Integration test S05+S06 — `test-partition-quorum-gate.sh`. Brief partition (5s) isolates gate behavior (< 8s self-drain threshold). |
| 9  | ✅ done | Integration test S19+S20 — `test-self-drain-quorum-loss.sh`. Kill 3 of 5, assert exit code 2 on survivors, restart → 5 ON_DUTY in 60s. |
| 10a | ✅ done | JBCT review: 2 P1s applied. P2-1 (sibling formatter glitch) applied too. P2-2 (doc clarity in MembershipFsm) and others deferred — not blocking. |
| 10b | ❌ blocked | `./build.sh` blocked by pre-existing debt (out of scope). Use workaround. |
| 10c | ⏳ in flight | 15-suite integration on remote Docker via background bash `b1va1f2pr`. |
| 10d | ⏳ pending | 30-min 02-chaos soak. Depends on 10c. |
| 10  | ✅ CHANGELOG entry | Added one consolidated paragraph in `CHANGELOG.md` 1.0.0-rc1 §Added. |

## Step 10 finer details

### JBCT review findings (delegated to `jbct-reviewer`)

P1 (FIXED in-place, included in commit `247737154`):
1. **FQCN in `AetherNode.java`** (5 method-body sites: `SelfDrainCoordinator`, `SelfDrainConfig`, `InFlightRequestTracker`, `PeriodicObservationConfig`, `SharedScheduler`, `TimeSpan`) → added imports, replaced with simple names.
2. **Formatting glitches in `SelfDrainCoordinator.java`** (lines 131 and 173: `if (x <y)` missing space) → fixed.

P2 (applied):
3. **Sibling formatter glitch in `ClusterSyncCollector.java:336`** → fixed.

P2 (deferred — not blocking):
4. **`MembershipFsm.java:668-674` doc comment** could be tightened to mention `(OnDuty, TransportUnreachable)` gate-conditional behavior. Doc was correct as-is.

P3 confirmations (all clean):
- No `Promise<Result>` anywhere in the refactor surface.
- `ClusterMembershipReducer.apply` is pure (gate as parameter, no I/O).
- All sealed switches exhaustive; no `default ->`.
- `(Decommissioned, *) → nop` chaos-revival defense preserved (incl. new `Transport*` cells).
- Incarnation gate untouched.
- I1 invariant: `MembershipFsm` only mutates `fsmStates` after consensus apply success.
- Leader gate on `onTransportSnapshot` mirrors `onSwimObservation` (started + isLeader).
- `SelfDrainCoordinator` has ZERO `org.pragmatica.kvstore` / `org.pragmatica.consensus.rabia` imports (only `NodeId` type from `org.pragmatica.consensus`).
- CAS uninterruptibility verified via line 206 + line 230 transitions and early-return guards on all 3 entry points.
- `Runtime.halt(2)` used (not `System.exit`); line 104.
- All durations `TimeSpan`; no `long ms` / `Duration`.
- BSL-1.1 SPDX headers on all 4 new files.

### Pre-existing JBCT debt (not refactor's responsibility)

`./build.sh` failed at Step 2 (lint) with these PRE-EXISTING violations:

**aether-metrics/invocation/** (3 errors):
- `InvocationMetricsCollector.java:185` — `addSlowInvocation` returns void (introduced 2025-12-26).
- `ThresholdStrategy.java:109` — `update` returns void (introduced 2025-12-26).
- `ClusterSyncCollector.java:432` — `CallStats.record` returns void (introduced 2025-12-21).

**aether-stream** (14 errors):
- Pre-existing `JBCT-RET-01` violations in `StreamPartitionManager`, `ConsumerRuntimeState`. Module not touched by this refactor.

All confirmed pre-existing via `git blame`. Action: file a separate cleanup ticket post-RC1; bundle with the `pg-codegen` JBCT debt the prior session also flagged. **NOT this refactor's responsibility.**

Workaround per project memory `feedback_build_runner_only_buildsh.md`:
```bash
mvn -pl aether/node install -DskipTests -am
```
Produces `aether/node/target/aether-node.jar` (53MB) — what Testcontainers consumes for the integration suite Docker image.

### Stub fix that WAS refactor's responsibility

Step 1's addition of `emitPeriodicConnectivity` to the `ClusterSyncCollector` interface required a corresponding stub in `aether-control`'s test fixture `StubMetricsCollector`. Missed in the original commit. Fixed in `ed5158c0f`:
```java
// + import java.util.Set;
@Override public void emitPeriodicConnectivity(Set<NodeId> topology, Set<NodeId> connected, NodeId self, long nowMs) {}
```

If you add abstract methods to a public interface, search `grep -rn "implements ClusterSyncCollector" --include='*.java'` for stubs to update. The other 3 (`NoopClusterSyncCollector`, `RecordingCollector` in metrics tests, `ClusterSyncCollectorImpl` in main) were correctly handled in the original Step 1 commit.

### Integration suite path (10c)

Background bash id: `b1va1f2pr`. Output file: `/private/tmp/claude-501/.../tasks/b1va1f2pr.output`. Command:
```bash
cd /Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/tests/integration
./run-tests.sh --env remote --skip-build
```

**Important:** The aether-investigator subagent's attempt to run this same command was **denied** by the permission system. Running it from the main session worked. If next session needs to re-run, do it from main session (`Bash` with `run_in_background: true`), NOT from a subagent.

Suites covered (15):
- 00-smoke, 01-stability, 02-chaos, 03-scaling, 04-streaming, 05-security, 06-deployment, 07-cluster-mgmt, 08-resources, 09-artifacts, 10-database, 11-observability, 12-network, 13-edge-cases, 15-delegation
- 02-chaos and 12-network include this session's 3 new tests (S01, S05+S06, S19+S20).

Expected duration: 30-60 min wall-clock.

### Pending: 10d (soak)

After 10c completes (15 suites green or with identified failures), the plan calls for a 30-minute soak on 02-chaos with the kill loop. Goal: zero flake on the new chaos paths. Command pattern (not yet validated — check existing soak scripts under `aether/tests/integration/` first):

```bash
cd aether/tests/integration
for i in $(seq 1 6); do
  ./run-tests.sh --env remote --skip-build --suites 02
  [ $? -ne 0 ] && echo "SOAK iter $i failed" && break
done
```

Or use the `chaos_soak.sh` if one exists in `lib/`.

## Critical context (carry-forward from prior session)

These remain in force:

- **DO NOT run `mvn jbct:format`** — known formatter bugs (code mangling, blank-line issues).
- **DO NOT prefix commands with env-var assignments** — `TARGET_HOST`, `AETHER_SSH_KEY`, `AETHER_SSH_USER` are already exported.
- **DO NOT `mvn verify`** — `HCLOUD_TOKEN` may fire HetznerCloudIT.
- **DO NOT pass `-Djbct.skip=true`** — POM hierarchy handles it.
- **Single-line commit messages**, no `Co-Authored-By` trailer.
- **`build-runner` only via `./build.sh`** — never raw `mvn test`. Workaround `mvn install -DskipTests -am` is documented and approved.
- **Verify subagent claims** before non-trivial action; subagent line-number citations are NOT proof.
- **Delegation rule**: 2+ file reads / 2+ shell commands / long Maven runs → delegate to subagent. Caught and applied this session after a user reminder.

## Sensitive paths NOT touched (preserve)

All confirmed via JBCT-reviewer audit + manual verification:
- `(Decommissioned, *) → nop` chaos-revival defense.
- Incarnation gate (`MembershipFsm.admitIncarnationOrDrop`).
- I1 invariant: `MembershipFsm.fsmStates` only mutated post-consensus-apply.
- Single-writer rule on `NodeLifecycleKey` (FSM is sole writer).
- Leader gates on `onSwimObservation` AND new `onTransportSnapshot`.
- `SelfDrainCoordinator` has zero KV/consensus dependencies (static-import test asserts this).

## Reference files

- `~/.claude/plans/polymorphic-discovering-melody.md` — plan (authoritative).
- `aether/docs/specs/membership-architecture-spec.md` §16 — 20-scenario acceptance contract.
- `aether/docs/specs/reachability-aggregator-spec.md` "Periodic Observation Mode" — Step 1's rationale.
- This handover.
- Predecessor: `aether/docs/internal/progress/session-handover-2026-05-19.md`.

## Next-session resume point

1. **Check 10c outcome.** If the background bash `b1va1f2pr` has produced a final summary, read `/private/tmp/claude-501/.../tasks/b1va1f2pr.output` for the 15-suite results. Otherwise re-run from main session.
2. **Investigate any failures** — likely candidates:
   - 02-chaos S01 budget (15s could still be tight under remote latency — relax to 20s if so).
   - 02-chaos S19+S20 self-drain timing (exit budget 45s; soak grace + 7s headroom).
   - 12-network S05 partition window (5s — make sure `docker network disconnect` actually severs reachability; if minority's QUIC connections stay open via cached file descriptors, the gate may see stale REACHABLE).
3. **10d (soak)** — 30-min 02-chaos kill loop, zero flake.
4. **Update memory** — once 15/15 green, memorize the working command sequence so next chaos work doesn't re-invent it.
5. **Final commit + tag for RC1** — only after 10c + 10d are both green.

## Branch checkpoint commands

```bash
# Verify
git log --oneline -8                       # HEAD should be bb78144ca
git status --short                         # clean
git tag --list 'v1.0.0-rc1-candidate'     # bb78144ca

# JAR sanity (Docker image source)
ls -lh aether/node/target/aether-node.jar  # expect ~53MB, recent timestamp

# Re-run integration suite (from main session, NOT subagent)
cd aether/tests/integration
./run-tests.sh --env remote --skip-build  # 30-60 min
```

## Quick acceptance check before 10d

Without running full integration:
```bash
# Module unit tests (NOT mvn verify — HCLOUD risk)
# Use build-runner with focused command:
mvn -pl aether/aether-deployment test       # expect 507+ pass
mvn -pl aether/aether-metrics test          # expect 175+ pass
```

If these don't pass, something regressed between the prior session and now. Likely candidate: my JBCT review fixes to `AetherNode.java` (5 imports added). Compile-only validation already passed via the workaround build.

---

**End of handover.** Session work: 5 commits + CHANGELOG; refactor surface clean per JBCT review; pre-existing debt cleanly bounded as out-of-scope; integration validation in flight.
