# Session handover — 2026-05-20

Branch: `release-1.0.0-rc1`
HEAD: `c332fac60`
Candidate tag: `v1.0.0-rc1-candidate` → `c332fac60`

## Topline

**Five work waves landed today on `release-1.0.0-rc1`:**

**Wave 1 — CLI / REST surface consistency audit closeout** (commits `6dd22fdc7` … `caedfa62a`, summarized in §"Wave 1" below). Six commits closing out the audit including a $2500 post-audit self-review.

**Wave 2 — Tier 1 RC1-foundational follow-ups** (commits `f563841b4` … `1bca1b4e1`). Five commits closing every Tier 1 item that was queued at the end of Wave 1: `HttpStatusAware` mixin + status-code mapping across 4 sealed `*Error` hierarchies; rename `StatusResponse.lifecycleState` → `runtimeState` with a separate KV-direct FSM `lifecycleState` field; same `kvState`/`derivedStatus` split for `ClusterStatusNodeInfo` in `/api/cluster/status` (plus discovery that the field was a `"ON_DUTY"` hardcoded stub — fixed); CLI namespace consolidation folding `node-{slices,routes,inflight,metrics}` under `aether nodes <verb> [id]` and resolving a latent `NodesCommand`/`NodeCommand` `@Command(name="nodes")` clash via merge.

**Wave 3 — Tier 2 operator parity batch** (commits `8c172328f` … `023c819cc`). Four commits closing four Tier 2 CLI/API gaps: idempotent artifact push (200 + `{status: uploaded|already-present}` instead of stderr-grep-bait); `aether cluster tasks list/status` CLI subcommands with client-side filter; `?state=` query param on `/api/slices` + matching CLI `--state` flag; per-node `/health/{ready,live}/{id}` endpoints + `aether nodes health [id]` CLI using the Phase B `NodeIdParam` forwarding pattern. Plus inline TASKS_TABLE column-field bug fix during the cluster-tasks work.

**Wave 4 — CLI finishing batch** (commits `efb5e1438` … `c9b8ae7cc`). Three commits closing the remaining CLI consistency gaps: `aether slices --state` extended to accept `+`-separated multi-state union (e.g. `LOADED+ACTIVE` — eliminates the last raw-JSON LOADED|ACTIVE union grep on the test side); `aether nodes lifecycle --state X[+Y]` filter (server query param + CLI option + `RouteFilters` shared helper extracted at the 2nd callsite); top-level `aether topology` moved under `aether cluster topology` namespace for discovery.

**Wave 5 — Test-side detection consolidation** (commits `f9954a8b6` … `c332fac60`). Four commits implementing the canonical detection contract for integration tests: new `aether/docs/specs/test-readiness-contract.md` spec defining "cluster is ready" (4-property composite), "node count" vocabulary (`cluster_member_count` vs `cluster_active_core_count`), health endpoint contract (`/health/live` vs `/health/ready` vs `/api/health` vs `/api/nodes/status`), and error rate threshold tier table; 61-file shell refactor folding three readiness helpers into canonical `wait_for_cluster_ready` + renaming both node-count helpers per spec; inline rationale comments at all 6 `MAX_ERROR_RATE=` sites citing the spec §4 tier; new `SELF_DRAIN_INITIATED` cluster event emitted from `SelfDrainCoordinator.initiateDrain` (eliminates the `docker logs | grep "Self-drain: DRAINING on"` workaround in `test-self-drain-quorum-loss.sh`).

## Commits — Wave 5 (Test-side detection consolidation)

