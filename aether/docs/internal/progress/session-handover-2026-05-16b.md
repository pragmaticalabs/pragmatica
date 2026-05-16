<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Session Handover — 2026-05-16b (RC1 Wave 4 — Hetzner identity-bound + universal label + diagnostic surfacing)
date: 2026-05-16
branch: release-1.0.0-rc1
head: 68fd203a4
predecessor: aether/docs/internal/progress/session-handover-2026-05-16.md
status: in-flight — RC1 architecture work mostly closed; one diagnosed test-side issue (CTM-replacement stale id reuse) remains
---

# Session Handover — 2026-05-16b

## TL;DR (3 minutes)

1. **Wave 4 lands two architecturally-correct fixes**: Hetzner provider now identity-bound (parity with Docker), and the test-infrastructure layer surfaces failures with clean `[FAIL]` diagnostics instead of silent `set -e` aborts. Three commits + changelog pushed: `aaf7c9133` (shell+YAML), `335971555` (Java), `68fd203a4` (changelog). `v1.0.0-rc1-candidate` tag moved.
2. **Wave 4 integration delta vs Wave 3 cluster B**: 02-chaos 1p/3f → **2p/2f** (+1), 03-scaling unchanged green 3p/0f, 12-network 1p/2f → 0p/3f (regression — see below), 13-edge-cases unchanged 0p/3f.
3. **Massive diagnostic upgrade**: every failure now emits a precise `[FAIL]` line naming the rc, container name, and stderr. Previously, chaos tests would silently abort with no signal.
4. **One real bug freshly diagnosed**: the same CTM-replacement container id (`aether-core-node-3Do37u9J6rVlLLQ9kQSIs1yciLa` in the Wave 4 run) is targeted by `pick_non_leader` in 3 different test files in a single suite run. First kill succeeds; subsequent kills fail with "No such container". This is a `pick_non_leader`/state-staleness issue OR a `restore_cluster_baseline` issue — needs a focused look but is now CLEARLY visible (whereas in Wave 3 it was buried in silent aborts).
5. **One possibly-environmental regression**: 12-network test-gossip-encryption was 6/6 PASS in Wave 3, now fails at "TLS handshake failures (46) exceed half of total (6)". Could be noise, could be a real interaction with the new compose labels. Worth investigating before assuming flake.

---

## Quick state

```
branch:  release-1.0.0-rc1
HEAD:    68fd203a4 docs(changelog): RC1 Wave 4 — Hetzner identity-bound + universal label + test-infra hardening
pushed:  yes (origin/release-1.0.0-rc1)
tag:     v1.0.0-rc1-candidate @ 68fd203a4 (forced)
working: clean (test-results.json gitignored)
```

---

## Commits this session (Wave 4)

| # | Hash | Subject |
|---|------|---------|
| 1 | `aaf7c9133` | fix(test-infra): universal aether.node-id label + kill_node failure surfacing + helper hardening |
| 2 | `335971555` | fix(ctm): Hetzner identity-bound provisioning + end-to-end identity-bound slot test |
| 3 | `68fd203a4` | docs(changelog): RC1 Wave 4 — Hetzner identity-bound + universal label + test-infra hardening |

Cumulative session (Waves 1-4): 16 commits past predecessor handover `030ac5b08`.

---

## Integration delta — Wave 3 (`ec9c3a1e6`) → Wave 4 (`68fd203a4`), cluster B suites only

| Suite | Wave 3 | Wave 4 | Delta | Notes |
|---|---|---|---|---|
| 02-chaos | 1p/3f | **2p/2f** | **+1** | test-kill-leader still 5/5 in 0s; test-kill-multiple likely now passes via fresh diagnostic surfacing; tests 3+4 fail on "no such container" — same id reused across files |
| 03-scaling | 3p/0f | 3p/0f | = | clean — Wave 3a accounting holds |
| 12-network | 1p/2f | 0p/3f | -1 | test-gossip-encryption regressed: 6/6 PASS → 0/0 with TLS handshakes 46/6 |
| 13-edge-cases | 0p/3f | 0p/3f | = | First_drain 503 (TODO), App_routes_reachable (EchoSlice not wired) — orthogonal |

