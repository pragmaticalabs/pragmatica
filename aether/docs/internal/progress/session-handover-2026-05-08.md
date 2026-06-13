# Session Handover — 2026-05-08

**Branch:** `release-1.0.0-rc1` · **HEAD:** `737b29cb5` (pushed) · **Tag:** `v1.0.0-rc1-candidate` at HEAD (pushed, force-updated) · **Image:** `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1-candidate` rebuilt by CI on tag push

Continuation of [`session-handover-2026-05-07b.md`](session-handover-2026-05-07b.md). That doc closed with a 5-fix plan replacing audit Steps 3–7. **All 5 fixes shipped today, plus audit Step 6, plus 4 test-infrastructure bugs uncovered while validating.** Net delta: 11 commits, ~3000 unit tests passing, integration suite went from 1/15 → 14/15 in best runs (with run-to-run flakiness from a deeper architectural issue: see §6).

---

## ⚡ TL;DR for next session

**Read §6 first** — `GenerationSnapshotPublisher` quiescence not advancing reliably is the dominant remaining flake source. Every multi-suite run hits 408 timeouts on `await-quiesced`, which cascade into 503s on drain, missing NODE_FAILED events, and intermittent suite failures even after today's targeted fixes. **This is the highest-leverage next-session investigation.** Diagnostic plan in §6.

If you want to keep iterating on tests:
```bash
# Power on PG, run full 15-suite, look for which suites fail this time
curl -s -X POST -H "Authorization: Bearer $HCLOUD_TOKEN" \
  'https://api.hetzner.cloud/v1/servers/129807252/actions/poweron' | jq -r '.action.status'
/Users/sergiyyevtushenko/IdeaProjects/pragmatica/tools/pg-firewall.sh open
cd aether/tests/integration && source /tmp/aether-test-pg.env && \
  ./run-tests.sh --env remote --skip-build
```

**Hetzner state at session end:** clean. Only PG VM `129807252` (off). Account zero-orphans.

---

## 1 · State at session end

| Item | Value |
|---|---|
| Branch HEAD | `737b29cb5` (pushed) |
| Tag `v1.0.0-rc1-candidate` | at `737b29cb5` (pushed, force-updated) |
| Local `aether-node.jar` | fresh (May 8 18:02) |
| Local CLI `~/.aether/lib/aether.jar` | fresh (May 8 08:11) |
| Hetzner account | clean (only PG VM `129807252`, off) |
| PG firewall | closed (baseline) |
| Working tree | clean (only ephemeral `test-results.json`) |
| Last full 15-suite run on docker-remote | 12/15 (08, 12, 13 fail — see §3) |
| Best 14/15 run | post-pick_non_leader fix; 08-resources only fail |

---

## 2 · This session's commits (11 — all pushed)

```
737b29cb5 fix(test-infra): retarget_app_endpoint_to_active_slice probes route on docker too
c8d3459fe docs: changelog for TTL retention fix + pick_non_leader fix
a191b1d61 fix(test-infra): pick_non_leader queries actual cluster membership instead of hardcoded id list
99daa54a4 fix(autoheal): lower DECOMMISSIONED_RETENTION default from 24h to 60s
cfdf78cf8 docs: changelog for docker test API key role fix
337460af9 fix(docker): test API key uses rich-syntax with ADMIN role (was VIEWER default)
b82a7de01 docs: changelog for Fix #3/#4/#5 + audit Step 6
9e8acbe1d refactor(env): replace ProvisionSpec.tags Map with typed ProvisionContext
e16cfaf0c feat(cli): persist source cleanup handles in BootstrapState for fresh-shell destroy
6c9f626e3 feat(consensus,swim): explicit BOOTING/NORMAL TopologyObserver mode + phase-aware SWIM cold-boot suppression
ee798ca74 refactor(config): validate ClusterIdentity at construction (parse-don't-validate)
f360f2eb5 fix(ctm): provisioning circuit breaker — bound runaway replacement attempts
```

