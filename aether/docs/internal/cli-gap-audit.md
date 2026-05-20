# CLI / REST surface audit — Phase A canonicalization log + remaining gap inventory

Status: Phase A landed in commit `6dd22fdc7` on `release-1.0.0-rc1`.
Date: 2026-05-19.

This document records what was canonicalized in Phase A, what remains for Phase B, and the full inventory of CLI gaps + test-detection inconsistencies surfaced by the audit. It is the canonical reference for the RC1 surface-consistency cleanup that started from "are tests detecting things consistently?" and pivoted to "is the CLI itself coherent enough that consistent detection becomes possible?"

---

## Phase A — what landed (canonicalization)

### Decisions

1. **Convention**: REST-orthodox plural collections, within the existing tail-params invariant of `ManagementRoute`. Pattern: `/api/<plural-collection>/<verb-or-subresource>/{id}` with id at the trailing path segment. No placeholder-in-middle templates needed.
2. **Hard-cut**, no deprecation window. RC1 is pre-1.0; wire format is allowed to break.
3. **Single source of truth**: `ManagementRoute` enum. Renames there propagate to server-side route registration (Routes classes), client-side URL assembly (CLI `route.assemble(args)`), and the matcher (`RouteMatcher`) automatically. No data-model change required.
4. **Param normalization**: ID-like params under their resource collection get `id` (was `nodeId`, `blueprintId`, `deploymentId`, `testId`, `keyId`, `groupId`, `requestId`). Semantic non-ID params keep their meaningful names (`name`, `partition`, `metric`, `logger`, `key`, `section`, `datasource`, `methodName`, `artifact`, `group`).
5. **Picocli subcommand renames**: top-level CLI nouns pluralized to match REST: `node→nodes`, `blueprint→blueprints`, `backup→backups`, `ab-test→ab-tests`, `artifact→artifacts`, `stream→streams`, `cert→certs`. Mass-noun commands stay singular (`storage`, `config`, `health`). The kebab-case top-level commands `node-slices` and `node-routes` are preserved in Phase A; folding under `nodes` is Phase B.

### Mislabeled endpoints discovered

The audit surfaced four endpoints whose paths/enum names misrepresented their actual data. These were not duplicates — they were two distinct concepts hiding behind misleading names. Phase A repositioned them:

| Old | New (path + enum) | What it actually returns |
|---|---|---|
| `/api/status` (`CLUSTER_STATUS`) | `/api/nodes/status` (`NODE_STATUS`) | **Node-scoped**: this node's uptime, lifecycleState, clusterPhase, isLeader, MetricsSummary |
| `/api/cluster/status` (`CLUSTER_CONFIG_STATUS`) | `/api/cluster/status` (`CLUSTER_STATUS`) | **Cluster-scoped**: desiredCoreCount, actualCoreCount, slicesDeployed, certificate, loadBalancer (path stays, enum renamed) |
| `/api/topology` (`TOPOLOGY`) | `/api/slices/topology` (`SLICE_TOPOLOGY`) | **Slice DAG**: logical wiring graph (nodes + edges), used by visualization |
| `/api/cluster/topology` (`CLUSTER_TOPOLOGY`) | (unchanged) | **Physical cluster layout**: coreCount, workerCount, peer connectivity, epoch |
| `/api/certificate` (`CERTIFICATE`) | `/api/certificates` (`CERTIFICATES_LIST`) | TLS cert status (singleton list now; multi-cert in future) |

The two `*_STATUS` enum entries had names inverted from their semantics — `CLUSTER_STATUS` returned node data, `CLUSTER_CONFIG_STATUS` returned cluster data. Tests and CLI commands have been miscoded around this for the lifetime of the codebase. This is now fixed.

### Path renames applied

**Singular → plural for collections:**
- `/api/node/{lifecycle,drain,activate,shutdown,inflight,slices,routes}` → `/api/nodes/...`
- `/api/blueprint/{*}` → `/api/blueprints/{*}` (plus parameterless variants)
- `/api/backup/{*}` → `/api/backups/{*}`
- `/api/ab-test/{*}` → `/api/ab-tests/{*}`