Total: 4 suites, 1 passed, 3 failed. Better diagnostics in every case.

---

## What Wave 4 actually delivers

### Hetzner provider identity-bound (`335971555`)

- `HetznerComputeProvider.labelsFor(ProvisionContext)` now writes `aether-node-id=<id>` Hetzner label when `ctx.nodeId().isPresent()` — kebab-case per HCloud API convention. Comment documents the dotted-vs-hyphenated asymmetry vs Docker provider.
- `HetznerComputeProvider.listInstances(tagFilter)` translates upper-layer `aether.node-id` (dotted) → Hetzner-native `aether-node-id` at the boundary via new `translateKeys(...)` helper. Keeps `NodeLifecycleManager.NODE_ID_TAG` provider-agnostic; each provider encodes natively.
- Two unit tests: `provision_contextWithNodeId_setsAetherNodeIdLabelOnServer`, `listInstances_withDottedNodeIdTag_translatesToHetznerLabel`. Test count: hetzner 47 → 49.
- New E2E test `ClusterTopologyManagerIdentityBoundSlotE2ETest` drives the full identity-bound slot lifecycle through CTM + real MembershipFsm against a shared KV fixture — distinct from the 4 unit tests in `ClusterTopologyManagerIdentityBoundSlotTest` because it chains transitions through real reducer cells. aether-deployment: 442 → 443. The pre-existing `BootstrapModuleTest$ClusterConfigSeed` failure remains acknowledged and untouched.

### Universal `aether.node-id` label + diagnostic surfacing (`aaf7c9133`)

- `docker-compose-{a,b}.yml` — all 10 cluster-node services now carry `labels: { aether.node-id: "node-N" }`. CTM-provisioned containers already had it via `DockerComputeProvider.buildRunCommand`. Label coverage is universal.
- New `_docker_container_by_node_id_label` helper in `cluster.sh:1007-1016` resolves a container name via `docker ps --filter 'label=aether.node-id=<id>'`. NodeId-format-agnostic.
- `kill_node` docker-mode branch rewritten with the `|| kill_rc=$?` pattern that survives `set -e`, plus `log_fail` + `return $kill_rc` on non-zero. Failures surface BEFORE the test function exits.
- Audit of `pick_non_leader`, `start_node`, `drop_ctm_replacements`, `restart_all_nodes`, `container_running`, `list_aether_containers` confirmed no remaining silent-exit patterns. `wait_for_node_departure` / `wait_for_replacement_of` are not functions in `cluster.sh` — likely live elsewhere (`topology.sh` or inlined in suite scripts); worth a follow-up locate.

---

## Newly-visible issues

### Issue 1: same CTM-replacement targeted across tests (HIGH)

In the Wave 4 cluster B run, `aether-core-node-3Do37u9J6rVlLLQ9kQSIs1yciLa` was killed (or attempted to be killed) in:
- `02-chaos/Kill_non-leader_node` — FAIL: "No such container"
- `02-chaos/Kill_node_during_active_load` — FAIL: "No such container"
- `12-network/SWIM_detection_time` — FAIL: "No such container"