(Plus `dfd9df050` docs and `c3526cbde` pg-firewall IPv4 fix from session start.)

### Verdicts — what's keeping vs revisiting

| Commit | Status | Notes |
|---|---|---|
| `f360f2eb5` Fix #2 CTM circuit breaker | ✅ KEEP | Cloud-validated: zero orphan VMs across all kill-test runs (was 7+ orphans/run last session). Bug class actively burning Hetzner spend is closed. |
| `ee798ca74` Fix #1 ClusterIdentity | ✅ KEEP | 14 unit tests; parse-don't-validate at value-object boundary; 309 cli module tests pass. |
| `6c9f626e3` Fix #3 + audit Step 6 | ✅ KEEP | 8 unit tests; SwimProtocol uses `BooleanSupplier isBooting` (avoids cross-module dep); TopologyObserver one-way BOOTING→NORMAL transition. |
| `e16cfaf0c` Fix #5 BootstrapState persistence | ✅ KEEP | 22 unit tests round-trip + back-compat; env-var NAMES persisted (never values). |
| `9e8acbe1d` Fix #4 typed ProvisionContext | ✅ KEEP | Closes Hetzner 422 bug class structurally; all 5 providers + 2 callers refactored. |
| `337460af9` Docker test key ADMIN | ✅ KEEP | Image bundled aether.toml — needed for docker/remote tests; cloud unaffected (composes its own runtime TOML). |
| `99daa54a4` TTL 24h → 60s | ✅ KEEP | Reaper was started but with 24h default; lowering to 60s makes it actually sweep. |
| `a191b1d61` pick_non_leader queries cluster | ✅ KEEP | `lib/cluster.sh` — queries `/api/nodes/lifecycle` (state=ON_DUTY) instead of hardcoded `node-1..5`. |
| `737b29cb5` retarget probe on docker | ⚠️ KEEP but caveat | Helper now probes route on docker too (was cloud-only); this caused 08-resources 4p/1f → 5p/0f in subset run, but full-suite still flakes due to underlying §6 issue. |

---

## 3 · Test pipeline progression (best runs)

| Run | Fixes active | Result |
|---|---|---|
| Pre-session | (state from `123899171`) | 1/15 (gate fail at 00-smoke blueprint-deploy = VIEWER role 403) |
| + RBAC fix | `337460af9` | 14/15 (only 13-edge-cases fails — disruption budget) |
| + TTL fix | + `99daa54a4` | 14/15 (13-edge-cases now PASS; 12-network/03 fails — `pick_non_leader` returns dead node-2) |
| + pick_non_leader fix | + `a191b1d61` | 14/15 (12-network/03 now PASS; 08-resources fails — `retarget` is cloud-only no-op) |
| + retarget probe fix (subset 06+08) | + `737b29cb5` | 1/2 (08-resources 5/0 PASS; 06-deployment 4p/1f — `await-quiesced 408` cascade) |
| + retarget probe fix (full 15) | + `737b29cb5` | 12/15 (08, 12, 13 all flake on `await-quiesced` 408 cascade — §6) |

**Pattern: each targeted fix is real, validated in isolation. The regression in the last full run is the snapshot-quiesce architectural flake re-asserting itself under different timing distributions.**

---

## 4 · Bugs fixed today — root cause briefs

### 4.1 RBAC default = VIEWER (337460af9)

`aether/docker/aether-node/aether.toml` line 18 had `api_keys = ["aether-integration-test-key"]` — **simple-syntax** form that `ApiKeyEntry.defaultEntry` maps to `VIEWER` (per `ApiKeyEntry.DEFAULT_ROLE`). Every operator endpoint returned 403:
- `/api/blueprint/deploy` (OPERATOR_AND_ABOVE per `RoutePermissionRegistry.java:59`)
- `/api/cluster/await-quiesced` (default ADMIN_ONLY per fallthrough at `:55`)
- `/api/cluster/config`, `/api/cluster/keys`, `/api/node/drain` etc.