**Kebab-case standalones folded under their noun:**
- `/api/node-metrics` → `/api/nodes/metrics`
- `/api/artifact-metrics` → `/api/artifacts/metrics`
- `/api/invocation-metrics/*` → `/api/invocations/metrics/*`
- `/api/config/node` → `/api/config/nodes`

**Repositioned per actual content:**
- `/api/status` → `/api/nodes/status` (was node-scoped, now path matches semantics)
- `/api/topology` → `/api/slices/topology` (was slice DAG, now path matches semantics)
- `/api/certificate` → `/api/certificates` (plural-as-rule)

### Scope of change

- **1 commit**: `6dd22fdc7`
- **66 files modified**
- **438 insertions / 438 deletions** (pure substitution, no semantic logic added)
- **0 test failures** across 4 verification rounds (`mvn install` + `mvn test` on `aether-management-api`, `aether/node`, `aether/cli`, `aether/forge`, `aether/http-handler-api`, `aether/cloud-tests`)
- **0 residual old paths** in any tracked code or operator-facing doc (`CHANGELOG.md` and `aether/docs/{specs,internal,archive,contributors,slice-developers}/` deliberately preserved for historical reference)

### Critical production stragglers surfaced (and fixed in Phase A)

These were discovered after the first sweep and would have silently broken the system if missed:
- **`RoutePermissionRegistry.java`** — RBAC permission registry was keyed off old singular paths. After the rename, the registry's path-prefix matching would have failed to resolve permissions for the new plural paths, falling through to ADMIN_ONLY default for every authenticated mutation. **Fixed.**
- **`aether/script/rolling-aether-upgrade.sh`** — 8 live curl calls to old paths in production rolling-upgrade script. **Fixed.**
- **Dashboard JS** (`index.html`, `app.js`, `stores/requests.js`, `stores/cluster.js`, `stores/events.js`) — live RestClient calls to old paths. **Fixed.**
- **`forge/Dockerfile` + `forge/docker-compose.yml`** — healthcheck commands hitting `/api/status` (now `/api/nodes/status`). **Fixed.**
- **`aether/docker/scaling-test/*.sh`** — scaling-test scripts with live API curl. **Fixed.**
- **`DeploymentError.java`** — user-facing error message hardcoding old path. **Fixed.**

### Verification

`PathRearrangementTest.allRoutes_obeyTailParamsInvariant()` — the existing invariant that no enum prefix may contain `{` — continues to pass. The `roundTrip_assembleThenMatch_preservesParams` test in `RouteAssemblerTest` iterates over all enum values and verifies assemble + match consistency; passes for all 134 enum entries.

---

## Phase B — planned, not yet landed

### Per-node variant endpoints

User-approved scope: add parameterized variants for endpoints currently restricted to local-node introspection.

| New enum entry | Path | Behavior |
|---|---|---|
| `NODE_STATUS_GET` | `GET /api/nodes/status/{id}` | Returns status of specified node (forwarded if not local) |
| `NODE_INFLIGHT_GET` | `GET /api/nodes/inflight/{id}` | Returns in-flight request count for specified node |
| `NODE_ROUTES_GET` | `GET /api/nodes/routes/{id}` | Returns route table for specified node |
| `NODE_SLICES_GET` | `GET /api/nodes/slices/{id}` | Returns slice deployments on specified node |
| `NODE_METRICS_GET` | `GET /api/nodes/metrics/{id}` | Returns per-node metrics for specified node |

Each pairs with an existing parameterless variant (`/api/nodes/status` without id = local). No-param = implicit local; with-param = forward to target node.

### Phase B implementation requirements

