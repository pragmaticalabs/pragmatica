# Session Handover — 2026-06-08c (06-deployment robustly GREEN: 3 fixes shipped; #126 member-model REFUTED by direct evidence — real 02-restore pin is community/spokesman + a scale-config 500)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `c790a3b78` · tree clean.
**Origin:** behind HEAD; **NOTHING PUSHED this session** — push not requested. The 3 fixes below + the prior backlog are unpushed.

## TL;DR
Three deployment-plane bugs were root-caused, fixed, Docker-validated, and committed — **06-deployment went from flaky/red to robustly green (10/10 cluster-A)**. Then #126 (the 02-chaos `restore_cluster_baseline` 180s quiesce stall) was attacked three ways and **all three failed**, because a focused diagnostic **conclusively refuted the premise**: the stall is **not** a never-stable/never-READY *member* problem. The member-health component is clean; quiescence is pinned by the **community/spokesman** verdict (and a `scale → 500 "No cluster configuration stored"` during restore). All #126 code was reverted. The real root needs a fresh, properly-scoped investigation of the community-quiescence path.

---

## SHIPPED this session (3 commits on rc1, UNPUSHED)

| Commit | Issue | Fix |
|---|---|---|
| `228e7e024` | #124 | **register-only publish must not activate** — `ClusterDeploymentState.shouldSuppressActivation` now suppresses the `SliceTargetValue` Put unconditionally for `registerOnly` (was gated on a racy existing-target read → first publish activated the version → blue-green deploy hit "already active"). |
| `337fbc006` | #127 | **name-keyed blueprint identity + restore-prior-on-failed-upgrade** — `hasConflictingOwnership` compares by blueprint base (no self-conflict across versions); `capturePreviousBlueprint` sources the prior active version from KV (blueprint base, not slice base); `restorePreviousBlueprint` keys off `previous.id()`. A failed ALL_OR_NOTHING upgrade restores the prior version instead of wiping; no-previous still clean-wipes. Adds `BlueprintId.base()` + `blueprintId(Artifact)`. 7 unit tests. |
| `c790a3b78` | #129 | **slice intrinsic-config composite materialized synchronously before factory invoke** — `DependencyResolver` moved `materializeComposite` from an async `onSuccess` (which raced the sync `flatMap` factory-invoke) into a synchronous `materializeThenResolve`. Kills the intermittent per-node `Config section not found: click-events` (the factory was resolving against the global node-composite before the slice intrinsic was attached). |