Cloud bootstrap ALWAYS composes its own runtime TOML via `BootstrapOverlayGenerator` (`cloud-hetzner-b.toml:74-77` uses rich-syntax with `authorization_role = "ADMIN"`), so cloud was unaffected. Docker/remote consume the image's bundled TOML directly → all integration tests blocked.

Fix: rich-syntax `[app-http.api-keys.aether-integration-test-key] authorization_role = "ADMIN"`. Key value `aether-integration-test-key` is explicitly test-named; production rotates anyway.

### 4.2 DECOMMISSIONED retention 24h → 60s (99daa54a4)

`DecommissionedAtomGc` (at `aether/aether-deployment/.../generation/`) was implemented and started in `AetherNode.java:1239` but with `DEFAULT_DECOMMISSIONED_RETENTION = timeSpan(24).hours()` — the reaper sweeps at retention/2, clamped [5s, 1h]. With 24h retention → 1h period (cap). KV-Store accumulated DECOMMISSIONED entries across long sessions and back-to-back integration test suites.

After 14 prior suites kill-test cycles, `/api/nodes/lifecycle` returned 4 ON_DUTY + 5+ DECOMMISSIONED entries. Disruption-budget calculator at `NodeLifecycleRoutes.java:125-149` (which counts non-ON_DUTY) saw `currentlyUnavailable >> 2`, rejected drain even on first attempt.

Fix: 60s default → 30s sweep cadence → DECOMMISSIONED entries gone within a minute → budget calc correct. Operators can override via `[operations.auto_heal] decommissioned_retention = "..."` for forensic retention.

### 4.3 `pick_non_leader` hardcoded id list (a191b1d61)

`aether/tests/integration/lib/cluster.sh:156-180` iterated `for i in 1 2 3 4 5; do node-$i`. After 02-chaos killed and "revived" `node-2` (where `start_node` does `docker start ${container}` only — no KV cleanup, single-writer DECOMMISSIONED rule prevents rejoin), subsequent `pick_non_leader` calls in 12-network/03 still picked `node-2` even though it was no longer in cluster's SWIM membership.

Surviving nodes had already removed `node-2` from their SWIM members map, so killing the container produced no Ping timeout → no `FaultyObserved` → no `NODE_FAILED` event → 60s timeout.

Fix: query `/api/nodes/lifecycle`, filter `state == "ON_DUTY"`, pick from those. Falls back to hardcoded list if API fails (pre-bootstrap edge).

### 4.4 retarget helper cloud-only (737b29cb5)

`retarget_app_endpoint_to_active_slice` (`lib/cluster.sh:596`) bailed for non-cloud at line 598:
```bash
if [ "${ENV_TYPE:-docker}" != "cloud" ]; then return 0; fi
```

Designed to handle the post-`SliceState.ACTIVE` route-table propagation race on cloud (each VM has unique IP, retarget APP_ENDPOINT to the slice owner). On docker/remote only `node-1`'s app port is host-mapped, so we can't IP-retarget — but we CAN still wait for `node-1`'s route table to pick up the slice route. The bail meant `test-sql-connector` raced the propagation window and PUT got 500.

Fix: split logic — IP-retarget cloud-only, but probe the path on both env types. On docker, just probe APP_ENDPOINT (default node-1) until status < 500.

---

## 5 · Architectural fixes that landed (Fix #1–5 + audit Step 6)

These are the structural fixes from the 5-fix plan in `session-handover-2026-05-07b.md` §4. All 5 done; all unit-tested. Briefly:

### Fix #1 — `ClusterIdentity` parse-don't-validate (`ee798ca74`)

`ClusterIdentity` value object existed but didn't validate. Moved regex `^[a-z][a-z0-9-]{0,62}$` from `ClusterBootstrapCommand` (CLI override path only) into the factory and `withName` mutator. Both return `Result<ClusterIdentity>` now. `ClusterBootstrapConfigParser` chains via `.flatMap(ClusterIdentity::clusterIdentity)`. Closes the silent path where TOML could carry an uppercase / leading-digit name into Hetzner labels and DNS.