| Commit | Subject | Scope |
|---|---|---|
| `f9954a8b6` | `docs: add test-readiness-contract spec — canonical definitions for cluster ready, node count, health endpoints, error rate thresholds` | New 180-line spec at `aether/docs/specs/test-readiness-contract.md`. §1 canonical "cluster is ready" (4-property composite: generation members ≥ N, leader elected, active cores ≥ N-1, every node /health/ready UP). §2 node-count vocabulary (member_count = generation includes JOINING; active_core_count = topology ON_DUTY+HEALTHY). §3 health endpoint contract per endpoint. §4 error rate threshold tiers (1% soak / 2% operational / 5% mid-flight ops / 10% chaos). §5 quick reference table. §6 future work (T3.1 SelfDrainInitiated + RC2 #224 deferred). |
| `ebe551c6d` | `refactor(test-infra): canonical wait_for_cluster_ready + rename cluster_member_count/cluster_active_core_count per test-readiness-contract spec` | 61 files modified (lib + 56 suite scripts). New `wait_for_cluster_ready` helper implementing all 4 spec properties as one composite predicate. Deprecated aliases `is_cluster_ready` / `wait_for_cluster` / `wait_for_all_nodes_ready` retained as shims. Renames: `cluster_node_count` → `cluster_member_count`, `cluster_node_count_on_duty_healthy` → `cluster_active_core_count`. Zero remaining old-name hits. macOS BSD sed `\b` issue worked around via perl. Trade-off: per-port readiness diagnostic spam from the old loop is dropped in favor of fail-fast atomicity (recoverable via deprecated alias). |
| `afe007095` | `docs(test-infra): inline rationale comments at MAX_ERROR_RATE sites citing test-readiness-contract §4` | 6 suite files (test-soak-4h, test-streaming-soak, test-03-scale-down, test-kill-under-load, test-stream-under-load, test-cert-rotation). Each `MAX_ERROR_RATE=` gets a 2-line comment citing spec §4 tier + disruption-magnitude rationale. |
| `c332fac60` | `feat(aether): SELF_DRAIN_INITIATED cluster event + test migrates off docker-log grep` | New `ClusterEventValue.EventType.SELF_DRAIN_INITIATED` variant (severity WARNING). New narrow `SelfDrainEventPublisher` `@FunctionalInterface` in `aether-deployment/.../drain/` (avoids `aether-deployment` → `aether-node` cycle). `SelfDrainCoordinator.initiateDrain` publishes after the existing log.warn; wrapped try-catch so a throwing publisher doesn't interrupt the drain. `AetherNode` wires via forward-declared `AtomicReference<ClusterEventLogPublisher>` resolved lazily at publish time. **NOT leader-gated** (rationale: the draining node is the only authoritative source; partition victims wouldn't reach the leader). Test `test-self-drain-quorum-loss.sh` migrates from `docker logs | grep` to `wait_for_self_drain_event` polling `/api/events` for `type=SELF_DRAIN_INITIATED + details.nodeId=<n>`. Event is a SOFT signal under quorum loss (Rabia may not commit before halt(2)); exit-code-2 + container-exit remain HARD contract. 20 tests in `SelfDrainCoordinatorTest` (up from 17 — 3 new EventEmission tests). |

### Tier 5 (test-detection) backlog status