**Validation:** full 15-suite = **10/15** (all 10 cluster-A green incl. 06 5p/0f; cluster-B 02/03/05/12/13 fail — all pre-existing/separate roots: #94, #93/scale-down, #95, cascade). 06 went 4p/1f→5p/0f→robustly green across the 3 fixes; #129 fix run showed **0** `Config section not found` cluster-wide + 06 5p/0f + no regression.

### Dead-ends burned on the way (each disproved by evidence — don't repeat)
- #124 "read-after-write across DEPLOYMENT/STRATEGIES owners" → **refuted** (ownership is leader-pinned = same node; `cluster.apply` applies-then-resolves). Real bug was register-only-activates.
- #128 "pubsub-namespace refactor regressed config resolution" → **refuted** (resolver is verbatim exact-match, untouched by the 5 pubsub commits). Then "build skew" theory also **refuted** (m2 artifact was already bare `click-events`; harness pushes from `~/.m2`, never stale `target/`). Real root was the #129 async-onSuccess race.

---

## #126 — 02-chaos `restore_cluster_baseline` quiesce-180s: MEMBER MODEL REFUTED (reverted)

**The longstanding framing (this handover lineage + mine): "a never-READY/never-stable SUSPECT member ghost re-stamps its SWIM hint faster than the 15s TTL → pins quiescence DEGRADED → 180s timeout." This is WRONG, proven by diagnostic.**

Three approaches tried, all failed (02 stayed 5p/1f, `restore_cluster_baseline` 3× 180s-timeout each run):
1. **FSM-only never-healthy EVICTION** (leader-gated timer evicts SUSPECT members present>budget & never-stably-healthy, via `everStablyHealthy` latch). Docker `[#126-DIAG]`: eviction *works* (node-2 EVICTed 8 genuine never-healthy ghosts; guard-exempt hypothesis refuted) but **unreliable** — fired at presentMs 126s/495s/714s, not 60s, because `firstObservedAtMs` is **per-node** and the sweep is **leader-gated**: under 02 kill-leader churn no stable leader accumulates 60s within the restore window. (`everStablyHealthy` is per-node too → same inconsistency.)
2. **Quiescence-side BOUND** (don't evict; exclude a never-stable long-present SUSPECT member's hint from `healthHints`, mirroring #125's DEAD-exclusion). Also failed (same 3× timeout).
3. **Quiescence-VERDICT diagnostic** (`[#126-QDIAG]` in `MembershipFsm.healthHints()`, logging every non-HEALTHY pinning member with id/hint/state/presentMs/everStablyHealthy/shouldSuppress). **Result: ZERO QDIAG lines** across all cluster-B nodes while restore timed out 3×.

**Conclusive evidence (the redirect):**
- `ClusterGenerationAssembler.evaluateCluster` (aether/node `.../api/routes/ClusterGenerationAssembler.java:216`) sources member-health from `fsm.healthHints().values()` — confirmed the await-quiesced verdict IS on the healthHints path.
- Zero QDIAG ⇒ `healthHints()` returned **all-HEALTHY on every poll** ⇒ the **member-health component is QUIESCED**.
- Yet the verdict stayed DEGRADED/CONVERGING. `ClusterQuiescenceEvaluator.evaluateCluster`'s only other DEGRADED/CONVERGING sources are **community state (governor announcements) and `pendingSpokesmanRebalance`**. → **the pin is the community/spokesman component, not members.**
- Second smoking gun: `restore_cluster_baseline` hit `POST /api/cluster/scale → 500 "No cluster configuration stored"` during restore (run.log:409 of the QDIAG run) — leaves governor/community state unsettled.

**NEXT (fresh, properly-scoped session):** investigate the community-quiescence path, NOT membership. Instrument `ClusterGenerationAssembler.evaluateCluster` / `communityStates(governors)` / `countPendingSpokesmanRebalance` (aether/node `ClusterGenerationAssembler.java:213-249`) + `ClusterQuiescenceEvaluator.evaluateCommunity` to log the DEGRADED/CONVERGING `detail` + which governor/community pins it during a failing restore. Separately root-cause the `scale → "No cluster configuration stored"` 500 (a `ClusterConfigKey` not stored / not yet propagated when restore scales to 5). Both are in the deployment/generation + cluster-config layers, not SWIM/membership. **All #126 member-eviction/suppression code was REVERTED** (it addressed the wrong component).

---

## Open backlog (cluster-B, all pre-existing/separate)
- **#126** (redirected): community/spokesman quiescence + scale-config-500 (above).
- **#94**: NODE_FAILED-within-60s under load + 12-network READY-convergence (SWIM-detect latency). The counted 02/12 failures.
- **#93**: drain-budget returns 500 instead of 409.
- **#95**: 05-security needs a secure-mode cluster-B variant (runs under `AETHER_INSECURE_DEV_MODE`).
- **#91**: physical-node-drain DHT durability. **#97**: #96 budget-stress integration suite.

## Pending chore (deferred)
**Reformat-all-sources + enable formatting** was gated on #126 green — **deferred** (#126 not green). When done: run the JBCT formatter, verify **idempotent** (2nd pass = zero diff), **spot-check it doesn't mangle** (`///` javadoc / `//` comments / multi-line `if` — see `project_jbct_formatter_bugs` memory), commit all in one go, then full suite. NOTE: do NOT run `jbct-maven-plugin:process` as a diagnostic — it reformats the whole module (bit us this session; memory updated).

## State / environment
- HEAD `c790a3b78`, tree clean, 3 session fixes + prior backlog UNPUSHED. The shaded `aether-node.jar` is STALE (built with reverted #126/QDIAG) — rebuild before any use (`mvn -pl aether/node -am install -DskipTests`).
- Clusters were cleaned post-#126-runs (verify `docker ps` empty before next run). Env vars `$TARGET_HOST`/`$AETHER_SSH_KEY`/`$AETHER_SSH_USER`/`$AETHER_API_KEY` set; reference by name.

## Key learnings
- **Verify which COMPONENT pins a composite verdict before fixing it.** #126 burned multiple sessions on a member-health model; one QDIAG in `healthHints()` showed members were never the pin. Instrument the verdict's actual inputs first.
- **Per-node temporal state (firstObservedAtMs, everStablyHealthy) is unreliable under leader churn** for any leader-gated decision — eviction fired at 126–714s, not the 60s budget. Cluster-consistent state (ping-broadcast) would be required, but only if membership were the pin (it isn't).
- **Background agents that run a remote suite leak completion-poller shells** (`until/while pgrep … sleep`). Run long suites via **Bash `run_in_background`** (harness-managed, re-invokes on exit) — NOT via an agent that re-polls. Killed 4 leaked pollers this session.
- **`jbct-maven-plugin:process` reformats the whole module** even when build-format is disabled — never use it as a lint diagnostic (recorded in `project_jbct_formatter_bugs`).
- Each fix this session exposed the next real defect; verifying-before-acting caught 3 wrong turns (read-after-write, build-skew, slice-base-vs-blueprint-base) before they became wasted code.