### Fix #2 — CTM circuit breaker (`f360f2eb5`)

`ClusterTopologyManagerRecord` adds two atomics: `consecutiveProvisioningFailures`, `nextProvisioningAllowedMs`. Counter increments on (a) `provisionSingleNode` API rejection, (b) `expireSlots` deadline expiry. After 3 consecutive failures: trip → `handleDeficit` halts dispatch entirely. Exponential backoff `30/60/120/240/300`s. Resets on: `onNodeReady` (slot success), `onClusterPhaseChanged(NORMAL)`, `activate` (leader handoff), `setDesiredSize` (operator action). 4 unit tests; 251 module tests pass. **Cloud-validated: zero orphan VMs across all subsequent kill-test cycles.**

### Fix #3 — explicit BOOTING/NORMAL `TopologyObserver` modes (`6c9f626e3`)

New nested enum `TopologyMode { BOOTING, NORMAL }` + `AtomicReference<TopologyMode> mode` initialised to BOOTING. `BOOTING` reads = legacy `nodeStatesById` fallback (preserves cold-boot quorum-eval); `NORMAL` reads = snapshot-only (returns 0 if snapshot empty). One-way transition `BOOTING → NORMAL` triggered by FIRST `MembershipView` with `coreMemberIds().size() >= clusterSize/2+1`. Mode checked on every read AND on snapshot publish. Exposed via `TopologyObserver.topologyMode()` and surfaced in `aether status --format json` as `topology.mode`.

### audit Step 6 — phase-aware SWIM cold-boot suppression (bundled in `6c9f626e3`)

`SwimProtocol.emitFaultyOrUnknown` (around line 703) no longer suppresses `FaultyObserved` based solely on per-peer `everSeenHealthy`. New `BooleanSupplier isBooting` injected via `SwimHealthContext`. In `BOOTING` phase: legacy suppression preserved. In `NORMAL` phase: `FaultyObserved` always emits regardless of `everSeenHealthy`. Wiring is via generic `BooleanSupplier` so `integrations/swim` doesn't gain a dep on `aether/slice` — `AetherNode` translates `() -> healthReconciler.phase() == ClusterPhase.BOOTING` at the boundary. Closes the cloud-only failure mode where a peer killed before its first Ping ack would emit `UnknownObserved` (which `HealthReconciler.aggregator` doesn't aggregate).

### Fix #5 — `BootstrapState` source persistence (`e16cfaf0c`)

New `Map<String, SourceCleanupHandle> sources` field in `BootstrapState`. `SourceCleanupHandle(provider, region, credentialEnvVars)` — `credentialEnvVars` is `Map<String, String>` from logical-field-name to env-var-NAME (never value). Stamped after each successful provision via regex over `BootstrapContext.rawTomlContent` (Option A from spec — re-extract `${env:NAME}` from raw TOML rather than threading names through SourceProfile). Read at cleanup time: `BootstrapCleanup.destroyVm` prefers handle, falls through to `ProviderResolver.resolveCloudComputeForCleanup` if absent (back-compat). Round-trip + back-compat tests (state files lacking `sources` field load with empty map, no NPE).

### Fix #4 — typed `ProvisionContext` (`9e8acbe1d`)

Replaces `ProvisionSpec.tags: Map<String, String>` with `ProvisionContext(clusterName, role, sourceName, nodeId, peers, coreMax, provisionedBy, extraTags)` record. Each provider does native encoding internally:
- Hetzner: `aether-cluster` / `aether-role` / `aether-source` / `aether-node-id` labels (Hetzner-spec dashes); cloud-init userData carries PEERS
- Docker: dotted-key labels (Docker-conventional) derived from typed context fields
- AWS / GCP / Azure: native tag/label encoding

`HetznerComputeProvider.mergeLabels` regex filter (added in `29b7fed38`) reduced to a defensive last-line filter on `extraTags` only — typed-field path can no longer ship dotted keys as Hetzner labels. The HTTP 422 "invalid input in field labels" bug class is structurally impossible.