Of the test-detection inconsistencies enumerated at the end of Wave 1:
- ✅ "Cluster is ready" — three helpers folded into canonical `wait_for_cluster_ready` (`ebe551c6d`)
- ✅ Node count divergence — renamed for semantic clarity per spec §2 (`ebe551c6d`)
- ✅ Health endpoint zoo — semantic contract documented per endpoint in spec §3 (`f9954a8b6`)
- ✅ Error rate thresholds — tier table written + inline rationale comments at every site (`f9954a8b6` + `afe007095`)
- ✅ Drain-trigger signal — `SELF_DRAIN_INITIATED` event published, test migrated (`c332fac60`)
- ✅ Slice instance state grep — `slices_total_instances` LOADED+ACTIVE union migrated via Wave 4 `efb5e1438`
- ✅ Task group status raw-JSON grep — migrated via Wave 3 `b82c0262a`
- 🚧 Node departure widening (NODE_LEFT vs NODE_FAILED + reason=transport-failure vs swim-faulty) — explicitly RC2-bound (#224 two-path SwimFaulty vs TransportUnreachable architectural finding)

The only remaining test-detection inconsistency is the one explicitly deferred to RC2.

## Commits — Wave 4 (CLI finishing batch)

| Commit | Subject | Scope |
|---|---|---|
| `efb5e1438` | `feat(aether): aether slices --state supports +-separated multi-state union` | `--state LOADED+ACTIVE` (or any `+`-joined combination) filters server-side via uppercase split-on-`+` Set membership. Single-state queries continue working unchanged. `slices_total_instances` shell helper migrated from raw-JSON `(LOADED\|ACTIVE)` grep to `--state LOADED+ACTIVE`. |
| `442c20f72` | `feat(aether): aether nodes lifecycle --state filter (multi-state via +) + RouteFilters shared helper` | Adds `?state=` query param on `/api/nodes/lifecycle` (multi-state via `+`) + `--state` option on `aether nodes lifecycle`. Extracts `RouteFilters.parseStateFilter` to a shared helper at the 2nd callsite (Slice + NodeLifecycle routes); `SliceRoutes` migrated to use it. `pick_non_leader` shell helper migrated from `/api/nodes/status` derivedStatus grep to `aether nodes lifecycle --state ON_DUTY`. |
| `c9b8ae7cc` | `refactor(aether/cli): move topology under cluster namespace (aether topology -> aether cluster topology)` | RC1 hard-cut rename. `TopologyStatusCommand` moved from `AetherCli.java` inner class to new top-level file `aether/cli/cluster/ClusterTopologyCommand.java`; registered as subcommand of `ClusterCommand`. Subcommands `circuit-breaker` (status/reset) and `auto-heal` (status/enable/disable) follow. 5 files modified. Zero remaining `aether topology ` callers anywhere. |

## Commits — Wave 1 (audit closeout, in order)

## Wave 1 — Audit closeout (commits `6dd22fdc7` … `caedfa62a`)

The headline architectural deliverables:

1. **Canonical REST surface** — every collection plural; every ID-like param named `id`; every misleading enum/path repositioned to match its actual content.
2. **RFC 9457 ProblemDetail envelope** — all management-plane errors share one wire shape.
3. **Two-endpoint state authority contract** — `/api/nodes/lifecycle/{id}` is KV-direct FSM authority; `/api/nodes/status cluster.nodes[]` carries both `kvState` (authoritative) and `derivedStatus` (operator view with reachability overlay) side-by-side.
4. **Per-node variant endpoints** — `NodeIdParam` routing primitive lets `aether status <id>` work from any node via the cluster forwarder.

## Commits — Wave 1 (audit closeout, in order)

| Commit | Subject | Scope |
|---|---|---|
| `6dd22fdc7` | `refactor(aether): canonicalize REST paths (plural collections) and rename CLI subcommands (Phase A)` | 66 files, 438/438. Path renames, picocli renames, RBAC registry update, dashboard JS, Docker healthchecks, rolling-upgrade script. |
| `6459cb9a7` | `docs: cli-gap-audit log capturing Phase A canonicalization and remaining gaps` | New `aether/docs/internal/cli-gap-audit.md`. |
| `55e0909bb` | `refactor(aether): RFC 9457 ProblemDetail envelope for management-plane errors` | 6 files. `ProblemResponses.java` utility, `ManagementRouter.writeError`, `ManagementServer` 4 sites, CLI `OutputFormatter` parses ProblemDetail, `NodeLifecycleRoutes` drain/activate leak fix (200→409). |
| `0fc1c8a9b` | `refactor(aether): state authority cleanup (kvState/derivedStatus split, KV-direct lifecycle list, SHUTTING_DOWN collapse)` | 6 files. New `state-authority.md` spec; `NodeInfo` shape change; `/api/nodes/lifecycle` mass form switched to KV-direct; `SHUTTING_DOWN`→`DRAINING` normalization at API boundary; `lib/cluster.sh` field renames. |
| `56d0f4221` | `feat(aether): per-node variant endpoints (NodeIdParam routing + forwarder + 5 CLI commands)` | 11 files. New `RouteTarget.NodeIdParam(int paramIndex)`. `HttpForwarder.forwardToTargetNode`. `ManagementServer.tryForwardIfNotTargetNode`. 5 new enum entries (`NODE_STATUS_GET`, `NODE_INFLIGHT_GET`, `NODE_SLICES_GET`, `NODE_ROUTES_GET`, `NODE_METRICS_GET`). 5 route handlers. 4 CLI commands gaining optional `[id]` (`status`, `node-slices`, `node-routes`) + 2 new top-levels (`node-inflight`, `node-metrics`). New error variants `NotLocalTarget` / `TargetDisconnected`. |
| `8399594cc` | `docs: session handover 2026-05-20 — CLI/REST surface consistency audit closeout` | Initial handover doc (this file in pre-self-review state). |
| `95f5f4f9d` | `fix(aether): dashboard reads renamed NodeInfo.derivedStatus + flag misnamed StatusResponse.lifecycleState field` | **Post-audit self-review under $2500 challenge** caught two issues missed by Maven test verification: dashboard JS silently defaulted to `ON_DUTY` for every node because `n.lifecycleState` field was renamed to `derivedStatus`/`kvState` in `#24` but JS wasn't updated (Maven doesn't lint JS); and the top-level wire field `StatusResponse.lifecycleState` is misnamed — it carries `NodeState` (in-memory JVM runtime) not `NodeLifecycleState` (FSM). Dashboard fixed; field-name fix queued as follow-up. |

