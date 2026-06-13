# Session Handover — 2026-06-12

**One-line state:** Foundation work (cluster-topology-overhaul) is essentially landed; #325 ROUTING-wedge fix + docker app-port fix are coded, unit-green, JAR-built, and **the very next action is the suite-02 remote re-gate** (expected 6p/0f). After that: commit → full-15 ×3 → history rewrite → cut RC1 tag → draft multi-RC roadmap. The release process plan below is **ratified by the user** — follow it.

---

## GOAL

### Immediate goal
Finish the cluster-topology-overhaul foundation → **pass 15/15 full integration suite on Hetzner** → cut **v1.0.0-rc1**.

### Strategic decisions ratified this session (do not re-litigate)
1. **Foundation first, then stop.** We are grinding THE foundation; everything else is meaningless without it. Pass 15/15, then stop spending time on the topology track.
2. **RC1 scope = foundation/consensus validated + 15/15 Hetzner green.** Release RC1 **as soon as 15/15 passes**. The ~50 open RC1-labeled issues are **NOT** RC1 blockers — they go to RC2/RC3.
3. **RC1 ships with an explicit scope banner** (this is the audit's own "scope honesty" + "default security posture" findings, not caution): *single-trust-domain only, security OFF by default, not production-hardened.* **Verify the insecure default is LOUD** (startup warning + README), not silent. Security blockers #282 (unauth Maven code-push into cluster) + #290 (mgmt plane open by default) become the **RC2 hard gate**, not RC1.
4. **Multi-RC roadmap, parallel-buildable.** Partition the ~50 open issues into a few RC steps. Ordering principle: **one-way-door FIRST between buckets** (irreversible: API version-prefix #300, security defaults, wire/config-key, stream-durability decision), **non-interfering WITHIN buckets** so agents build in parallel (worktree isolation). Anchor on the **PR#326 audit docs** (now merged). Mint tickets for audit-gaps-without-issues (audit explicitly flags **version skew as unticketed**).
5. **History rewrite before the RC1 tag.** Collapse the 1429 rc1 commits into ~15–25 cohesive subsystem/deliverable commits — logical grouping, not chronological replay. Details in the sequence below.

---

## CURRENT STATE

- **Branch:** `release-1.0.0-rc1`. Local HEAD `adc948d19` (pushed).
- **#326 MERGED** to `origin/release-1.0.0-rc1` (merge commit `c459c1298`, base was the release branch not main). Docs-only audit sweep. **Local branch is 1 commit behind origin** (the #326 merge) — **NOT pulled yet** because of uncommitted work in the tree. Pull happens at rewrite time (folds the #326 docs into the `docs:` group).
- **`main` == `03fe57bb4 Release 1.0.0-alpha`**, exact ancestor of HEAD, 0 divergence, **1429 commits** on top. So `git reset --soft main` == `reset --soft 03fe57bb4` lands precisely on the alpha base.
- **Fresh node JAR:** `aether/node/target/aether-node.jar`, built 2026-06-12 00:04, 53,900,730 bytes (~51 MB). **Includes all uncommitted fixes** incl. the docker app-port publication.
- **Unit tests green:** aether-deployment 572, aether-invoke 189, aether-environment-docker 48. Zero failures.
- **Orphan-run pre-flight: CLEAN** (no run-tests.sh / surefire / mvn at last check).

### Uncommitted work in tree (the "#325 batch")
Modified (main): `ClusterDeploymentState.java`, `NodeDeploymentState.java`, `HttpRoutePublisher.java`, `DockerComputeProvider.java`
Modified (test): `ClusterDeploymentStateActiveTest.java`, `DockerComputeProviderTest.java`
Modified: `aether/tests/integration/lint-baseline.txt`
New (main, the move-only CDS split): `CommunityPlacementPlanner.java`, `SliceAllocationEngine.java`, `StaleEntryCleaner.java`, `StuckTransitionalRemediator.java` (all under `aether-deployment/.../cluster/fsm/`)
New (test): `NodeDeploymentStateSeedEpochAckTest.java`, `HttpRoutePublisherConsensusRetryTest.java`, `StubRouteHandlerFactory.java`, `aether-invoke/src/test/resources/META-INF/services/org.pragmatica.aether.http.handler.HttpRequestHandlerFactory`

### What the #325 batch actually fixes (validated — S20 self-drain went 7 PASSED / 0 FAILED)
- **Defect 1 (HttpRoutePublisher):** naked `cluster.apply` returned a never-resolving Promise → ROUTING never reached ACTIVE. Fixed with `applyWithRetry`: `cluster.apply(commands).timeout(CONSENSUS_OPERATION_TIMEOUT=30s).mapToUnit().orElse(() -> retryApply(...))`, `CONSENSUS_MAX_RETRIES=2` (3 attempts total), inline `Causes.cause("...timed out after N retries").promise()` on exhaustion. Matches NodeDeploymentState idiom verbatim.
- **Defect 2 (StuckTransitionalRemediator, extracted class):** ROUTING was dropped by the `default -> {}` arm. Now `case LOADING, ACTIVATING, ROUTING -> resetStuckLoadingSlice(...)`; the `transitionalStateTimestamps().remove(sliceKey)` was relocated **inside** the handled arms so unhandled states stay tracked (re-WARN behavior).
- **Epoch.ZERO fix (NodeDeploymentState.seedEpochAckExpectationForRoute):** the seeded path used `value.observedCoreEpoch()` which is STRICTER than the live-registration path's `Epoch.ZERO`; a lagging acker failed `isAtLeast` → seeded slice wedged in ROUTING → remediator force-unloaded a healthy slice. Now seeds with `Epoch.ZERO`.

### Docker app-port fix (this session, in the fresh JAR)
`DockerComputeProvider.java:332-341` — under `if (config.exposeHostPorts())` now publishes BOTH `-p 8080` (mgmt) AND `-p 8070` (app) to ephemeral host ports (was only 8080). **Root cause:** CTM-provisioned ULID replacements published only the mgmt port; once ALL compose seeds were replaced (which happens mid-suite-02), no host-mapped app port existed anywhere → kill-under-load's `retarget_app_endpoint_to_active_slice` found the echo owner but `host_port_for_container "$owner" 8070` returned empty → load probed a dead port → 174× status-000, 100% error → false FAIL. With 8070 published, the harness's `docker port` discovery resolves it (no harness change needed). Test asserts both ports present when `exposeHostPorts` true, neither when false (48 tests).

---

## EXACT NEXT STEP (resume here)

**Verification already done & POSITIVE:** `--skip-build` skips ONLY `build.sh` (run-tests.sh Step 1). It does **NOT** skip the image ship/rebuild — `deploy_docker` (Step 2) at run-tests.sh:458-473 `remote_scp`s the local `node/target/aether-node.jar` + Dockerfile + aether.toml to `~/aether-build/...` and runs `docker build --no-cache -q -t aether-node:local` on the remote. So the fresh 00:04 JAR (with the docker fix) **will** reach the tested image. (The flag that would reuse a stale remote image is the SEPARATE `--skip-image-push` — do NOT use it.)

**→ Launch the suite-02 re-gate:**
```
cd /Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/tests/integration
./run-tests.sh --env remote --skip-build --skip-teardown --suites 02
```
- **Long (~10–20 min). Run as detached background Bash (`run_in_background: true`)** with output redirected to a log file — a single foreground Bash call cannot exceed the 10-min tool cap. Read only the final summary + the kill-under-load forensics from the log (protect context).
- **Expect 6p/0f.** S20 ROUTING wedge already green last run; the only prior failure (Kill_node_during_active_load) should now pass. Watch: error rate should drop from 100%; `retarget_app_endpoint_to_active_slice` should resolve a non-empty app host port (grep the load-forensics / `RETARGETED_SLICE_OWNER` / `host_port_for_container` lines).
- Don't mask stderr (`2>/dev/null` is a known trap). Capture both streams to the log.

---

## SEQUENCE AFTER 6p/0f (the rest of the locked plan)

### Step A — Commit the #325 batch (task #4)
Commit it **functionally, single commit, don't over-polish** — the final history is authored in the rewrite, so intermediate message quality doesn't matter. Single-line message, conventional prefix, **no trailers / no Co-Authored-By**. Include all 4 modified-main + 4 new-main + 4 new-test + 2 modified-test + lint-baseline.txt. Suggested: `fix(deployment): #325 ROUTING wedge (route-publish timeout+retry, stuck-remediator ROUTING arm, epoch-zero seed) + docker app-port parity`.

### Step B — Full-15 ×3 (task #5)
- **RECREATE CLUSTER A FIRST.** There is a zombie `aether-a-node-4` (was at :5154) that re-fails 07-cluster-mgmt + 11-observability if not recreated.
- **Cluster A recreation MUST go through run-tests.sh WITHOUT `--skip-teardown`** (so teardown+fresh-bring-up runs). **Direct ssh `docker compose down -v` teardown of cluster A was classifier-DENIED earlier — do NOT retry that path.**
- Full run command (all suites, teardown enabled so A is fresh): `./run-tests.sh --env remote --skip-build`. Run ×3 for stability. The 3× includes the split no-double-active + Wave-2 worker-join regression checks.
- Re-run orphan-run pre-flight (`pgrep -fl run-tests.sh / surefire / mvn`) before each remote run.

### Step C — History rewrite (task #7) — LAST, on green, before the tag
Lifecycle (each gate must pass before the next):
1. `git fetch origin && git merge origin/release-1.0.0-rc1` (or otherwise pull) so the **#326 docs are in the working tree** — else the force-push drops them.
2. `git branch backup/pre-cleanup-rc1` — **LOCAL ONLY, NEVER pushed.** This is the parachute.
3. `gh pr list --base release-1.0.0-rc1` pre-flight — confirm no open PR pins the old tip (only #326 did; now merged → should be clear). Verify, don't assume.
4. `git reset --soft main` (== `03fe57bb4`, the alpha base).
5. Re-commit ~15–25 **cohesive subsystem/deliverable** groups. Taxonomy: `core` (Result/Option/Promise) · `integrations` (consensus, swim, storage, db, http) · `jbct` toolchain · aether → membership/FSM · transport/SWIM · election/consensus · deployment/CDM · storage/pg · cloud · cli · node/management · dashboard · e2e/integration tests · `docs` (incl. #326 audits). Single-line messages, conventional prefixes, no trailers.
6. **Assert `git diff backup/pre-cleanup-rc1 HEAD` is EMPTY** — byte-identical-tree proof that the rewrite lost zero code across 1429 commits' delta. (Interactive rebase is blocked in this env; `reset --soft` is the right tool. This empty-diff invariant is what makes it safe at scale.)
7. Force-push the branch (`git push --force-with-lease`).
8. Re-point the moving tag `v1.0.0-rc1-candidate` to the new clean HEAD (force-update + force-push tag) — its old target goes unreachable (part of the clean break).
9. **Show the user the new commit list**, then delete `backup/pre-cleanup-rc1` + `git gc --prune=now`. Backup is **never pushed** and is deleted only after tree-equality passes AND force-push succeeds AND user has eyeballed the list.

### Step D — Cut v1.0.0-rc1 tag (task #8)
On the clean HEAD, with the scope banner (see GOAL #3). Verify the insecure-default is loud first.

### Step E — Draft multi-RC roadmap (task #9) — after the tag
Anchor on the merged #326 audits in `aether/docs/internal/audits/`:
- `design-completeness-assessment-2026-06-10.md` — layer scorecard + top-10 ranked gaps + 5 meta-patterns
- `zoom-out-summary-2026-06-11.md` — one-way-door triage; named gaps: version skew (unticketed), signal trustworthiness, stream durability, default security posture, scope honesty, day-2 runbooks
- `operator-surface-assessment-2026-06-11.md` (→ #285–313), `resource-surface-assessment-2026-06-11.md` (→ #268–283), `streaming-deep-assessment-2026-06-11.md` (→ #260–267)
Delegate the audit digestion read-only; synthesize the RC2/RC3/… partition yourself (one-way-door-ordered between buckets, non-interfering within, missing-tickets flagged). Open-issue clusters for reference: Security (#282,#290,#289,#295,#299,#313,#287) · Dashboard (#291-294,302-305,312 ~11) · Cloud (#296-298,306,307) · Interceptors/Facades/PubSub (#273-281) · CLI/Mgmt-API (#300,301,308,309,311) · Docs EPIC #314 (#315-324). Adjacent unfixed foundational bug worth early attention: **#284 (CDM deploy retry storm flooding consensus log)** — same family as #325, context cheap to reload. Note #325/#284 carry only `bug` label, not `rc1`.

---

## CONSTRAINTS (verbatim — violating these has caused real incidents)
- **`env -u HCLOUD_TOKEN` on EVERY mvn invocation.** NEVER run `mvn verify` when `HCLOUD_TOKEN` is set — failsafe picks up `HetznerCloudIT` and creates a **real paid Hetzner server**. Module tests: `mvn -pl <module> test`.
- **NEVER `-Djbct.skip=true` for aether builds** — POM hierarchy handles it.
- **Never inline** `$TARGET_HOST` / `$AETHER_SSH_KEY` / `$AETHER_SSH_USER` — they're exported; reference by name only.
- **Git:** single-line commit messages, conventional prefixes (`feat:`/`fix:`/`refactor:`/`chore:`/`docs:`/`test:`), **NEVER Co-Authored-By or any trailer**. Commit directly to the release branch (no feature branches on a release branch).
- **Backup branch for the rewrite: NEVER pushed; deleted + gc'd after success.**
- **Direct ssh teardown of cluster A is classifier-DENIED** — recreate via run-tests.sh without `--skip-teardown`.
- **Check orphan runs before any remote/Docker work** (`pgrep -fl run-tests.sh` / `surefire` / `mvn`) — a 2-day-old orphan once starved all builds.
- **Delegate verbose work** (build-runner for maven, agents for investigation/test-runs); keep main context for synthesis + targeted edits. User re-emphasized "delegate as much as possible" twice.

## Known accepted trade-off
`HttpRoutePublisherConsensusRetryTest`'s never-resolving-apply test takes ~90s wall-clock (timeout constants are `private static final`, no injectable seam without a main-source change). Flagged as a possible RC2 testability seam.

## Task list state
- #4 in_progress — rebuild (DONE) → **re-gate suite 02 (NEXT)** → commit batch
- #5 pending — full-15 ×3 (recreate cluster A first)
- #7 pending — history rewrite (collapse 1429 commits)
- #8 pending — cut v1.0.0-rc1 tag with scope banner
- #9 pending — draft multi-RC roadmap from #326 audits
- #1/#2/#3/#6 completed
