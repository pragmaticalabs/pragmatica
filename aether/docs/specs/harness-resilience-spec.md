# Harness Resilience Spec

| Field   | Value                                                        |
|---------|--------------------------------------------------------------|
| Status  | Draft — ready for implementation                             |
| Date    | 2026-06-15                                                   |
| Module  | `aether/tests/integration/`                                  |
| Related | integration-test-overhaul-v2-spec.md, integration-test-overhaul-spec.md, cli-gap-audit.md |

---

## Table of Contents

1. [Motivation](#1-motivation)
2. [Relationship to Existing Specs](#2-relationship-to-existing-specs)
3. [Root-Cause Map](#3-root-cause-map)
4. [Completed — Tier B](#4-completed--tier-b)
5. [Tier A — Product Capabilities](#5-tier-a--product-capabilities)
6. [Tier C — Authoring UX and Provisioning Robustness](#6-tier-c--authoring-ux-and-provisioning-robustness)
7. [Sequencing and Priority](#7-sequencing-and-priority)
8. [Open Questions](#8-open-questions)

---

## 1. Motivation

### 1.1 The UX-Proxy Thesis

The integration test harness is the primary UX surface for a developer trying to answer "does this cluster work?" Its failure modes are a faithful proxy for the system's own opacity: every gap in the product's observable API creates a harness workaround, and every workaround introduces a new class of test failure.

This session proved that thesis empirically. A single cloud run that reported "9 members / 2 VMs alive" could not be diagnosed from the harness alone because the product offered no single endpoint combining identity, address, role, and liveness. The diagnosis required manual SSH, reading bootstrap state files, and cross-referencing Hetzner labels — a path that no CI job can walk.

### 1.2 The Cost-of-Opacity Argument

The RC1 cloud validation campaign spent more engineer-hours on harness failures than on product bugs. The breakdown:

- **Endpoint resolution failures** (R1): wrong port class on cloud, duplicated logic diverging silently.
- **Zombie / unreachable node confusion** (R2): no product API to distinguish "member the cluster knows about" from "VM that is alive."
- **Regex-JSON parse drift** (R3): field renames (`id` vs `nodeId`) that CI catches only on cloud, hours into a run.
- **Silent failure misattribution** (R4/R5): a stuck 2-of-5-READY baseline after suite 02 silently failed suites 03, 05, 12, and 13 with misleading error messages.

Items R1, R2, and R3 are symptoms of product gaps. Fixing them in the harness is whack-a-mole. Fixing them in the product eliminates the class.

---

## 2. Relationship to Existing Specs

### 2.1 What this doc does NOT duplicate

**`integration-test-overhaul-spec.md`** (2026-04-12, §3–§6) owns the full framework architecture: dual-cluster topology, `test-env.toml`, environment templates, capability detection, lifecycle phases, blueprint isolation, and the `run-all-v2.sh` runner. This spec does not revisit any of that.

**`integration-test-overhaul-v2-spec.md`** (2026-04-13, §§3–13) owns the implementation detail: `run-tests.sh` flags, TOML env files, compose generation, `suite.conf` format and field definitions, parallel/sequential execution pseudocode, self-heal (`self_heal()`), LB discovery API change, and the 8-layer migration plan. This spec does not revisit any of that.

**`cli-gap-audit.md`** (Phase A + B landed) owns the REST/CLI surface canonicalization — plural collections, `ManagementRoute` rename, per-node forwarding infrastructure, error envelope standardization, and state-authority cleanup. Tier A below adds three net-new endpoints not in that audit. They follow the same conventions (tail-params invariant, REST-orthodox plural noun, `ManagementRoute` enum entry, `ManagementServer` wiring).

**`cluster-bootstrap-spec.md`** (§8, §10) owns bootstrap flow and CLI command signatures for `aether cluster bootstrap`. The Tier C4 item (dogfood bootstrap on docker/remote) is a harness behavior change, not a change to the bootstrap CLI itself.

### 2.2 What this doc owns

This doc owns four things that none of the above specs cover:

1. **Root-cause map** — a structured post-mortem of the harness failure classes discovered during RC1 cloud validation.
2. **Tier B** — the four fixes already applied this session (recorded here as DONE so they are not re-implemented).
3. **Tier A** — three product endpoints whose absence forced the harness workarounds described in the root-cause map. Each entry specifies the REST route, CLI command, JSON shape, and `ManagementRoute` enum entry needed. These are RC2 work items that trigger the REST→CLI→Docs triad (see CLAUDE.md §"REST API → CLI → Docs triad").
4. **Tier C** — harness-side authoring UX and provisioning robustness items that do not require product changes.

### 2.3 Scope boundary

`integration-test-overhaul-v2-spec.md §9` already specifies `self_heal()` as the post-destructive-suite restore contract. Tier B item B4 is the implementation of the _unrecoverability gate_ inside the cluster-B loop of `run-tests.sh` — it activates the `aborted` branch that v2-spec §9 described but that was dead code. This is an implementation completion, not a new design.

---

## 3. Root-Cause Map

Seven failure classes were identified across a 4-audit review of the harness (lib/common.sh, lib/cluster.sh, lib/topology.sh, run-tests.sh) conducted during RC1 cloud validation.

### R1 — No node-addressing identity model

**Symptom:** Endpoint resolution was quadruplicated across `_resolve_live_endpoint` (lib/common.sh:228), `aether_failover` (lib/common.sh:56), `rotate_mgmt_entry_point` (lib/cluster.sh:453), and `wait_for_node_count_fast` (lib/cluster.sh:690), each with divergent cloud/docker branching.

**Latent bug (shipped in RC1 and now fixed):** `aether_failover` fell back to `MGMT_PORT` (the docker host-mapped range, 5151–5155) on cloud instead of `CLOUD_MGMT_PORT` (8080). On docker runs this was silent because the docker-range ports were reachable. On cloud runs the fallback dialed dead ports, causing spurious timeouts that were misattributed to product bugs.

**Root cause:** The harness has no identity model mapping nodeId → reachable management endpoint. Instead it reconstructs addressing from two sources that are both incomplete:
- `~/.aether/clusters/<name>/bootstrap-state.json` — contains only the original bootstrap nodes. CTM-provisioned replacement nodes (which get ULIDs, not sequential `node-N` names) are invisible to `_registered_by_to_offset` (lib/common.sh:698), whose regex `^node-[0-9]+$` cannot match ULID-named nodes.
- Hetzner labels (via `cloud_public_ip`) — requires `HCLOUD_TOKEN` at test runtime, not just at bootstrap time.

**Product fix:** Tier A1 (`GET /api/nodes/{id}/endpoint`).

### R2 — No live-vs-zombie liveness contract

**Symptom:** The "9 members reported / 2 VMs alive" problem — the cluster's membership view (from consensus KV-Store) contains nodes that the underlying infrastructure has already destroyed or that never fully booted. The harness papers over this with `max(coreCount, coreNodes.length)` and docker-ps guards that are not available on cloud.

**Root cause:** The product has no single endpoint that combines a node's cluster-identity (nodeId), infrastructure-address, role, SWIM liveness, and reported lifecycle state. Tests must stitch together three sources: `/api/nodes/lifecycle` (cluster FSM), SWIM reachability (not directly exposed), and `/api/nodes/status/{id}` (per-node, requires forwarding). The `pick_non_leader` helper in lib/cluster.sh iterates this stitch manually on every call.

**Product fix:** Tier A2 (`GET /api/nodes/live`).

### R3 — Regex-JSON parsing coupled to field names and layout

**Symptom:** Real cloud failure: `_resolve_live_endpoint` returned "got 1, expected 5" because a `grep -oE '"id":"[^"]*"'` pattern matched the `"id"` field inside a nested object before matching the top-level `nodeId` field. The `id` vs `nodeId` rename (cli-gap-audit.md §Phase A) was not reflected in the grep pattern.

**Scope:** The same brittleness appears in `cluster_active_core_count` (lib/cluster.sh:173, grep on `derivedStatus`), `node_lifecycle_state` (lib/cluster.sh:214, grep on field name), and `generation_current` in `restore_cluster_baseline` (lib/cluster.sh:2054, grep on `epoch` field). Any server-side field rename that is not simultaneously reflected in the harness grep patterns causes a silent wrong-answer, not a parse error.

**Product fix:** Tier A3 (versioned cluster-status schema + CI contract test). Harness-side: prefer `aether ... --format value --field <path>` over grep-on-JSON wherever possible.

### R4 — Silent failure characterization (mostly fail-safe, one genuine gap)

**Common pattern:** Most `2>/dev/null || true` guards in the harness fail **closed**: a failed curl returns empty string or zero, which the callers treat as "not ready yet" and re-poll until timeout. This is fail-safe (conservative), not silent-pass.

**The one genuine silent-pass:** `observe_quorum_window` (lib/topology.sh:203) previously assumed `clusterSize` was always a quoted string `"clusterSize":"5"`. Jackson can serialize it as an integer `"clusterSize":5`. When the integer form appeared, the grep returned zero matches, the min-size calculation produced no output, and the assertion passed vacuously.

**Fix:** Tier B item B2. The function now tolerates both forms (`"?[0-9]+\"?`) and has a parse-integrity guard: if events DO contain `"clusterSize"` but the regex produces no digits, the function fails closed with a schema-drift message rather than passing vacuously.

### R5 — No baseline isolation between destructive suites

**Symptom (observed 2026-06-15):** A stuck 2-of-5-READY baseline after suite 02-chaos caused suites 03-scaling, 05-security, 12-network, and 13-edge-cases to run against a degraded cluster. Their failures were attributed to product bugs. Two engineering-hours were spent before the stuck baseline was identified as the actual cause.

**Root cause (structural):** The cluster-B loop in `run-tests.sh` (around line 431) called `run_suite "$suite" "b" || true`, swallowing the exit code. The `aborted` flag (line 418) was set in the gate check but the quarantine branch that skips remaining suites on `aborted=true` (line 421) only ran when the flag was set by `restore_cluster_baseline` returning non-zero — which never happened because `restore_cluster_baseline` was called only inside the per-test `trap cleanup EXIT`, not authoritatively in the run-tests.sh loop. The `aborted=true` branch was dead code.

**Fix:** Tier B item B4.

### R6 — Suite authoring friction

**Symptoms (several):**
- `01-stability` has never run in CI since the `run-tests.sh` rewrite. The cluster-B suite array in `run-tests.sh` is hardcoded; `01-stability` requires `--suites 01` explicitly, but the cluster-A parallel array does not derive from `suite.conf cluster=non-destructive` — it is a static list that omits `01-stability` by convention. There is no lint that verifies the static arrays cover all suite.conf-declared suites.
- `parse_suite_conf` (lib/suite.sh, sourced by `run-tests.sh`) uses `source` to pull variables into the calling scope. If a suite's `suite.conf` sets `cluster=destructive`, that value bleeds into subsequent shell state because `source` has no local scope. The `target_cluster` rename workaround (run-tests.sh:255) documents this but does not structurally fix it.
- App ports 8070 (cluster A) and 8080 (cluster B) are hardcoded in `run-tests.sh:281` without documentation of the derivation from `docker.toml`/`docker-b.toml`.
- No suite template exists. Every new suite reinvents the precondition wait pattern.

**Fix:** Tier C items C1 and C2.

### R7 — Provisioning fragility and CLI drift

**Symptoms:**
- No retry on Hetzner HTTP 412 `resource_unavailable` at any harness or product layer. Cloud runs fail non-deterministically when Hetzner returns 412 on server creation during zone saturation events.
- Snapshot zone mismatch: container TOMLs reference `nbg1`, JVM TOMLs reference `fsn1`, and the builder defaults to `fsn1`. An `nbg1` bootstrap attempt that uses an `fsn1` snapshot fails with "image not found" — not a permissions error, not an API error, a silent mismatch.
- PG firewall (port 5432) is opened to `0.0.0.0/0` during bootstrap (run-tests.sh:592 closes it on teardown, but `--skip-teardown` and SIGKILL both leave it open). The window is bounded but the close is not idempotent — a second close attempt after a failed teardown produces a 404 that is currently swallowed.
- `build-aether-vm-snapshot.sh` hand-mirrors `NodeUserDataRenderer`'s cloud-init logic. Every change to `NodeUserDataRenderer` that is not simultaneously applied to the snapshot builder produces a snapshot that boots differently from the production image. This is a drift class with no lint guard.
- Docker and remote environments bootstrap via raw `docker compose up`, not via `aether cluster bootstrap`. Bootstrap regressions (config parsing, quorum formation timing, env-var threading) are only caught on slow cloud runs.

**Fix:** Tier C items C3 and C4.

---

## 4. Completed — Tier B

These four fixes were applied during the RC1/RC2 transition session. They are recorded here so they are not re-implemented and to document their design rationale.

### B1 — Resolver consolidation (DONE)

**Files changed:** lib/common.sh, lib/cluster.sh.

`aether_failover` (lib/common.sh:56) and `rotate_mgmt_entry_point` (lib/cluster.sh:453) previously each contained independent docker/cloud branching logic for finding a reachable management endpoint. Both now delegate unconditionally to `_resolve_live_endpoint` / `_refresh_mgmt_entry_point`. The latent `MGMT_PORT` cloud bug (using docker host-mapped port range instead of `CLOUD_MGMT_PORT` on cloud) was removed as part of this consolidation. Approximately 80 lines of duplicated branching deleted.

### B2 — `observe_quorum_window` parse hardening (DONE)

**File changed:** lib/topology.sh:203–240.

The grep pattern now matches both quoted (`"clusterSize":"5"`) and unquoted (`"clusterSize":5`) integer renderings. A parse-integrity guard (lines 232–235) catches the case where events DO contain `"clusterSize"` but the extended regex produces no digit sequence — indicating schema drift — and returns a fail-closed signal (`FAIL (clusterSize present but unparseable — schema drift)`) rather than passing vacuously. Validated against 6 synthetic cases covering both quoting forms, absent clusterSize (NODE_FAILED), and present-but-broken layout.

### B3 — Silent-failure audit (DONE, no changes required)

The remaining `2>/dev/null || true` guards in lib/common.sh and lib/cluster.sh were audited individually. All fail closed (conservative empty/zero return → visible poll timeout). No additional changes were made. Diagnostic value for the fail-closed cases is delivered by the gate logging added in B4.

### B4 — Cluster-B unrecoverability gate (DONE)

**File changed:** run-tests.sh, cluster-B loop (around lines 418–465).

The `restore_cluster_baseline` call was moved from the per-test trap (where it was advisory) to the authoritative position in the run-tests.sh cluster-B sequential loop. After each destructive suite:

1. `restore_cluster_baseline` is called with its exit code captured (`_restore_rc`).
2. If `_restore_rc != 0`, OR if `cluster_active_core_count` is below the quorum floor, OR if no leader is reachable, `aborted=true` is set.
3. Subsequent destructive suites check `aborted` before running and emit a structured `skip-with-reason` log entry naming the failed restore. The previously-dead `aborted` branch (line 421) is now reachable.

The `run_suite "$suite" "b" || true` pattern is retained for the suite itself (a suite failure is not a cluster failure), but restore failure is now definitively terminal for the cluster-B track.

---

## 5. Tier A — Product Capabilities

Each item in Tier A is a product change that eliminates a root-cause class from Section 3. Each triggers the REST→CLI→Docs triad: `*Routes.java` + `ManagementRoute` enum + `ManagementServer` wiring in `aether/node`; CLI subcommand in `aether/cli/AetherCli.java`; docs in `aether/docs/reference/management-api.md` and `aether/docs/reference/cli.md`.

### A1 — `GET /api/nodes/{id}/endpoint` — nodeId-to-endpoint resolution

**Eliminates:** R1 (bootstrap-state.json reconstruction, `_registered_by_to_offset` ULID blindness, `HCLOUD_TOKEN` dependency at test time).

**Route:**

```
GET /api/nodes/{id}/endpoint
ManagementRoute enum entry: NODE_ENDPOINT_GET
RouteTarget: NodeIdParam(paramIndex=0)  // forward to target node, local handler responds
```

**Handler:** `topologyManager.get(nodeId)` → extract `address()` → return JSON. The forwarding infrastructure from cli-gap-audit.md Phase B (`HttpForwarder.forwardToTargetNode`) applies unchanged.

**JSON response shape:**

```json
{
  "nodeId": "01J4XKRQ...",
  "address": "http://167.233.73.138:8080",
  "reachable": true
}
```

`reachable` is a best-effort TCP probe result (same probe `_resolve_live_endpoint` already performs). The endpoint is useful even when `reachable=false` — it tells the harness where to try, rather than requiring the harness to reconstruct the address.

**CLI command:** `aether nodes resolve <id>` → prints address, exits 0 if reachable, 1 if not.

**Harness replacement:** `cloud_public_ip` and the `_registered_by_to_offset` regex in lib/common.sh:698 can be replaced by `aether nodes resolve <nodeId> --format value --field address`. Works for CTM-provisioned ULID-named replacements without any bootstrap-state or cloud-API dependency.

### A2 — `GET /api/nodes/live` — unified live-node document

**Eliminates:** R2 (zombie/9-vs-2 confusion, `pick_non_leader` 3-source stitch, CTM-replacement invisibility).

**Route:**

```
GET /api/nodes/live
ManagementRoute enum entry: NODES_LIVE
RouteTarget: ANY  // served from any node via existing forward-capable consumer pattern
```

**Handler:** Join `TopologyManager.activePeers()` (address + role) with `MembershipView.members()` (lifecycle FSM state) with SWIM liveness from `SwimProtocol.isAlive(nodeId)`. Return the intersection. Nodes in the FSM but not in `activePeers()` are the zombie class — they appear as entries with `swimAlive=false` and no `address` field.

**JSON response shape:**

```json
{
  "nodes": [
    {
      "nodeId": "01J4XKRQ...",
      "address": "http://167.233.73.138:8080",
      "role": "core",
      "swimAlive": true,
      "reportedState": "ON_DUTY"
    },
    {
      "nodeId": "01J4DEAD...",
      "address": null,
      "role": "core",
      "swimAlive": false,
      "reportedState": "NODE_FAILED"
    }
  ],
  "liveCount": 4,
  "zombieCount": 1
}
```

**CLI command:** `aether nodes live` → table output. `aether nodes live --format json` for scripting. `aether nodes live --only-alive` filters to `swimAlive=true` entries.

**Harness replacement:** `pick_non_leader` (lib/cluster.sh) can be rewritten as a single `aether nodes live --only-alive --format json` call, eliminating the three-source stitch and the `_registered_by_to_offset` ULID regex entirely. The "9 members / 2 VMs" diagnostic becomes `aether nodes live --format json | jq '.zombieCount'`.

### A3 — Versioned cluster-status schema + CI contract test

**Eliminates:** R3 (field rename causing silent wrong-answer in regex-grep parsing).

**Problem statement:** The `id` vs `nodeId` rename during cli-gap-audit.md Phase A caused a real cloud failure where `_resolve_live_endpoint` matched the wrong field. The product has no mechanism to prevent this class: field renames are valid product changes, but they are invisible to grep-based parsing until a cloud run fails.

**Solution (two parts):**

**Part 1 — Frozen field contract.** Define a minimal stability contract for the fields that harness lib functions depend on. These fields MUST NOT be renamed without a major version bump and a corresponding harness update:

| Endpoint | Frozen fields |
|----------|--------------|
| `GET /api/cluster/status` | `leaderId`, `coreCount`, `phase` |
| `GET /api/nodes/status` | `nodeId`, `lifecycleState`, `isLeader` |
| `GET /api/nodes/lifecycle` | array element `nodeId`, `derivedStatus` |
| `GET /api/nodes/live` (new, A2) | `nodeId`, `swimAlive`, `reportedState` |

Document these frozen fields in `aether/docs/reference/management-api.md` under a "Schema stability contract" subsection.

**Part 2 — CI contract test.** Add `aether/tests/integration/lib/contract-test.sh` (a lib-level unit test, no live cluster required) that:

1. Reads the frozen-field list from a companion `schema-contract.toml` file.
2. Grep-scans lib/common.sh, lib/cluster.sh, lib/topology.sh for every field name in the frozen list.
3. Cross-references against the actual JSON shapes returned by the management API (obtained from `mvn test` on `aether-management-api` — the RouteAssemblerTest already exercises all 134+ routes).
4. Fails if a harness grep pattern references a field name not in the frozen list, or if a frozen field is absent from the API's documented response shape.

This test runs as part of `lint-tests.sh` (already invoked by run-tests.sh at line 689), requiring no live cluster.

---

## 6. Tier C — Authoring UX and Provisioning Robustness

These items improve harness reliability and developer experience without requiring product changes.

### C1 — Derive suite routing from `suite.conf cluster=` field

**Problem:** The cluster-A and cluster-B suite arrays in `run-tests.sh` are static hardcoded lists. `01-stability` declares `cluster=non-destructive` in its `suite.conf` but is absent from the cluster-A array — it has silently never run in CI since the array was written. There is no lint guard that detects the omission.

**Fix:**
1. `run-tests.sh` constructs cluster-A and cluster-B arrays dynamically by reading `suite.conf` from each suite directory and filtering on `cluster=non-destructive` / `cluster=destructive`. Remove the static arrays.
2. Add a `lint-tests.sh` rule (R4): verify that every suite directory with a `suite.conf` appears in exactly one of `cluster=non-destructive` or `cluster=destructive`, and that no suite directory lacks a `suite.conf`. This catches 01-stability-class omissions at push time.
3. Update `01-stability` suite.conf to `cluster=non-destructive` (it already is; the bug was the static array not reading it).

### C2 — Shared precondition helper + suite template + CHARTER requirement

**Problem:** Each suite reinvents the same 3-line precondition wait: `wait_for_cluster_ready`, `wait_for_leader`, `wait_for_phase NORMAL`. When this pattern is copy-pasted incorrectly (wrong timeout, missing leader check), the suite runs against a partially-initialized cluster.

**Fix:**
1. Add `assert_cluster_preconditions [timeout]` to lib/suite.sh — a single call that runs `wait_for_cluster_ready`, `wait_for_leader`, and `wait_for_phase NORMAL` in sequence with configurable timeout (default 120s). Suites call this as their first statement.
2. Add `aether/tests/integration/suites/TEMPLATE/` — a copy-paste starting point with `suite.conf`, `CHARTER.md`, and `test-00-preconditions.sh` (calls `assert_cluster_preconditions`).
3. Add a `lint-tests.sh` rule (R5): every suite directory MUST contain a `CHARTER.md`. Fail the lint if absent. (Several suites currently lack one.)

### C3 — Provisioning robustness

**Three independent sub-items:**

**C3a — Hetzner 412 retry.** The `aether cluster bootstrap` cloud provisioner makes a `POST /v1/servers` call per node. Hetzner returns HTTP 412 `resource_unavailable` when zone capacity is momentarily saturated. Add retry with exponential backoff (3 attempts, 5s/15s/45s) in `HetznerComputeProvider.createFrom()` before propagating the error. This belongs in the product (`aether/environment/hetzner`), not the harness. (Corrected 2026-08-12: previously cited `HetznerCloudProvider.provision()` in `aether/aether-cloud`; that class was deleted as dead surface and no such module exists.)

**C3b — Snapshot zone guard.** Before calling `cloud_public_ip` / `aether cluster bootstrap` with a snapshot-based image, validate that the snapshot's `location` field matches the TOML source's `zone` field. Add a pre-flight check (PF-SNAP) to `ClusterBootstrapCommand` that calls `GET /v1/images/{id}` and fails with a clear "snapshot {id} is in zone {actual}, TOML requests zone {expected}" message. Currently the mismatch produces a 422 from Hetzner with an opaque message 20 minutes into bootstrap.

**C3c — PG firewall idempotent close.** The run-tests.sh teardown (line 592) calls the Hetzner firewall close API. If teardown is interrupted (SIGKILL, `--skip-teardown`), port 5432 remains open to `0.0.0.0/0`. Fix: make the close call idempotent (404 is success, not error) and add it to a `trap INT TERM EXIT` at the top of run-tests.sh so it runs even on forced exit. Add a pre-run check that verifies port 5432 is NOT open to `0.0.0.0/0` before starting a cloud run, with a clear error if it is.

### C4 — Dogfood `aether cluster bootstrap` on docker/remote

**Problem:** Docker and remote environments bootstrap via raw `docker compose up` (see run-tests.sh around line 290). `aether cluster bootstrap` regressions (config parsing, quorum formation timing, env-var threading) are only caught on slow cloud runs.

**Fix:** Replace the raw `docker compose up` path in run-tests.sh with `aether cluster bootstrap env/docker.toml` / `env/docker-b.toml`, matching the flow specified in `integration-test-overhaul-v2-spec.md §3`. This is the implementation completion deferred during RC1. Prerequisite: `integration-test-overhaul-v2-spec.md §3.5` (structured bootstrap output via `OutputFormatter`) must land first.

**Note:** The bootstrap CLI already supports `type = "docker"` sources (cluster-bootstrap-spec.md §5.1.5a). The environment templates `env/docker.toml` and `env/docker-b.toml` already exist. This is a run-tests.sh wiring change only.

### C5 — Lib-level unit tests for pure parsers

**Problem:** `observe_quorum_window` (lib/topology.sh:203) now has 6 synthetic test cases (added as part of B2). These tests live in a comment block inside the function. There is no standard home for harness lib unit tests — they require no live cluster, they run in milliseconds, and they should run on every push.

**Fix:** Create `aether/tests/integration/lib/unit-tests.sh` — a standalone script that sources lib files and runs inline test cases using `assert_eq`/`assert_ne`. Add it to the `lint-tests.sh` invocation in run-tests.sh (already at line 689). The `observe_quorum_window` synthetic cases are the seed; add cases for `cluster_active_core_count`, `node_lifecycle_state`, and any new A2 helpers.

### C6 — Fix tautological assertions (R3 rule, 5 sites)

**Problem:** `lint-tests.sh` rule R3 flags `assert_ne <var> ""` where `<var>` is the raw HTTP response body. A non-empty error JSON (`{"error":"..."}`) passes the assertion even though the test intent is to verify a successful response. The CHARTER.md files for the affected suites already document these as AMBER findings.

**Sites to fix:**

| File | Line | Current | Fix |
|------|------|---------|-----|
| `suites/04-streaming/test-stream-replication.sh` | 126 | `assert_ne "$result" ""` | Parse and assert specific field (e.g., `streamId` non-empty) |
| `suites/00-smoke/test-cluster-formation.sh` | 67 | `assert_ne "$status" ""` | Assert `status` HTTP 200 AND `nodeId` field non-empty |
| `suites/00-smoke/test-cluster-formation.sh` | 76 | `assert_ne "$events" ""` | Assert response contains at least one `NODE_JOINED` event type |
| `suites/11-observability/test-transport-metrics.sh` | 17 | `assert_ne "$metrics" ""` | Assert at least one named metric key present |
| `suites/11-observability/test-prometheus-metrics.sh` | 28 | `assert_ne "$body" ""` | Assert `has_metric_line > 0` (already computed on line 29; the guard just needs reordering) |

The `suites/00-smoke/test-slice-deployment.sh:23` `assert_ne "$result" ""` is already partially mitigated by TC-00-010 and TC-00-013 (per CHARTER.md); fix it to `assert_contains "$result" '"status"'` as a minimum.

### C7 — Environment connectivity preflight (diagnose CLI-vs-curl reachability)

**Problem (observed 2026-06-15):** A full remote run failed 00-smoke with a cryptic
`java.net.ConnectException: No route to host` cascade and an aborted-all-suites gate. The
actual cause was **macOS Local Network Privacy (TCC)** blocking the Homebrew `java` binary
(which `aether` CLI execs) from the `192.168.x` LAN where the docker cluster runs — while
`curl`/`api_get` and the cluster itself were completely healthy (raw socket: `java`→LAN =
instant `NoRouteToHost`, `java`→public = OK; `nc`/`curl`→LAN = OK; node logs empty). The
same CLI works on cloud (public IPs). Diagnosis took hours because the harness gave no
signal distinguishing "cluster down" from "this operator machine's CLI can't reach the
cluster."

**Fix:** Add a preflight (after deploy, before suites) that probes the cluster two ways —
raw HTTP (`curl`/`api_get`) and the `aether` CLI — against the same endpoint. The outcomes
are diagnostic:
- both OK → proceed.
- both fail → cluster genuinely unreachable (real failure).
- **curl OK, CLI fails → the operator machine's CLI cannot reach the cluster** (macOS Local
  Network Privacy for a LAN cluster, an HTTP proxy, or IPv6-preference). Emit a clear,
  actionable message ("grant Local Network access to your terminal/java, run the CLI inside
  the cluster network, or use public-IP endpoints") and stop — do NOT run the suites and
  misattribute the cascade.
- curl fails, CLI OK → unusual; surface for investigation.

This converts a multi-hour misdiagnosis into a one-line preflight verdict, and embodies the
spec's UX-proxy thesis: the harness should *diagnose* the environment, not just fail in it.
Related: B4 already hardened its gate to read liveness via `api_get` (curl) rather than the
CLI for exactly this robustness reason.

---

## 7. Sequencing and Priority

### Phase 1 — Immediate (unblock cloud CI reliability)

| Item | Rationale |
|------|-----------|
| B4 (done) | Unrecoverability gate — already prevents the 4-suite cascade failure |
| B2 (done) | `observe_quorum_window` silent-pass — already fixed |
| C3c | PG firewall TTL — low risk, high blast radius if left open |
| C6 | Tautological asserts — small, improves signal quality immediately |

### Phase 2 — RC2 sprint (before next Hetzner campaign)

| Item | Rationale |
|------|-----------|
| A1 | Eliminates bootstrap-state dependency and ULID blindness |
| A2 | Eliminates the zombie/9-vs-2 class entirely |
| C1 | Enables 01-stability in CI; closes the dead-suite class |
| C3a | Hetzner 412 retry — prevents non-deterministic cloud failures |
| C3b | Snapshot zone guard — prevents the 20-minute silent mismatch |

### Phase 3 — RC2 polish (before GA)

| Item | Rationale |
|------|-----------|
| A3 | Schema contract — prevents future field-rename regressions |
| C2 | Suite template + CHARTER requirement — author experience |
| C4 | Bootstrap dogfood on docker — closes the bootstrap-regression blind spot |
| C5 | Lib unit tests — prevents regression of B2-class fixes |

---

## 8. Open Questions

**Q1 — A2 SWIM API surface.** `SwimProtocol.isAlive(nodeId)` is not currently part of the management API contract. The `NODES_LIVE` handler needs either direct access to the SWIM state machine or a new `ClusterHealthView` abstraction that joins FSM state with SWIM liveness. Which layer owns this join? Recommendation: add `SwimAliveQuery` to `TopologyManager` (already knows both FSM and transport state) rather than exposing SWIM internals to the route layer.

**Q2 — A1 reachability probe cost.** `NODE_ENDPOINT_GET` does a TCP probe per call. On a 5-node cluster with a caller polling every 2 seconds this is acceptable. On a 50-node cluster a naive fan-out would be expensive. Scope A1 to single-node point queries only; A2 (`NODES_LIVE`) covers the bulk use case and can cache SWIM liveness from the existing heartbeat cycle rather than probing on demand.

**Q3 — C4 prerequisite ordering.** C4 (bootstrap dogfood) is blocked on `integration-test-overhaul-v2-spec.md §3.5` (OutputFormatter for bootstrap). That item is unscheduled as of this writing. If it slips past RC2, C4 should be scoped down to a local smoke test that calls `aether cluster bootstrap` against a single-node docker target and asserts structured JSON output — cheaper than a full 5-node bootstrap and sufficient to catch the regression class.

**Q4 — `build-aether-vm-snapshot.sh` drift.** The snapshot builder mirrors `NodeUserDataRenderer` but has no automated drift detection. The structural fix is to make the snapshot builder call the CLI (`aether node render-cloud-init`) rather than reimplementing the template. This requires a new CLI subcommand not currently specced. Scope decision needed: RC2 or post-GA?