Test counts at each step: **386 → 736 → 736 → 785** (each commit verified independently via `build-runner` agent).

## Commits — Wave 2 (Tier 1 RC1-foundational closeout)

| Commit | Subject | Scope |
|---|---|---|
| `f563841b4` | `feat(http-routing): HttpStatusAware mixin + ClusterConfigError httpStatus() mapping` | New `HttpStatusAware extends Cause` interface in `integrations/http-routing/`. `HttpError extends HttpStatusAware` with `default httpStatus() { return status(); }` — single dispatch covers both. `ProblemResponses.resolveStatus` updated. `ClusterConfigError` (32 variants) extends `HttpStatusAware` with interface-default `BAD_REQUEST` + 11 per-variant overrides: `ClusterNotFound`→404, `VersionConflict`/`ClusterAlreadyExists`/`QuorumSafetyViolation`/`ImmutableFieldChange`/`UpgradeInProgress`→409, `BootstrapFailed`/`SecretResolutionFailed`/`CloudCredentialsMissing`→500, `ProvisionTimeout`/`QuorumTimeout`→504. Added `http-routing` dep to `aether-config/pom.xml` (architectural call documented). 1016 tests green. |
| `9af8a802f` | `feat(aether/node): cascade HttpStatusAware to WorkerError, SecurityError, ManagementServerError` | Three sealed types in `aether/node` (no new pom deps). `WorkerError.General` enum gained per-constant `HttpStatus status` field (state-machine variants→409, BOOTSTRAP_FAILED→500, GOVERNOR_UNAVAILABLE/NetworkFailure→503, ConfigurationError→400). `SecurityError` default 401; `AccessDenied`/`InsufficientRole`→403; `JwksFetchFailed`→500. `ManagementServerError` default 500; `MissingField`/`InvalidArtifactPath`→400; `NotLeader`→409; `StrategyChangeNotSupported`→501. **Skipped** `RateGuardError` (slice-api Apache-2.0 dev-facing surface; single variant) and `MetricsError` (single variant, would need new pom dep for one mapping). |
| `3f6d91653` | `refactor(aether): split StatusResponse runtimeState/lifecycleState fields` | Resolves the field-name finding from `95f5f4f9d`'s post-audit self-review. Top-level `lifecycleState` field renamed to `runtimeState` (carries `NodeState`: STARTING/JOINING/ACTIVE/DRAINING/STOPPED). NEW `lifecycleState` field added carrying authoritative FSM state from KV-Store (`NodeLifecycleState`: JOINING/ON_DUTY/DRAINING/DECOMMISSIONED/FAILED_DRAIN; SHUTTING_DOWN normalized to DRAINING; empty string when no KV entry yet). Constructor arity 12→13. Sweep across docs + CHANGELOG; CLI uses generic JSON rendering so no Java consumer broke; dashboard already consumes per-node fields, unaffected. |
| `2f1b43f56` | `refactor(aether): split ClusterStatusNodeInfo kvState/derivedStatus (and actually populate)` | `/api/cluster/status` `nodes[]` gets the same `kvState`/`derivedStatus` split as `/api/nodes/status` `NodeInfo` from `#24`. **Critical sub-finding**: `ClusterConfigRoutes.toStatusNodeInfo` was hardcoding `lifecycleState="ON_DUTY"` for every node — long-standing stub. Now properly reads `NodeLifecycleKey` from kvStore + computes `derivedStatus` with reachability overlay (mirrors `StatusRoutes.toNodeInfo`). `ClusterStatusNodeInfo.role` is still hardcoded `"core"` — follow-up TODO noted inline + in CHANGELOG. |
| `1bca1b4e1` | `refactor(aether/cli): fold kebab top-levels under nodes namespace; resolve NodesCommand/NodeCommand clash` | `AetherCli.java`: merged two `@Command(name="nodes")` classes (`NodesCommand` + `NodeCommand`) that were silently shadowing each other in picocli into a single `NodesCommand`. Default behavior preserved (`aether nodes` lists active nodes). Kebab top-levels `node-{slices,routes,inflight,metrics}` moved as `nodes <verb>` inner subcommands. Sweep across `cli.md` + `cli-gap-audit.md` + CHANGELOG. No remaining `aether node-{slices,routes,inflight,metrics}` references anywhere. |