1. **Add enum entries** to `ManagementRoute.java`.
2. **Implement server-side forwarding handlers** — the routes class needs to recognize incoming `{id}`, determine if local, and forward to target node if not. The HTTP forwarding infrastructure exists (per `HttpForwarder` in `aether-invoke/`); the per-node-by-id routing is the new piece.
3. **Add CLI optional `[id]` argument** to corresponding subcommands. The `aether nodes lifecycle` subcommand already has this pattern (`@Parameters(index = "0", arity = "0..1")`) and can serve as the template.
4. **Update CLI hyphenated top-levels**: consider folding `aether node-slices` → `aether nodes slices [id]` and `aether node-routes` → `aether nodes routes [id]` for full coherence.
5. **Tests**: integration test for each new per-node variant (verify forwarding is correct, response shape matches local-call shape).

Phase B is a separate commit because it introduces new behavior, not just renames.

---

## Remaining open consistency cleanups (not in Phase A or B)

### #23 — Error envelope shape (LANDED)

Resolved in commit (Phase B.1 — error envelope standardization). The framework now emits **RFC 9457 ProblemDetail** with content-type `application/problem+json` for every management-plane error.

**What landed:**
- New utility `aether/node/.../api/routes/ProblemResponses.java` — canonical emission helper used by Router and ManagementServer error sites
- `ManagementRouter.writeError` rewired to emit ProblemDetail (single funnel, propagates to every route automatically)
- `ManagementServer` error sites updated: `sendForwardError`, `sendForwardUnavailable`, `writeProbeJson` failure path, `handleManagementSecurityFailure`, the inline 404 builder
- `NodeLifecycleRoutes` leak fixed: drain/activate state-mismatch (was returning `TransitionResult(success=false)` at HTTP 200) now returns `HttpError.httpError(CONFLICT, ...)` → 409 ProblemDetail
- `aether/cli/.../OutputFormatter.java` updated to recognize ProblemDetail by integer `status` field; legacy `{"error":"..."}` envelope still accepted for backward compat with non-Aether endpoints
- `WWW-Authenticate` header still set for 401 responses (security parity), now alongside ProblemDetail body

**What was NOT included (separate follow-up commits):**
- Sealed `*Error` types (`ClusterConfigError` 24 variants, `KeyNotFoundError`, `UpgradeError`, `WorkerError`, `SecurityError`, etc.) → still default to HTTP 500 via the non-HttpError path. Per-domain `httpStatus()` mapping should land incrementally — each domain owner decides their cause→status table. The envelope shape is correct; only the status code is conservative.
- `BlueprintValidationResponse(valid:false)` left as-is — validation is a read-only query with structured results, not an error.

**Verification:** 736 tests pass (399 node + 337 CLI) post-refactor. Per-route shapes preserved via the funnel; only the wire envelope changed.

### #24 — State authority: MembershipView projection vs KV-direct (decision pending)

The handover doc for session 2026-05-19c flagged this. Currently:
- `/api/nodes/status` (was `/api/status`) returns `cluster.nodes[]` derived from **MembershipView** (a projection over SWIM ∪ KV with cluster-canonical reachability downgrade). Known projection lag.
- `/api/nodes/lifecycle` and `/api/nodes/lifecycle/{id}` read **KV-direct** (canonical FSM state, no lag).

Tests bypass `/api/nodes/status` to read `/api/nodes/lifecycle/{id}` directly because of the lag — meaning the operator-visible API isn't authoritative.

**Decision needed**: either fix the MembershipView projection lag so `/api/nodes/status` is authoritative, or formally demote it ("eventually consistent operator view, use `/api/nodes/lifecycle/{id}` for transition gating") and document.

This is architectural, not a path rename. Belongs in RC1 per the "anything affecting foundation → RC1" rule.

### CLI gaps still open (not addressed in Phase A)