---

## 6 · The dominant outstanding flake — snapshot quiescence

**This is the next-session priority.** Every multi-suite docker-remote run today exhibited:

```
[FAIL] await-quiesced status=408 after 61000ms (target=1:N)
[FAIL] await-quiesced status=408 after 122000ms (target=1:N)
```

…repeatedly. Cascading into:
- 13-edge-cases: drain returns **503** ("Management forward failed: Request failed after all retries") instead of 200/409 (budget rejection)
- 12-network/03: `pick_non_leader` correctly returns a node ID present in KV's ON_DUTY entries, but cluster's actual SWIM members has already evicted that node → no Ping timeout → no FAULTY → no `NODE_FAILED` event
- 06-deployment: `/api/schema/status/default` returns 503 after retries
- 08-resources: `PUT /api/kv/test-key` returns 500

The 408 timeouts mean `GenerationSnapshotPublisher.observedEpoch` is NOT advancing to keep up with cluster KV-write activity. Looking at `aether/node/.../AetherNode.java:1214-1247`:
- `GenerationSnapshotPublisher` is created
- A separate `swimHintsTickExecutor` calls `markDirty()` every 1s
- `KVNotificationRouter` calls `markDirty()` on lifecycle/spokesman/governor KV puts

So the publisher IS being marked dirty. But quiesce-target epochs (e.g. `1:175`) aren't being observed even after 60-120s of waiting.

### Diagnostic hypotheses

1. **`markDirty` doesn't actually trigger a fresh publish** — there might be a flag-only set without a wake-up of the publisher's loop. Check `GenerationSnapshotPublisher` source.
2. **Publisher's tick has a backoff that compounds** — under load, each markDirty resets a debounce that prevents publish from firing.
3. **The leader's HLC clock isn't advancing fast enough** — the snapshot is keyed by HLC; if HLC stalls, observedEpoch stalls.
4. **`ClusterSyncCollector` floods the publish queue** — periodic per-peer health writes might dominate, pushing newer epochs far behind the await target.
5. **Specific message-routing pattern dropping events** — Step 4 deletion of redundant `NODE_FAILED` paths might have inadvertently dropped a publisher trigger.

### Proposed investigation steps

Day 1 — instrumentation:
- Add DEBUG logs to `GenerationSnapshotPublisher`: every `markDirty`, every publish-loop tick, every observedEpoch update. Time-stamped.
- Add a `/api/cluster/generation-debug` endpoint that returns `{ observedEpoch, requestedEpoch, lastPublishMs, lastMarkDirtyMs, queueSize }`.
- Run `--suites 13` (just disruption-budget) with --skip-teardown; ssh into leader; tail `aether-node.log` during the failing test window; capture which `markDirty` calls don't translate to publishes.

Day 2 — fix or escalate:
- If hypothesis 1 (debounce flag without wake-up) confirmed → add wake-up signal
- If hypothesis 4 (queue flood) → add per-source rate limit or LIFO selection
- If hypothesis 3 (HLC stall) → root-cause HLC implementation

This is likely a 1-2 day fix once the diagnostic logging is in place. Without it, the test suite remains flaky regardless of test-side improvements.

---

## 7 · Other open questions / smaller follow-ups

### A. Cloud full-suite validation pending

Today only ran `--suites 12` on cloud (Hetzner capacity blocked broader runs early; later capacity returned and `--skip-teardown` cluster left intact for inspection). Before declaring RC1, run full 15-suite cloud at least once.

### B. CTM-provisioned VMs not in `BootstrapState.createdResources`

Earlier session inspection found 4 orphan VMs (CTM-provisioned during 12-network/02 kill+replace) that bootstrap teardown didn't sweep — they're not in `BootstrapState.createdResources` (only bootstrap-time creates are tracked). `cloud-reaper.sh` catches them via label filter, but normal teardown via `aether cluster destroy` misses them. Worth a follow-up: either CTM stamps a separate KV atom that destroy reads, OR teardown uses cloud-reaper-style label filter.

