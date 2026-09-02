# Session Handover — 2026-04-17

**Branch:** `release-1.0.0-rc1` · **Version:** 1.0.0-rc1 · **Tag at start:** `v1.0.0-rc1-candidate` at `03f168f09` · **Current HEAD:** `cc91c07bb`

## TL;DR

Core runtime contracts are solid: consensus, QUIC transport, leader election, task-group forwarding, scaling (scale-up & scale-down), task reassignment, BSL relicensing, dev-mode removal — all landed this session. Three suites still fail on remote Docker (`04-streaming`, `08-resources`, `12-network`). Root cause for `12`/`04` is **test-vs-auto-heal races**, not a cluster bug. The fix is **test-side**, not runtime-side: real workloads rely on fast auto-heal, tests must adapt. `08-resources` is a separate track with two distinct sub-bugs (empty `cluster.nodes` in status + slice PUT 404).

**RC1 shipping bar (decided this session):** 15/15 green on remote Docker AND 15/15 green on Hetzner cloud. No waivers. No `requires_fresh_cluster` opt-ins at ship time.

---

## Why this file exists

Context did not fit in a single session. The next session inherits a detailed plan with all decision points resolved. Do not re-debate them — execute.

---

## What landed this session (in order)

| Commit | Subject |
|---|---|
| `ac5e776e8` | `is_cluster_ready` waits for `>= NODE_COUNT`, not just quorum |
| `398c4c5ca` | `discover_endpoints` via `aether` CLI + LB reachability probe + safe empty arrays |
| `b617f0cb6` | Typed `KVStore.forEach` in API-key routes; composite validator honors config keys |
| `cd476b8fb` | Demux `HttpForwardResponse` by `Pipeline` — mgmt forwards reach ManagementServer's forwarder |
| `4b30c6a3e` | Apply BSL-1.1 SPDX headers to `aether/**` + slice-processor modules (1213 files) |
| `4b9d0b890` | Fix test-persistence blueprint: `read = POST` and `resources.toml` declares `[database]` |
| `be6d2bc2d` | Remove `AETHER_INSECURE_DEV_MODE`; require deterministic QUIC TLS via `AETHER_CLUSTER_SECRET` |
| `955b1a6b7` | Changelog + feature catalog + build.sh for BSL + dev-mode removal |
| `ae1f7ec2c` | Scaling triple-fix (see details below) |
| `f9229f818` | Task reassignment bypasses leader-bound short-circuit via consensus KV put |
| `bcccb5035` | Drop FQCN for `QuicTlsProvider.CLUSTER_PROTOCOL` |
| `03f168f09` | Changelog for scaling + task-reassignment fixes |
| `cc91c07bb` | Test infra: `APP_ENDPOINT` falls back to direct node-1 app port; remote self-heal runs on target host |

### Scaling triple-fix (`ae1f7ec2c`) — remember this architecture

1. `cluster-config.toml` had `[core_topology]` but parser reads `[cluster.core]` → stored `coreMax` defaulted to current count → any scale-up rejected with "Invalid core max"
2. `/api/cluster/scale` is `taskGroup(SCALING)` routed — forwards to SCALING owner, NOT leader. `ClusterTopologyManager` is leader-bound. `setDesiredSize` on non-leader is a no-op. **Fix**: KV listener on every node — on `ClusterConfigKey` put, propagate `coreCount` to CTM. Leader's active CTM reacts. Pattern also used for task reassignment (see `f9229f818`).
3. `DockerComputeProvider` hardcoded `network_name = "aether-network"` + published host ports 8080..8084 that collide with seed cluster. **Fix**: `AETHER_DOCKER_NETWORK` env override (propagated to provisioned children); removed host-port publishing (provisioned nodes reachable via docker network only).

**Key insight for future similar bugs**: leader-bound state behind task-group-routed endpoints is a recurring pattern. The solution is to write intent to consensus KV, then let the leader's component react to the KV change through its existing notification path. Do NOT try to forward the original HTTP request to the leader — that would require a new `RouteTarget.LeaderNode` variant and more plumbing.

---

## Current test state

**Last full-suite run on remote Docker:** 12 pass / 3 fail (46p/5f tests).