Test counts: **399 (node) + 337 (cli) + 280 (config) = green at every Wave 2 step**.

### Tier 1 backlog: CLEARED

All four Tier 1 items from the Wave 1 handover are now landed (see Wave 2 commits above):
- ✅ Sealed `*Error` httpStatus() mapping (`f563841b4` + `9af8a802f`)
- ✅ `StatusResponse.lifecycleState` → `runtimeState` + genuine FSM `lifecycleState` (`3f6d91653`)
- ✅ `ClusterStatusNodeInfo.lifecycleState` → `kvState`/`derivedStatus` (`2f1b43f56`) + bonus stub-discovery + fix
- ✅ Fold kebab `aether node-{slices,routes,inflight,metrics}` under `aether nodes <verb>` (`1bca1b4e1`) + bonus NodesCommand/NodeCommand merge

## Commits — Wave 3 (Tier 2 operator parity batch)

| Commit | Subject | Scope |
|---|---|---|
| `8c172328f` | `feat(artifact-repo): idempotent artifact push (200 with status uploaded\|already-present)` | Reframing: started as "blueprints contains" but survey revealed (a) blueprint coords aren't persisted (would need schema change), (b) the actual problem lives at the ARTIFACT upload layer not blueprint publish, (c) blueprint publish via KV-Store Put is already idempotent. Real fix: `PUT /repository/...` now checks `ArtifactStore.exists()` first, returns 200 + `{"status":"already-present","coords","size","md5","sha1"}` on duplicate. New `ArtifactPushResponse` record. New `MavenResponse.json(byte[])` factory. New `ArtifactStore.metadata(Artifact)` lightweight accessor. `MavenProtocolRoutes` content-category honored from response (not hardcoded BINARY). CLI `PushArtifactCommand` emits aggregate `--format json` shape. `push_blueprint` shell helper drops legacy `already exists\|409\|conflict\|duplicate artifact` regex; reads JSON `.status` field instead. 9 files / 351 ins / 39 del. |
| `b82c0262a` | `feat(cli): aether cluster tasks list/status subcommands + fix TASKS_TABLE column field mappings` | `aether cluster tasks list` (explicit form of existing default) + `aether cluster tasks status <group>` (client-side filter using brace-depth-counter JSON extractor, no Jackson dep). 5 new tests for the filter. Inline bug fix during work: `TASKS_TABLE` columns were keyed off `assignedNode`/`since` but server emits `assignedTo`/`assignedAt` — pre-existing dead-render that the new commands surfaced. `task_group_status` shell helper migrated from raw-JSON grep to CLI `--format value --field assignments.0.status`. 342 tests (up from 337). |
| `262826e65` | `feat(aether): /api/slices state filter + aether slices --state CLI flag` | Server-side instance-level filter (case-insensitive). Slices with no matching instances drop from the response when filter present. CLI `--state` mirrors `EventsCommand` query-string pattern. `slices_active_instances` shell helper migrated; `slices_total_instances` deliberately left on full list (LOADED+ACTIVE union not expressible as single-state filter — documented in commit). |
| `023c819cc` | `feat(aether): per-node /health/{ready,live}/{id} endpoints + aether nodes health [id] CLI` | Two new `ManagementRoute` enum entries (`HEALTH_READY_GET`, `HEALTH_LIVE_GET`) using Phase B `NodeIdParam(0)` forwarding. Routes register the existing `buildReadinessResponse` / `buildLivenessResponse` handlers — id param is ignored at handler time because forwarder has already routed. `NodesCommand.HealthCommand` inner static class with optional `[id]` arg + `--liveness` flag. `lib/cluster.sh` raw-curl loops intentionally NOT migrated — documented as a per-iter latency concern (~50ms curl vs ~5-15s CLI cold-start). 399 tests green; `RouteAssemblerTest.roundTrip_assembleThenMatch_preservesParams` exercises the new entries via `ManagementRoute.values()` iteration. |