### C. `start_node` doesn't actually rejoin

The whole class of "kill_node then start_node" assumed pre-single-writer-DECOMMISSIONED behavior. Currently `start_node` just runs `docker start ${container}` (or its cloud equivalent). The container starts, the node process boots, but the cluster's leader sees `NodeLifecycleKey[id] = DECOMMISSIONED` and rejects the rejoin attempt. CTM provisions a NEW node with new ID instead. **Today's fix to `pick_non_leader` worked around this by picking from current cluster, but the underlying assumption baked into many tests is wrong.** Audit: `grep -rn "start_node" aether/tests/integration/suites/` and review each call site — most should just remove the start, or CTM-provisioned replacement should be used as the new "5th node".

### D. `await-quiesced` returns 408, not 503

The endpoint at `ClusterAwaitQuiescedRoute.java` is currently ADMIN_ONLY (per `RoutePermissionRegistry` fallthrough). Today's RBAC fix put the test key at ADMIN so this path is no longer 403 — it's now 408 (genuine timeout). But a polling-style endpoint shouldn't really need ADMIN; consider downgrading to ALL_AUTHENTICATED. Separate RC2 work; mentioned in session-handover-2026-05-07b.

### E. 08-resources `PUT /api/kv/route-probe` probe semantics

The retarget probe waits until `http_status < 500`. The `test-persistence` slice exposes `/api/kv/{key}` so a GET on `/api/kv/route-probe` should return 404 (key not found) — that's < 500, probe succeeds. But for a slice with no GET-able route at the probe path, it might return 405 (method not allowed) which is also < 500 — also succeeds. Worth double-checking the probe path picked in tests is actually slice-served.

---

## 8 · Quick start for next session

```bash
# 1. Sanity
git log --oneline 7fbab16f5..HEAD          # ~13 commits this session + history
git status --short                          # should be clean
git tag --points-at HEAD                    # v1.0.0-rc1-candidate

# 2. Hetzner inventory (should be just PG, off)
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers' | \
  jq -r '.servers[] | "\(.id)\t\(.name)\t\(.status)"'

# 3. Decide next action:
#    OPTION A — Investigate snapshot quiescence flake (recommended; §6)
#    OPTION B — Re-run full 15-suite a few times to characterize flake-rate first

# 4. Power on PG when you're ready to test
curl -s -X POST -H "Authorization: Bearer $HCLOUD_TOKEN" \
  'https://api.hetzner.cloud/v1/servers/129807252/actions/poweron' | jq -r '.action.status'
tools/pg-firewall.sh open

# 5. Full 15-suite docker-remote
cd aether/tests/integration && source /tmp/aether-test-pg.env && \
  ./run-tests.sh --env remote --skip-build

# 6. Power off PG when done
curl -s -X POST -H "Authorization: Bearer $HCLOUD_TOKEN" \
  'https://api.hetzner.cloud/v1/servers/129807252/actions/poweroff' | jq -r '.action.status'
tools/pg-firewall.sh close
```

---

## 9 · Score card

| Metric | Start of session | End of session |
|---|---|---|
| Branch HEAD | `123899171` | `737b29cb5` |
| Commits ahead of session-start | 0 | 11 |
| Unit-test count (key modules) | ~3000 | ~3050 (added 50+ for fixes) |
| Unit-test pass-rate | green | green |
| Integration suite (best run) | 1/15 | 14/15 |
| Integration suite (typical run) | 1/15 | 12-14/15 (flaky on snapshot quiesce) |
| Hetzner orphans/run | 7+ (Pattern 3 runaway) | 0 (Fix #2 validated) |
| Cloud cost/session | €8-12 (orphan accrual) | ~€2 (clean teardowns) |
| RC1-day budget (estimate) | 7-10 days | 3-5 days |

**Net: 8 distinct bugs root-caused and fixed, plus the architectural Fix #1-5 + audit Step 6.** The remaining flake is an architectural snapshot-publisher issue (§6) that's the highest-leverage next target.