| Suite | Status | Note |
|---|---|---|
| 00-smoke | ✅ | 2p/0f |
| 02-chaos | ✅ | 4p/0f |
| 03-scaling | ✅ | 3p/0f (after fixes) |
| 04-streaming | ❌ | 3p/1f — race with auto-heal after stream load |
| 05-security | ✅ | 3p/0f |
| 06-deployment | ✅ | 5p/0f |
| 07-cluster-mgmt | ✅ | 4p/0f |
| 08-resources | ❌ | 3p/2f — SEPARATE TRACK, see "Distinct bug: 08-resources" |
| 09-artifacts | ✅ | 3p/0f |
| 10-database | ✅ | 3p/0f |
| 11-observability | ✅ | 5p/0f |
| 12-network | ❌ | 0p/3f — test-vs-auto-heal races |
| 13-edge-cases | ✅ | 3p/0f |
| 14-storage | ✅ | 2p/0f |
| 15-delegation | ✅ | 2p/0f (reassignment fix landed) |

**Cloud / Hetzner status:** UNVERIFIED this session. Docker must be green first, then validate cloud. Expect new classes of failure (VM provisioning latency, DNS resolution, bastion SSH, LB routing).

---

## Next session priorities (in order)

### Priority 1 — Fix 12-network and 04-streaming via semantic test helpers

Root cause: tests assert `cluster_node_count == 5` on snapshot reads. After a kill, CTM auto-heal (fast, correctly working) spawns a replacement faster than SWIM reports the departure. Test sees a count in `{4, 5, 6, 7}` during the race window and fails.

**Decision already made: tests adapt to cluster, not the reverse.** Fast auto-heal is a feature (real-workload SLI); tests currently measure the wrong thing.

**Approach: (c) incremental** — land helpers first, then rewrite suites one at a time.

**Step 1 — Audit `/api/events`** before writing any helpers:
- Does it emit `NodeAdded`, `NodeDown`, `NodeRemoved`, `TaskAssignmentChange` with monotonic timestamps and NodeId payload?
- Query shape: `since=<timestamp>` or offset-based resume token? If neither, what does it currently support?
- Time format (epoch-ms vs ISO), NodeId format (string vs object).
- Durability across leader failover — events-on-leader need to survive failover; figure out the read semantics.

**If `/api/events` doesn't cover this today, ADD the missing events — do not fall back to polling.** The event stream becomes a contract consumed by tests, and via tests, by real workloads. If it's not emitting what real consumers need, that's the actual bug to fix first.

**Step 2 — Create `aether/tests/integration/lib/topology.sh`** with:

- `wait_for_node_departure <nodeId> <timeout>` — waits for NodeDown or NodeRemoved event for the given node via `/api/events`
- `wait_for_replacement_of <killedNodeId> <timeout>` — waits for a NodeAdded event with a NodeId ≠ killedNodeId after the kill timestamp
- `observe_quorum_window <startTs> <endTs>` — reads events in the window; computes min member count at any moment; asserts `>= ceil((N+1)/2)`
- Consume events via the CLI if it supports event queries; otherwise raw `curl`.

**Step 3 — Rewrite 12-network tests** to use the helpers. Prove pattern.

**Step 4 — Rewrite 02-chaos and 13-edge-cases kill-and-assert-count tests** using the same helpers.

**Step 5 — 04-streaming** has two failures. One is "expected '5', got '7'" (same root cause as 12 — use same fix). The other is `'repl-test-events' stream not created` — diagnose separately; may be a stream-publish event emission issue and could also be verified via `/api/events`.

### Priority 2 — Diagnose 08-resources (separate track)

Two distinct failures, both pre-existing hypothesis:

**2a — `Status contains node id in cluster.nodes: expected NOT '', got ''`**
- `/api/status` returns empty `cluster.nodes[]`. Management API bug. Check `ClusterConfigRoutes.assembleStatus(...)` and its `buildNodeInfos` path. Investigator report: `aether/node/src/main/java/org/pragmatica/aether/api/routes/ClusterConfigRoutes.java`.
- Suspect: `metricsCollector().allMetrics()` returns empty, or the status response omits the nodes array when some field is missing.
- Compare against `/api/cluster/topology` output which DID return 5 nodes — so topology data exists; `/api/status` is the one dropping it.