### Tier 2 backlog status

Of the five Tier 2 items from the Wave 1 handover backlog:
- ✅ `aether cluster tasks list/status` — `b82c0262a`
- ✅ `aether nodes health [id]` — `023c819cc`
- ✅ `aether slices --filter state=` — `262826e65` (as `--state`)
- ✅ `aether blueprints contains <coords>` — reframed and landed as artifact-push idempotency (`8c172328f`) which fixes the actual underlying problem more cleanly
- 🚧 `aether events --follow` — deferred: survey revealed no SSE/streaming primitive exists in `integrations/http-routing/`; would require new chunked-encoding response infrastructure (RC2-scope per session decision)

## Key files for next session

| File | Why it matters |
|---|---|
| `aether/docs/internal/cli-gap-audit.md` | **Master backlog** of remaining surface-consistency work. All open items live here. |
| `aether/docs/specs/state-authority.md` | The two-endpoint contract (`kvState` vs `derivedStatus`). New text — operators / test writers MUST read this before designing checks against node lifecycle. |
| `aether/aether-management-api/src/main/java/.../ManagementRoute.java` | The single source of truth for REST routes. 134 enum entries. |
| `aether/aether-management-api/src/main/java/.../RouteTarget.java` | New sealed variant `NodeIdParam` added. Routing model documented. |
| `aether/node/src/main/java/.../routes/ProblemResponses.java` | Canonical ProblemDetail emission helper. All management error sites flow through this. |
| `aether/cli/src/main/java/.../OutputFormatter.java` | CLI parses ProblemDetail by integer `status` field; legacy `{"error":"..."}` still accepted for non-Aether endpoints. |

## Outstanding items, by priority

### Tier 1 — CLEARED in Wave 2

All Tier 1 items landed. See "Tier 1 backlog: CLEARED" subsection above for the commit mapping. Remaining sub-stubs (none load-bearing for RC1):
- `ClusterStatusNodeInfo.role` is still hardcoded `"core"` — should read from `ActivationDirectiveValue` like `EnrichedNodeInfo.role` does. Documented inline as TODO.
- `RateGuardError` and `MetricsError` deliberately not made `HttpStatusAware` — rationale recorded in `9af8a802f` commit message. Funnel-side mapping can be added later if/when these surface through `ProblemResponses`.

### Tier 2 — CLI / API gaps (operator parity)

From the audit doc's "CLI gaps still open" table:

| Gap | Recommendation |
|---|---|
| `aether cluster tasks list/status` | Add subcommands; `/api/cluster/tasks` REST already exists |
| `aether nodes health [id]` | Per-node `/health/ready` reader; cluster-only `aether health` exists today |
| `aether events --follow` | SSE / streaming endpoint + CLI `--follow` flag; replaces poll loops |
| `aether blueprints contains <coords>` | Visibility check; replaces stderr grep in `lib/cluster.sh:push_blueprint` |
| `aether slices --filter state=ACTIVE` | Server-side filter instead of grep on JSON |

