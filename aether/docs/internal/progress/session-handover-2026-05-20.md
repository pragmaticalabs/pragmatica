# Session handover — 2026-05-20

Branch: `release-1.0.0-rc1`
HEAD: `1bca1b4e1`
Candidate tag: `v1.0.0-rc1-candidate` → `1bca1b4e1`

## Topline

**Two work waves landed today on `release-1.0.0-rc1`:**

**Wave 1 — CLI / REST surface consistency audit closeout** (commits `6dd22fdc7` … `caedfa62a`, summarized in §"Wave 1" below). Six commits closing out the audit including a $2500 post-audit self-review.

**Wave 2 — Tier 1 RC1-foundational follow-ups** (commits `f563841b4` … `1bca1b4e1`). Five commits closing every Tier 1 item that was queued at the end of Wave 1: `HttpStatusAware` mixin + status-code mapping across 4 sealed `*Error` hierarchies; rename `StatusResponse.lifecycleState` → `runtimeState` with a separate KV-direct FSM `lifecycleState` field; same `kvState`/`derivedStatus` split for `ClusterStatusNodeInfo` in `/api/cluster/status` (plus discovery that the field was a `"ON_DUTY"` hardcoded stub — fixed); CLI namespace consolidation folding `node-{slices,routes,inflight,metrics}` under `aether nodes <verb> [id]` and resolving a latent `NodesCommand`/`NodeCommand` `@Command(name="nodes")` clash via merge.

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

With Tier 1 cleared, the natural next move is **Tier 2 operator parity gaps**. Highest-leverage starter: **`aether events --follow` (streaming/SSE)** — it directly unblocks ~10 test helpers and ~15 assertions currently doing per-node fan-out polling, and the underlying event-aggregator surface already exists. Implementation sketch:
1. Add `/api/events/stream` with `text/event-stream` content-type (SSE) — reuse the `eventAggregator()` Stream.
2. CLI `--follow` flag on `aether events` switches from one-shot fetch to long-poll SSE consumer.
3. Replace `topology_events_since` / `wait_for_node_departure` shell helpers with `aether events --follow --filter type=NODE_LEFT` patterns.

Alternative entry points if SSE feels too large:
- `aether blueprints contains <coords>` — tiny endpoint, eliminates `lib/cluster.sh:push_blueprint` stderr-grep
- `aether nodes health [id]` — surfaces per-node `/health/ready` data already produced by the server
- `aether slices --filter state=ACTIVE` — server-side filter for the existing `/api/slices` endpoint

For Tier 3 / RC2 readiness work the priority order is captured in `aether/docs/internal/cli-gap-audit.md`.

## Session metadata

- Date: 2026-05-20 (single working day, two waves)
- Commits: Wave 1 (audit closeout) = 6 substantive + 1 handover + 1 self-review fix = 8 commits. Wave 2 (Tier 1 closeout) = 5 substantive commits. Plus this handover update = **14 commits total**.
- Files touched: ~95 unique across all commits
- Build verifications: ≥10 independent `build-runner` runs, all green
- Tests passing at HEAD: aether/node 399, aether/cli 337, aether-config 280 (incremental; full reactor green)
- Tag movements: `v1.0.0-rc1-candidate` advanced 12+ times
- Notable architectural choices: (1) `http-routing` added as `aether-config` dep — alternative architectures rejected with rationale documented in commit; (2) `NodesCommand`/`NodeCommand` picocli name-clash fixed by merge as part of T1.4 (latent bug discovered mid-task)
- $2500 post-audit challenge from Wave 1 generated the follow-up backlog that Wave 2 cleared