| Gap | Description | Recommendation |
|---|---|---|
| **`aether cluster tasks list/status`** | `/api/cluster/tasks` exists; only `aether cluster tasks reassign` has a CLI command. Tests grep raw JSON via `task_group_status` helper. | Add `aether cluster tasks list` + `aether cluster tasks status <group>` subcommands |
| **`aether cluster topology view`** | `/api/cluster/topology` has no CLI reader. Tests use `aether status --field cluster.coreCount` workaround. | Add `aether cluster topology view` or `aether topology status` (rename current `topology` namespace) |
| **`aether nodes health [id]`** | `aether health` returns cluster-aggregated only. Tests raw-curl `/health/live` and `/health/ready` per-node. | Add `aether nodes health [id]` for per-node readiness, with `--component` filter |
| **`aether events --follow`** | Events are polled. Test infra (`topology_events_since`, `wait_for_node_departure`) does per-node fan-out polling. | Add `--follow` for streaming/SSE; would canonicalize ~10 helpers and ~15 test assertions |
| **`aether blueprints contains <coords>`** | `aether blueprints list` returns everything; `push_blueprint` test helper greps stderr for `already exists`. | Add `aether blueprints contains <coords>` returning 0/1 for idempotent visibility check |
| **`aether slices filter`** | `slices_total_instances`/`slices_active_instances` test helpers grep raw JSON for `state="LOADED"` or `"ACTIVE"`. | Add `aether slices --filter state=ACTIVE` or `aether slices count --state ACTIVE` |
| **`aether nodes by-state <state>`** | `pick_non_leader` helper greps `/api/status` for `lifecycleState=ON_DUTY`. | Add `aether nodes --state ON_DUTY` filter |

### API gaps — system doesn't expose what tests need

| Gap | Test workaround |
|---|---|
| `SelfDrainInitiated` event not published | `test-self-drain-quorum-loss.sh` greps docker logs for literal string `"Self-drain: DRAINING on"` |
| `/api/config` returns free-form JSON without schema | Callers must treat as untyped tree |
| `/api/metrics/transport` returns implementation-dependent Map keys | Same — untyped tree |
| `/api/metrics/history` returns nested untyped Map | Same |
| No per-node SWIM probe-time field exposed | Two-path issue between SwimFaulty and TransportUnreachable (RC2 #224 architectural finding) |

These need API-side additions; CLI can't surface what the server doesn't emit.

### Test-detection inconsistencies still present (will be addressed once gaps above close)

Cross-suite divergence on the same physical state, surfaced by the initial audit pass and recorded for next iteration:

| Class | Methods in use across suites | Resolution path |
|---|---|---|
| **"Cluster is ready"** | `is_cluster_ready` (count+leader+ON_DUTY) vs `wait_for_cluster` (count+leader+healthy) vs `wait_for_all_nodes_ready` (per-node `/health/ready`) | Define one canonical readiness contract, fold helpers |
| **Node departure** | `NODE_LEFT` ∪ `NODE_FAILED` via events vs `reason=transport-failure` OR `reason=swim-faulty` widening | Belongs to RC2 #224 two-path finding |
| **Node count** | `cluster_node_count` (generation members) vs `cluster_node_count_on_duty_healthy` (topology coreCount) | Depends on State Authority decision (#24) |
| **Health endpoint zoo** | `/health/live` vs `/health/ready` vs `/api/health` vs `/api/nodes/status` — different suites use different ones for the same intent | Document semantic contract per endpoint; fold to canonical |
| **App route wired** | `/api/echo/health == 200` vs PUT-then-retarget vs body-grep `"title":"No route found for "` | Resolved by error-envelope standardization (#23) |
| **Error rate thresholds** | 1% (soak) / 2% (scale-down) / 5% (cert) / 10% (kill) — no documented rationale | Either document rationale per test class or normalize |
| **Drain-trigger signal** | Log-grep `"Self-drain: DRAINING on"` smoking gun | Resolved by adding `SelfDrainInitiated` event |

---

## Document maintenance

- When Phase B lands, update "Phase B — planned" section to past tense and append commit ref.
- When #23 (error envelope) and #24 (state authority) are decided, append decisions below the open-question subsection and remove from "pending".
- New CLI gap fixes go in the "CLI gaps still open" table — mark each row resolved with commit ref.
- New API gap fixes go in the "API gaps" table same way.

Source of audit: `aether-management-api/src/main/java/.../ManagementRoute.java`, `aether/cli/src/main/java/.../AetherCli.java`, `aether/tests/integration/lib/*.sh`, `aether/docs/reference/{cli,management-api}.md`. Run `git log --oneline aether/aether-management-api` to see the route-surface evolution.