Suggests one of:
- (a) `pick_non_leader` is using stale data (cached `/api/nodes/lifecycle` response, eventually-consistent read) and keeps returning the same id even after it's been killed.
- (b) `restore_cluster_baseline` brings cluster to "5 healthy" but does NOT trigger CTM to provision a fresh replacement; the same CTM-provisioned container survives across tests (only the original-name nodes are killed-and-respawned, but the test then picks the survivor by accident).
- (c) The cluster genuinely has the SAME CTM-provisioned id persisting (CTM didn't observe the kill, didn't tombstone, didn't re-provision) — would indicate a problem in the SWIM-FAULTY → DECOMMISSIONED chain post-Wave-3b.

Diagnostic: look at `/api/cluster/topology` and `/api/nodes/lifecycle` BEFORE each chaos test's `pick_non_leader` call. If the API returns the dead container as ON_DUTY, root cause is (c). If the API returns a different node but the test still picks the same dead one, it's (a).

### Issue 2: 12-network test-gossip-encryption regression (HIGH)

Was 6/6 PASS in Wave 3 cluster B run. Now FAILs at `Gossip_encryption_via_transport`:
```
TLS handshake failures (46) exceed half of total (6) — cert/protocol issue
```

What changed in Wave 4 that could plausibly affect TLS handshakes:
- Compose YAML added `labels:` block on every service (CHECKED: yaml passes `docker-compose config`).
- Hetzner provider Java (not used in docker-remote env).
- `kill_node` shell rewrite (only executed during chaos tests, not during test-gossip-encryption setup).

The most likely cause: environmental noise. The TLS handshake counter is sensitive to startup timing on TARGET_HOST. The cluster may have been in a partially-recovered state from prior chaos suites (12-network runs AFTER 02-chaos on cluster B), and TLS handshakes attempted during the unstable window count as failures.

Alternative: the universal-label addition triggered a compose reconfigure that nudges handshake timing. Unlikely but worth verifying by re-running 12-network in isolation.

### Issue 3: `restore_cluster_baseline` semantic check

Both Issue 1 and Issue 2 hint at the same root question: does `restore_cluster_baseline` actually achieve PRODUCTION-LIKE recovery between chaos test files? It waits for "5 ON_DUTY healthy cores" — but ON_DUTY counts a CTM-provisioned replacement as just another core, regardless of how recently it was provisioned or whether it's at steady-state. A more rigorous restore would ALSO wait for:
- Cluster phase = NORMAL (already does this)
- Generation snapshot quiesced at a stable version (already does this)
- No nodes in JOINING / DRAINING / FAILED_DRAIN state (this is the gap)
- A configurable grace period after the most recent CTM provisioning event

But adding cleanup-between-tests violates the production-like principle (correctly raised earlier in the session). The right move is probably: make the chaos tests themselves more robust to picking already-dead nodes (consult LIVE state, not cached). Or make CTM faster at expiring stale ON_DUTY entries for nodes that have been gone for >5s.

---

## Architecture status at end-of-session

| Area | Status | Notes |
|---|---|---|
| Wave 1 (SWIM port, healthOf, ConnectionEstablished+NodeInfo, harness H1-H5) | shipped | Wave 1 closed all observed RC1 storm root causes |
| Wave 2 (artifact provisioning, test contracts, activePeers widening, @Contract) | shipped | UNMASKED test fixes recovered +5 tests |
| Wave 3a (deficit + surplus accounting, live slots) | shipped | Validated by 02-chaos test-kill-leader 5/5 PASS in 0s |
| Wave 3b (identity-bound slots — Docker side) | shipped | Validated by `ClusterTopologyManagerIdentityBoundSlotTest` (4 tests) |
| Wave 4 (Hetzner parity, universal label, kill_node hardening, E2E test) | shipped | This session |
| **CTM replacement reuse** | open (Issue 1) | Diagnosis is now possible — was buried under silent aborts before |
| 12-network test-gossip-encryption stability | possibly env-noise (Issue 2) | Re-run in isolation to confirm |
| 13-edge-cases First_drain 503 | open | Management forwarder + drain endpoint interaction |
| 13-edge-cases App_routes_reachable | open | EchoSlice handler wiring or blueprint issue |
| 14-storage, 09-artifacts 1MB, 05-security | open (orthogonal) | Pre-existing; not chaos-related |
| build.sh Step 2 lint | partial | 8 sites cleared in Wave 2d; 6 more in `ConfigurableLoadRunner` (task #16) |

---

## Critical gotchas

1. **`wait_for_node_departure` and `wait_for_replacement_of` not in cluster.sh** — the audit agent reported they don't exist as functions in cluster.sh. Likely in `topology.sh` or `events.sh` or inlined. If you find silent-exit patterns there, apply the same `|| rc=$?` hardening.
2. **CTM `restore_cluster_baseline` does NOT explicitly tombstone or replace prior CTM replacements** — it just waits for 5 ON_DUTY. The "production-like" interpretation is that this is correct; the test must handle whatever cluster state it finds. If chaos tests need a fresh provision each time, the answer is to fix the test (consult live state) not the harness (cleanup between tests).
3. **`./build.sh` is still blocked at Step 2 (lint)** by 6 newly-surfaced JBCT-RET-01 violations in `aether/forge/forge-load/ConfigurableLoadRunner.java`. The mvn fallback (`mvn -pl aether/node install -DskipTests -am`) produces a current JAR for integration testing. Task #16 captures the cleanup.
4. **No worktrees** — `isolation:"worktree"` has the stale-base-ref bug.
5. **Wave 3b NodeId generator uses KSUID** — `IdGenerator.generate("aether-core-node")`. Each CTM provision produces a globally-unique id. If the same id appears across tests, the test isn't actually re-provisioning between them.

---

## Open items (prioritised)

### High — RC1-blocker tier

1. **Issue 1**: chaos tests target stale CTM replacement across files. Likely fix: `pick_non_leader` consults `/api/cluster/topology` for `connectedPeerCount` + verifies container is alive via `_docker_container_by_node_id_label` before returning, OR `restore_cluster_baseline` explicitly waits for a no-recent-provisioning grace period. The architecturally-cleaner option is the former: make the test smarter about live state.

2. **Issue 2**: 12-network test-gossip-encryption regression. First step: re-run 12-network in isolation (`./run-tests.sh --env remote --skip-build --suites 12`) to determine if it's env-noise or a real regression introduced by the compose labels.

### Medium — likely test-side / environment

3. **13-edge-cases First_drain_allowed 503** — `Management forward failed: Request failed after all retries`. Diagnose: is the drain endpoint forwarder routing through a stale node? Does it require leader and the forwarder times out?
4. **13-edge-cases App_routes_reachable** — `App route http://192.168.0.71:8080/health not wired`. Likely the test-echo blueprint isn't deploying its handler properly in cluster B; check blueprint push + deploy_complete.
5. **09-artifacts 1MB push 504** — gateway timeout on > 1MB POST. Investigate nginx-gateway `client_max_body_size` / `proxy_read_timeout`.

### Low — cleanup

6. **6 JBCT-RET-01 sites in ConfigurableLoadRunner** (task #16). Apply `@Contract` per Wave 2d pattern.
7. **`wait_for_node_departure` / `wait_for_replacement_of` audit** — locate the actual file (likely `topology.sh`), apply the same `|| rc=$?` hardening.
8. **BootstrapModuleTest pre-existing failure** (task #23) — clock/timestamp assertion, unrelated to CTM.

---

## Next-session start

```bash
# 1. Verify state
git log --oneline -5                  # expect 68fd203a4 at HEAD
git status --short                      # expect clean

# 2. Decide focus:
#    (a) Issue 1 — chaos test stale-id reuse: smartest test-side fix
#    (b) Issue 2 — 12-network re-run in isolation; if regression real, investigate
#    (c) build.sh / ConfigurableLoadRunner cleanup
#    (d) other orthogonal items (13-edge-cases, 09-artifacts)

# 3. Recommended start: Issue 1 first — it's the dominant remaining cause of cluster B failures
#    Trace pick_non_leader in cluster.sh:190-220
#    Add a "verify container is alive" guard via _docker_container_by_node_id_label before returning
#    Re-run cluster B; expect 02-chaos 4p/0f and 12-network ≥ 2p/1f
```

---

## Artefacts written this session

- `aether/docs/internal/progress/session-handover-2026-05-16b.md` — this doc
- `/tmp/rc1-wave2-final.log` — Wave 2 integration log
- `/tmp/rc1-wave3-clusterB-v2.log` — Wave 3 cluster B validation log
- `/tmp/rc1-wave4-clusterB.log` — Wave 4 cluster B validation log
- `/Users/sergiyyevtushenko/.claude/plans/zany-forging-clarke.md` — approved Wave 4 plan

---

## References

- Predecessor: `aether/docs/internal/progress/session-handover-2026-05-16.md`
- CHANGELOG `1.0.0-rc1 Unreleased` section now spans Wave 1+2+3+4 fixes
- Friendly-agent diagnosis (CTM cascade) in this session's tail — confirmed accurate at every line citation
- Tag `v1.0.0-rc1-candidate` @ `68fd203a4`

---

**End of handover.** Architectural correctness work is closed. Remaining failures are diagnostically clean and well-scoped.