### Tier 3 — API surface gaps (system doesn't expose)

| Gap | Test workaround today | Recommendation |
|---|---|---|
| `SelfDrainInitiated` event not published | `test-self-drain-quorum-loss.sh` greps docker logs for literal `"Self-drain: DRAINING on"` | Publish the event; CLI/operators can subscribe |
| `/api/config` returns free-form JSON | Callers treat as untyped tree | Type the config schema |
| `/api/metrics/transport` implementation-dependent keys | Same | Document the schema or type it |
| `/api/metrics/history` nested untyped Map | Same | Same |

### Tier 4 — Future-hardening, RC2-ish

| Item | Where | Notes |
|---|---|---|
| **B5 indexing** for `MembershipView` per-peer reads | `MembershipView.java:247-255` | TODO comment placed in `StatusRoutes.java`. RC2 perf concern for clusters past ~hundreds of peers. |
| Two-path SwimFaulty vs TransportUnreachable architectural finding | (RC2 ticket #224 per earlier handover) | Pending; recommended Option B (multi-signal verification) per session 2026-05-19d. |

### Tier 5 — Test-detection inconsistencies that resolve as Tier 1-3 close

Original audit found ~10 classes of inconsistency in test-side detection (catalog in `cli-gap-audit.md`). Many of these resolve as the canonical CLI / API gaps close:
- "Cluster is ready" — 3 different definitions across helpers (`is_cluster_ready`, `wait_for_cluster`, `wait_for_all_nodes_ready`)
- Node departure — `NODE_LEFT` vs `NODE_FAILED` lumping; `reason=transport-failure` OR `reason=swim-faulty` widening (touches RC2 #224)
- Health endpoint zoo (`/health/live` vs `/health/ready` vs `/api/health` vs `/api/nodes/status`)
- Error rate thresholds — 1% / 2% / 5% / 10% across suites with no documented rationale
- Drain trigger via log grep — resolves once `SelfDrainInitiated` event lands

## Architectural learnings worth retaining

These came up during today's audit work and are worth preserving as project memory if not already:

1. **MembershipView is intentionally not authoritative.** Post-`3f3142ded`: "KV is authoritative; SWIM is fast confirmation; aggregator is second confirmation; absence ≠ demotion." Tests that need FSM truth use `/api/nodes/lifecycle/{id}`; tests that need operator-visible state use `/api/nodes/status`. Both are correct uses — `pick_non_leader` style helpers use status; transition gates use lifecycle.

2. **The framework already had `ProblemDetail`.** It was wired in for slice user-routes via `AppHttpServer.sendProblem`. Management plane just wasn't using it. Single funnel-point change (`ManagementRouter.writeError`) propagated the new envelope to all routes that flow through it.

3. **`PathRearrangementTest.allRoutes_obeyTailParamsInvariant`** is a deliberate, tested rule: no enum prefix may contain `{`. This made route canonicalization a pure rename pass — `RouteAssembler` / `RouteMatcher` / their tests didn't need changes.

4. **`NodeIdParam` routing piggybacks on existing infrastructure.** The `forwardToSpecificNode(NodeId)` primitive was already there (used by `forwardToLeader`). The new variant just declares "extract NodeId from this path-param index" — no new transport, no new RPC, no new index. Reuses the cluster network address-by-NodeId capability.

5. **`SHUTTING_DOWN` is internal-only.** Per `cluster-membership-fsm-spec.md` §R6, the FSM no longer emits it (folded into `DRAINING`). Only the `NODE_SHUTDOWN` API action writes it transiently before `NodeDeploymentManager` observes the write and triggers `halt(2)`. External viewers see `DRAINING`.

6. **Maven test surface doesn't cover JS / HTML / Dockerfile / shell scripts.** Surfaces compiled-by-build-tool get verified; everything else needs grep-after-renames discipline. The $2500 self-review caught a silent dashboard regression that 4 prior verification rounds missed because JS isn't linted. Project memory: after any wire-format rename, grep extension classes `*.js`, `*.html`, `*.sh`, `Dockerfile`, `*.yml` for the old field name, not just `*.java`.

7. **`NodeState` vs `NodeLifecycleState` are different enums.** `NodeState` is the in-memory JVM runtime state machine (STARTING/JOINING/ACTIVE/DRAINING/STOPPED) defined at `aether/node/.../lifecycle/NodeState.java`. `NodeLifecycleState` is the cluster-level KV-Store FSM state (JOINING/ON_DUTY/DRAINING/DECOMMISSIONED/SHUTTING_DOWN/FAILED_DRAIN) defined at `aether/slice/.../kvstore/AetherValue.java`. They overlap on JOINING/DRAINING names but mean different things. `StatusResponse.lifecycleState` (top-level wire field) carries `NodeState`, not `NodeLifecycleState` — the name is misleading and needs renaming.

## Suggested next-session opener

With Tier 1 (foundational), Tier 2 (operator parity), AND Tier 5 (test-side detection) effectively closed, the surface work for RC1 is in a clean state. The natural next moves:

1. **Pre-release smoke** — run the full integration suite against the remote Docker cluster to catch any regressions from today's broad surface changes (wire-format renames in §1-3, helper renames in §5, event additions). 14 suites; one full pass is the right confidence builder before tagging RC1.
2. **`aether events --follow` (SSE infrastructure)** — the deferred Wave 3 item. Requires building a chunked-response primitive in `integrations/http-routing/` + an event-aggregator subscription API. RC2-scope but operator-facing.
3. **Tier 3 — typed schemas for free-form JSON responses** — `/api/config`, `/api/metrics/transport`, `/api/metrics/history` currently return untyped trees. Typing them improves CLI rendering and operator tooling.
4. **RC2 #224** — two-path SwimFaulty vs TransportUnreachable architectural finding; gates the last test-detection inconsistency (node departure widening).

## Session metadata

- Date: 2026-05-20 (single working day, five waves)
- Commits: Wave 1 (audit closeout) = 6 substantive + 1 handover + 1 self-review fix = 8. Wave 2 (Tier 1 closeout) = 5 substantive. Wave 3 (Tier 2 batch) = 4 substantive. Wave 4 (CLI finishing) = 3 substantive. Wave 5 (test-side consolidation) = 4 substantive. Plus 3 handover updates = **27 commits total**.
- Files touched: ~180 unique across all commits
- Build verifications: ≥18 independent `build-runner` / smoke runs, all green
- Tests passing at HEAD: 399 (aether/node) + 342 (aether/cli, +5 from cluster-tasks filter tests) + 280 (aether-config) + 22 (artifact-repo) + 20 (SelfDrainCoordinator, +3 EventEmission tests). Full reactor green.
- Tag movements: `v1.0.0-rc1-candidate` advanced 22+ times
- Notable architectural choices: (1) `http-routing` added as `aether-config` dep — alternative architectures rejected with rationale documented; (2) `NodesCommand`/`NodeCommand` picocli name-clash fixed by merge as part of T1.4; (3) `ClusterStatusNodeInfo.lifecycleState` was a stub hardcoded `"ON_DUTY"` — discovered + fixed during T1.3; (4) `aether blueprints contains` reframed as artifact-push idempotency once the survey revealed the real problem lived a layer down; (5) `events --follow` SSE deferred to RC2 after survey revealed no HTTP streaming primitive exists; (6) `SelfDrainCoordinator` publisher injection via narrow `SelfDrainEventPublisher` interface to avoid `aether-deployment` → `aether-node` cycle, with forward-declared `AtomicReference` for construction ordering; (7) `SELF_DRAIN_INITIATED` event explicitly NOT leader-gated (originating node is the only source); (8) test-readiness-contract spec is the first written contract for integration-test setup helpers — establishes the pattern of "spec doc → fold helpers to match" rather than letting helpers drift independently
- $2500 post-audit challenge from Wave 1 generated the follow-up backlog that Waves 2+3 cleared; user-directed test-side consolidation generated the Wave 5 backlog
