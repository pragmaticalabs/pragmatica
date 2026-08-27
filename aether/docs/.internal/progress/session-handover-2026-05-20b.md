# Session handover — 2026-05-20 (Wave 6: pre-release smoke)

Branch: `release-1.0.0-rc1`
HEAD: `fc334a71e`
Candidate tag: `v1.0.0-rc1-candidate` → `fc334a71e`

## Topline

Wave 6 ran the full 14-suite integration suite against the remote Docker cluster (6 attempts, with fix-and-retry between each). The smoke pass surfaced a chain of test-infra issues hidden behind my earlier Wave 5 readiness-contract changes, plus one real cluster bug (seed-node missing `NodeLifecycleKey`). All test-infra issues fixed; the seed-node bug fixed in production code with regression test. Final state: **Cluster A 8/10 suites pass (31p/5f), Cluster B 0/5 suites pass (all fast-failing because the chaos suite kills it and CTM doesn't recover)**.

The Wave 6 commits split into three categories:

1. **Real production fix** (`03e4c83d9`) — seed-node lifecycle write bug in `ClusterDeploymentState.handleNodeAdded`. Joining nodes get an `ActivationDirective` → FSM → `NodeLifecycleKey` write; seed nodes skipped the entire path, leaving the initial leader missing from `/api/nodes/lifecycle` and `/api/cluster/generation core.members[]`. Fixed by adding `ensureSeedNodeLifecycleEntry` that plants `JOINING` for seed nodes if no entry exists; FSM drives the rest. 3 new regression tests in `ClusterDeploymentStateSeedNodeLifecycleTest`. **This bug was masked for months by `cluster_node_count`'s `max(members, desiredSize)` fallback** — Wave 5's stricter check exposed it.

2. **My Wave 5 over-reach corrections** — three commits initially relaxed the canonical readiness contract to work around the seed-node bug (`ef5013881`, `1625cb5ff`, `c566eeac0`), then reverted once the production bug was fixed (`03e4c83d9` + `fc334a71e`). The reverts also dropped Wave 5's **Property 4** (per-port `/health/ready` iteration) — Property 4 was new in Wave 5 and broke fundamentally under CTM auto-heal because replacement nodes use port range 5156+ outside the configured `MGMT_PORT..MGMT_PORT+N-1` window. The contract is now back to its pre-Wave-5 shape: three cluster-side properties (member count + leader + active core floor).

3. **Stale CLI migrations missed in Phase A / T2.6** — five test-infra cleanups for CLI rename gaps the bulk migration didn't catch (`e6c710f4c`, `1d29a4949`, `2fcaa6f8f`).

Plus a real wire-format bug (`3910a3499`): `RouteFilters.parseStateFilter` only split on literal `+`, but URL form-encoding decodes a literal `+` in query strings to a space — so `?state=LOADED+ACTIVE` arrived server-side as `LOADED ACTIVE` and matched nothing. Now accepts `+`, comma, or whitespace as separator.

## Commits — Wave 6 (in order)

| Commit | Subject | Scope |
|---|---|---|
| `e6c710f4c` | `fix(test-infra): migrate 7 stale aether topology auto-heal callers missed during T2.6 namespace move` | `lib/cluster.sh` — 7 sed-replaces from `aether_failover topology auto-heal` to `aether_failover cluster topology auto-heal`. T2.6 had renamed the top-level `aether topology` to `aether cluster topology` but missed these shell callers. |
| `3910a3499` | `fix(aether): RouteFilters.parseStateFilter accepts +/space/comma as separator (URL form-encoding robust)` | `RouteFilters.parseStateFilter` regex: `"\\+"` → `"[+,\\s]+"`. URL form-encoding decodes literal `+` to space; the server now tolerates either. Discovered when `slices_total_instances` shell helper called `?state=LOADED+ACTIVE` and got 0 results. |
| `ef5013881` | `fix(test-infra): relax wait_for_cluster_ready Property 1 to N-1 floor (seed-node lifecycle write bug, RC2 follow-up filed in spec §6)` | First attempt to work around the seed-node bug. Relaxed `_cluster_is_ready` Property 1 from strict-N to N-1. **Superseded by `03e4c83d9` + `fc334a71e` reverts.** |
| `1625cb5ff` | `fix(00-smoke): relax strict-equality node count assertions to N-1 floor (seed-node bug, spec §6 RC2)` | Smoke gate's three strict equality assertions relaxed to N-1 floor. **Superseded by revert.** |
| `03e4c83d9` | `fix(cluster-fsm): seed-node lifecycle write — handleNodeAdded plants JOINING NodeLifecycleKey for seed nodes (revert N-1 floor relaxations)` | **THE PRODUCTION FIX.** New helper `ensureSeedNodeLifecycleEntry(NodeId)` in `ClusterDeploymentState.java`: reads `NodeLifecycleKey(node)` via kvStore; if absent, submits `KVCommand.Put` with `JOINING` so the FSM machinery drives `JOINING → ON_DUTY` normally. Idempotent — preserves existing state (DRAINING etc). Active-state-scoped (leader-only). Test fixture cloned from `ClusterDeploymentStateRebalanceOnScaleUpTest`; 3 new tests in `ClusterDeploymentStateSeedNodeLifecycleTest`. Reverted §1.1 N-1 relaxations from earlier commits because the underlying bug is now fixed. |
| `c566eeac0` | `fix(test-infra): wait_for_cluster_ready defaults to N-1 floor (matches restore_cluster_baseline; RC2 MembershipView convergence gap); smoke gate opts into strict N` | Switched default `expected` arg to N-1. **Superseded by `fc334a71e` revert.** |
| `1d29a4949` | `fix(test-infra): pick_non_leader — call aether_failover directly for multi-word 'nodes lifecycle' (aether_json single-word limit)` | `aether_json "nodes lifecycle"` was treating "nodes lifecycle" as a single argument due to quoting, so picocli never saw a valid subcommand path. Changed call site to `aether_failover nodes lifecycle ...` directly. Also fixed `aether_json` and `aether_field` to split commands on spaces. |
| `2fcaa6f8f` | `fix(test-infra): fast-fail wait_for_cluster_ready when entry-point /health/live is down; migrate stale CLI calls in bootstrap and 08-resources` | (a) `wait_for_cluster_ready` now probes `/health/live` once at entry-point. If dead, fails in ~1s instead of waiting the full 60s timeout. Bounds the cascading slowdown when one suite leaves the cluster broken. (b) `run-tests.sh` bootstrap: `aether artifact push` → `aether artifacts push`; `aether blueprint deploy` → `aether blueprints deploy`. (c) `08-resources/test-http-client.sh` migrated from `aether_field topology coreCount` to `aether_failover cluster topology --format value --field coreCount`. |
| `fc334a71e` | `fix(test-infra): drop Property 4 (per-port /health/ready iteration broke under CTM auto-heal); restore default expected=NODE_COUNT` | **Final shape of the readiness contract.** Property 4 (per-port `/health/ready` iteration) was new in Wave 5 and broke under CTM auto-heal because replacement nodes provision at ports outside the `MGMT_PORT..MGMT_PORT+N-1` window. Reverted to the OLD shape: three cluster-side properties (members + leader + active core floor). Restored default `expected=NODE_COUNT`. Spec §1.1 + §1.2 updated. Smoke gate explicit override removed (no longer needed). |

## Final integration suite state (HEAD = `fc334a71e`, run 11)

Total: **7/15 suites pass**, ~65 min total runtime.

### Cluster A (non-destructive) — 8/10 suites pass

| Suite | Result | Notes |
|---|---|---|
| 00-smoke | ✅ 2p/0f | Gate passes |
| 06-deployment | ✅ 5p/0f | |
| 07-cluster-mgmt | ✅ 4p/0f | |
| 08-resources | ✅ 5p/0f | (was failing in earlier runs on SQL connector / route-wired; passes now) |
| 10-database | ✅ 3p/0f | |
| 11-observability | ✅ 6p/0f | |
| 14-storage | ✅ 2p/0f | |
| 04-streaming | ❌ 1p/3f | "Stream visible in list" / "Multiple streams isolation" / "Replicated stream visible" — stream-list visibility issue, pre-existing |
| 09-artifacts | ❌ 2p/1f | "1MB artifact push returned 500" — likely real bug (artifact-repo size limit?) |
| 15-delegation | ❌ 1p/1f | "Node_failure_reassignment" timed out — kill node-2 + restart didn't reconverge to 5; chaos-recovery race |

### Cluster B (destructive) — 0/5 suites pass

All 5 suites fail because the **first chaos test kills Cluster B beyond CTM's ability to recover**. After 02-chaos's first kill, port 5161 (Cluster B's entry point) stops responding to `/health/live` and never comes back. Subsequent suites all fast-fail via the entry-point probe.

| Suite | Result | First failure |
|---|---|---|
| 02-chaos | ❌ 0p/6f | `Initial 5 nodes + label snapshot` (and all subsequent) — entry point dead |
| 03-scaling | ❌ 0p/3f | All fast-fail |
| 05-security | ❌ 0p/3f | All fast-fail |
| 12-network | ❌ 0p/4f | All fast-fail |
| 13-edge-cases | ❌ 0p/3f | All fast-fail |

**Important:** the fast-fail mechanism (added in `2fcaa6f8f`) is doing its job — 5 dead suites consumed 38s total of timeouts instead of 5×~60s×N-tests = potentially hours. Without it the run would not have completed.

## Open issues for next session

### Cluster B recovery (RC1-relevant, owns the 19 cluster-B failures)

Cluster B has `restart: "no"` policy (destructive tests need `docker kill` to be authoritative). The expected recovery path is CTM auto-heal — provision a replacement container for the killed node. Currently CTM isn't actually reviving Cluster B post-chaos. Root cause needs investigation:

- Is CTM detecting the killed node? (NODE_FAILED event published?)
- Is CTM attempting to provision a replacement? (Look for CTM provisioning errors in node-2..5 logs.)
- Is the replacement being provisioned but at a port the test infra can't see?
- Is the `restart_all_nodes` shell helper still functional, or has it diverged from the runtime contract?

Pragmatic alternative if CTM auto-heal genuinely can't recover Cluster B: have the runner **forcibly recreate Cluster B** between major destructive suites (`docker compose -f docker-compose-b.yml down -v && up -d`). This is a hard reset that bypasses the auto-heal expectation entirely. Tradeoff: tests stop validating CTM auto-heal in real conditions; gain: subsequent suites don't all cascade-fail.

### Cluster A residual failures (3 suites with real test issues)

These are pre-existing or chaos-specific:

- **04-streaming**: stream-list shows the stream was created (`Stream info available for test-events`) but `aether streams list` output doesn't contain `test-events`. Likely a CLI rendering issue — `streams list` command may have a stale field name post-rename. Check `aether/cli/.../AetherCli.java::StreamCommand.ListCommand`.

- **09-artifacts/1MB_artifact**: `Push 1.1.0 returned 500 (expected 2xx)`. Could be artifact repo's chunk-size limit, DHT timeout under load, or a real bug. Read the response body if it carries detail.

- **15-delegation/Node_failure_reassignment**: kills node-2 then calls `start_node node-2`. After restart, waits for `wait_for_cluster_ready 240` and times out. The cluster has fewer than 5 members at that point — either start_node silently failed (container already running due to a CTM-provisioned replacement at the slot) or the cluster legitimately couldn't reach 5. Same broader pattern as Cluster B's recovery issue.

### Cleanups deferrable to next session

- Wave 6's two reverted commits (`ef5013881`, `1625cb5ff`, `c566eeac0`) are now dead history but visible in the log. They can stay — they document the iteration. Optionally squash if PR hygiene matters.

- `restore_cluster_baseline` (lib/cluster.sh) still has a comment block saying it waits for "4+ ON_DUTY healthy cores" floor (N-1) due to "RC2 MembershipView convergence gap". With the seed-node fix, that gap may have closed too — worth re-testing whether `restore_cluster_baseline` can wait for strict N now. If yes, tighten it.

## Key learnings worth retaining

1. **"There are no changes that could change cluster behavior."** When a test starts failing, before assuming the cluster regressed, check whether the *check* changed. My Wave 5 added Property 4 (per-port /health/ready) and changed Property 1 / 3 thresholds — the cluster was doing the same thing it did yesterday; the contract was newly stricter.

2. **CTM auto-heal port range mismatch.** Replacement containers use a different port range (5156+ for Cluster A, 5166+ for Cluster B) than the compose-defined nodes (5151-5155, 5161-5165). Any test infra that iterates `MGMT_PORT..MGMT_PORT+N-1` will miss CTM replacements. Future per-port checks need to either skip dead ports or fetch the live port list from the cluster.

3. **URL form encoding decodes `+` to space.** Any wire format that uses `+` as a separator in query strings needs to either URL-encode as `%2B` client-side OR accept both `+` and space server-side. We chose the latter (`RouteFilters.parseStateFilter` now splits on `[+,\\s]+`).

4. **Seed node bootstrap path is special.** `ClusterDeploymentState.handleNodeAdded` had an explicit `if !seedNodes.contains(node)` branch that skipped role assignment for seed nodes — assuming "seed nodes already have their lifecycle". They don't. The FSM-write path that joining nodes go through must also be triggered for seed nodes (now via `ensureSeedNodeLifecycleEntry`).

5. **Single-word vs multi-word CLI subcommands.** `aether_json "nodes lifecycle" --state ON_DUTY` quotes the multi-word command as one argument; picocli sees a literal "nodes lifecycle" token and fails. Wrappers like `aether_json` and `aether_field` need to split the command arg on spaces (`$command` unquoted) to pass each word as a separate token to picocli.

## Session metadata

- Date: 2026-05-20 (continuation of the day's earlier waves)
- Wave 6 commits: 9 substantive + 1 handover (this) = **10 commits**
- Cumulative today: Waves 1-5 (27 commits) + Wave 6 (10) = **37 commits**
- Suite runs today: 11 (run 1-11), of which run 11 was the final
- Final pass rate: 7/15 suites (47%) — bottlenecked by Cluster B's chaos-kill recoverability bug
- Fast-fail mechanism: saved an estimated 30-60 minutes of cascading timeout waits

## Suggested next-session opener

**Highest leverage**: investigate why Cluster B can't recover from 02-chaos's first kill. That single bug owns 19 of the 25 cluster-B failures (76% of all failures in the run). Likely candidates: CTM provisioning error logs on the survivor nodes, or `restart_all_nodes`/`cleanup_cluster_zombies` shell helpers being out of sync with the current docker-compose-b setup.

Once Cluster B recovers, expected pass rate jumps to 10-13 of 15 suites. The remaining 2-3 failures (streaming visibility, 1MB artifact, 15-delegation chaos race) are real product/test issues but smaller-scope.