**2b — `PUT /api/kv/test-key returns 404`**
- test-persistence slice's routes not reachable on `APP_ENDPOINT = http://TARGET_HOST:8070`.
- Investigator confirmed `/api/slices` was empty after the run.
- Suspect chain (diagnose in order):
  1. Did pre-deploy actually succeed? Check runner stdout for deploy_blueprints errors.
  2. Is `test-persistence` in `/api/blueprint/list`? If yes, deployed; if no, not.
  3. If deployed, check `/api/slices` — is `test-persistence-persistence-slice` listed? If no, deploy didn't activate.
  4. If activated, is it on node-1? App HTTP server on node-1 only serves routes for slices deployed on node-1. If slice is on node-2/3/4/5, PUT to `:8070` (node-1's app port) returns 404.
  5. If not on node-1, does app HTTP forward to route owner? App-HTTP forwarding is separate from management forwarding; verify it's wired.

Keep this track separate from Priority 1. Different files, different fixes.

### Priority 3 — Then Hetzner

Once Docker is 15/15 green, pivot to Hetzner validation.

**Prerequisites:**
- `HCLOUD_TOKEN` set
- `deploy-cloud.sh` / `run-cloud-tests.sh` / `teardown-cloud.sh` unbroken
- `CLOUD_MODE=true` path in `lib/cluster.sh` exercised (SSH-via-bastion, timeout multipliers, LB routing)

**Expect new failures** beyond what Docker exposes:
- VM provisioning latency (CTM provision may take 30–90s, not 1–2s)
- DNS resolution for cluster peers
- Firewall rules (Hetzner default-deny on most ports; explicit allowlist needed)
- `aether` CLI SSH-tunnel scaling
- External-vs-internal hostname divergence (test-host perspective ≠ cluster-internal perspective)

**Budget:** Hetzner runs are slow (20–45 min per full suite) and cost real money. Iterate Docker-green first; Hetzner only after Docker lands.

### Priority 4 — Kept-idea implementation (low urgency, post-RC1 acceptable)

**(1) Suite-level pre/post contract** — `suite.conf` declares what cluster state it assumes and leaves. Runner validates. Would statically catch ordering sensitivity.
- Syntax: `requires_state=5_nodes,no_orphans,no_blueprints`
- Post-check: runner asserts same state after suite runs; if drift, emit warning
- Not a blocker; quality-of-life improvement

**(3) CTM backoff on provision/terminate churn** — if same NodeId was terminated < 60s ago, skip re-provisioning. Log warning. Gives operators "cluster is churning" signal and prevents runaway loops if we later surface an oscillation bug.

**(10) Event stream schema versioning** — once the event list stabilizes (after Priority 1 lands and 12/04 pass), document the full schema:
- Location: `aether/docs/reference/event-stream-api.md` (new)
- Include: all event types, payload schemas, timestamp format, version field, ordering guarantees, retention semantics
- Pin tests to schema version; breaking changes become deliberate
- Aligns with making `/api/events` a real public contract consumers can rely on

---

## Test ordering (decided)

**Current cluster-B suite order:** `02 03 05 12 13`
**New order (to land alongside Priority 1):** `05 12 02 03 13`

Rationale: 05-security is read-only-ish; run first. 12-network has strictest exact-count assumptions; run on pristine state. 02-chaos kills a node explicitly. 03-scaling changes topology extensively. 13-edge-cases designed for post-disruption.

**`requires_fresh_cluster=true` opt-in** — implement the flag but **DO NOT set it on any suite**. The ONLY purpose is as a tactical escape hatch if a suite proves unfixable within time budget. Treat usage as a bug. **Exit criterion for RC1: zero suites with the flag set.**

---

## Cluster state right now

- **Cluster A** (remote, 5150+8070): 5 nodes healthy, clean
- **Cluster B** (remote, 5160+8080): 5 nodes healthy, 0 orphans (manually cleaned at end of session)
- **Local dev**: clean; jar at `aether/node/target/aether-node.jar` mtime matches HEAD
- **Remote image** `aether-node:local`: matches `cc91c07bb` (last deploy-and-run cycle)

To restart next session cleanly:
```bash
cd /Users/sergiyyevtushenko/IdeaProjects/pragmatica
./build.sh
cd aether
scp -i "$AETHER_SSH_KEY" node/target/aether-node.jar aether@$TARGET_HOST:~/aether-build/node/target/
scp -i "$AETHER_SSH_KEY" /Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/tests/integration/docker-compose-*.yml aether@$TARGET_HOST:~/
ssh -i "$AETHER_SSH_KEY" aether@$TARGET_HOST 'cd ~/aether-build && docker build -t aether-node:local -f docker/aether-node/Dockerfile . && docker rm -f $(docker ps -aq --filter name=aether-core) 2>/dev/null; docker compose -f ~/docker-compose-a.yml down -v && docker compose -f ~/docker-compose-b.yml down -v && docker compose -f ~/docker-compose-a.yml up -d && docker compose -f ~/docker-compose-b.yml up -d'
```

Then run full suite or single suite:
```bash
cd aether/tests/integration
./run-tests.sh --env remote --skip-build --skip-deploy
# or
./run-tests.sh --env remote --suites 12 --skip-build --skip-deploy
```

---

## Watch-outs / pitfalls

1. **Do NOT revisit license decisions.** `aether/**` + `jbct/slice-processor*` are BSL-1.1 with SPDX per-file headers. Issue #162 tracks the future physical move of slice-processor into `aether/` — deferred, low priority.

2. **Do NOT re-add `AETHER_INSECURE_DEV_MODE`.** QUIC TLS is now mandatory. Forge uses a fixed test cluster secret in `EmberCluster.buildForgeQuicTls`. Docker tests use `AETHER_CLUSTER_SECRET` env var.

3. **If scale endpoint rejects with "Invalid core max"** — the stored cluster config has `coreMax=<small>`. Either the integration-test cluster-config was re-seeded with wrong `[cluster.core]` section name (check — should be `[cluster.core]` NOT `[core_topology]`), or the KV stored value is stale. Re-seed or restart cluster to refresh.

4. **Task-group-routed endpoints that touch leader-bound state** — the pattern we established (write to KV, let the leader react via existing notification) applies generally. If you see a new endpoint returning NOT_LEADER, this is probably the fix.

5. **03-scaling CTM bounce watch** — at 19:25/19:27 in last session's leader log, we saw 4 rapid scale-up/down cycles. Cause not fully pinned down (likely multiple investigator standalone re-runs, but potentially auto-heal oscillation). **First diagnostic step when you resume**: run 03-scaling twice back-to-back standalone, observe leader log for ONLY expected transitions (1 up, 1 down). If extra cycles appear without test triggers, this becomes a blocking investigation.

6. **The `/api/events` audit (Priority 1 Step 1) may surface missing events.** Adding them is in-scope — it's the underlying requirement for the test refactor AND for real-workload observability. Do not punt.

7. **`cluster_node_count` reads from `/api/cluster/topology`'s `coreCount`.** If you're debugging "why does my test see N when cluster has M", dump the endpoint raw — observer can lag physical container count by seconds during topology changes.

8. **Forge single-JVM mode** uses a fixed cluster secret (`aether-forge-cluster-secret` in EmberCluster). If Forge tests break, that's where to look — do not confuse with AETHER_CLUSTER_SECRET which is the docker/remote test fixture secret.

9. **Per-file SPDX header enforcement is not yet lint-enforced.** Script `tools/license/apply-bsl.sh` can be re-run anytime to fix missing headers. JBCT lint rule is a future addition.

10. **The TaskAssignmentCoordinator fix uses `clusterNode.apply(...)` from non-leader nodes.** This works because PassiveNode.apply() was wired up specifically for this use case. If you see weirdness there, verify PassiveNode is routing correctly.

---

## Files touched heavily this session (landmarks)

- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` — added `propagateScale` listener + demux logic
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNodeConfig.java` — new mandatory `quicTls` field + builder
- `aether/node/src/main/java/org/pragmatica/aether/Main.java` — always builds TLS bundle from AETHER_CLUSTER_SECRET
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicTlsProvider.java` — dev-mode paths deleted; ALPN wired via factory overloads
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/delegation/TaskAssignmentCoordinator.java` — reassignment via consensus KV put
- `aether/tests/integration/lib/cluster.sh` — `is_cluster_ready`, `discover_endpoints`, `self_heal` for remote env
- `aether/tests/integration/run-tests.sh` — `CLUSTER_A_APP_DIRECT`/`CLUSTER_B_APP_DIRECT` fallbacks, safe empty arrays
- `aether/tests/integration/docker-compose-a.yml` / `docker-compose-b.yml` — `AETHER_CLUSTER_SECRET`, `AETHER_DOCKER_NETWORK`

---

## Questions/assumptions to validate with operator before executing

None open — all 7 decision questions were answered in-session. If you need an answer about a decision beyond the scope of this handover (e.g., new architectural choice), surface it rather than guess.
