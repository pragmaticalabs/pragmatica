# Session handover — 2026-05-20

Branch: `release-1.0.0-rc1`
HEAD: `56d0f4221`
Candidate tag: `v1.0.0-rc1-candidate` → `56d0f4221`

## Topline

Five commits landed today closing out the **CLI / REST surface consistency audit** — the work that started with "are tests detecting things consistently?" and pivoted to "is the CLI itself coherent enough that consistent detection becomes possible?" The audit produced four foundational decisions (`#22`, `#23`, `#24`, Phase B), each of which landed as a focused commit with full test verification.

The headline architectural deliverables:

1. **Canonical REST surface** — every collection plural; every ID-like param named `id`; every misleading enum/path repositioned to match its actual content.
2. **RFC 9457 ProblemDetail envelope** — all management-plane errors share one wire shape.
3. **Two-endpoint state authority contract** — `/api/nodes/lifecycle/{id}` is KV-direct FSM authority; `/api/nodes/status cluster.nodes[]` carries both `kvState` (authoritative) and `derivedStatus` (operator view with reachability overlay) side-by-side.
4. **Per-node variant endpoints** — `NodeIdParam` routing primitive lets `aether status <id>` work from any node via the cluster forwarder.

## Commits (in order)

| Commit | Subject | Scope |
|---|---|---|
| `6dd22fdc7` | `refactor(aether): canonicalize REST paths (plural collections) and rename CLI subcommands (Phase A)` | 66 files, 438/438. Path renames, picocli renames, RBAC registry update, dashboard JS, Docker healthchecks, rolling-upgrade script. |
| `6459cb9a7` | `docs: cli-gap-audit log capturing Phase A canonicalization and remaining gaps` | New `aether/docs/internal/cli-gap-audit.md`. |
| `55e0909bb` | `refactor(aether): RFC 9457 ProblemDetail envelope for management-plane errors` | 6 files. `ProblemResponses.java` utility, `ManagementRouter.writeError`, `ManagementServer` 4 sites, CLI `OutputFormatter` parses ProblemDetail, `NodeLifecycleRoutes` drain/activate leak fix (200→409). |
| `0fc1c8a9b` | `refactor(aether): state authority cleanup (kvState/derivedStatus split, KV-direct lifecycle list, SHUTTING_DOWN collapse)` | 6 files. New `state-authority.md` spec; `NodeInfo` shape change; `/api/nodes/lifecycle` mass form switched to KV-direct; `SHUTTING_DOWN`→`DRAINING` normalization at API boundary; `lib/cluster.sh` field renames. |
| `56d0f4221` | `feat(aether): per-node variant endpoints (NodeIdParam routing + forwarder + 5 CLI commands)` | 11 files. New `RouteTarget.NodeIdParam(int paramIndex)`. `HttpForwarder.forwardToTargetNode`. `ManagementServer.tryForwardIfNotTargetNode`. 5 new enum entries (`NODE_STATUS_GET`, `NODE_INFLIGHT_GET`, `NODE_SLICES_GET`, `NODE_ROUTES_GET`, `NODE_METRICS_GET`). 5 route handlers. 4 CLI commands gaining optional `[id]` (`status`, `node-slices`, `node-routes`) + 2 new top-levels (`node-inflight`, `node-metrics`). New error variants `NotLocalTarget` / `TargetDisconnected`. |

Test counts at each step: **386 → 736 → 736 → 785** (each commit verified independently via `build-runner` agent).

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

### Tier 1 — RC1-ish foundational cleanup (per "anything affecting foundation → RC1" rule)

| Item | Where | Effort |
|---|---|---|
| **Sealed `*Error` httpStatus() mapping** — most causes still default to 500 in the new envelope. Per-domain incremental commits (start with `ClusterConfigError` — 24 variants, all RC1 surface). | `aether/aether-config/src/main/java/.../cluster/ClusterConfigError.java`, plus `KeyNotFoundError`, `UpgradeError`, `WorkerError`, `ConfigNotFoundError`, `SecurityError` | ~2 hours per domain; mechanical once pattern established. Consider an `HttpStatusAware` mixin interface in `http-routing` so `ManagementRouter.writeError` can dispatch via interface check. |
| **`ClusterStatusNodeInfo.lifecycleState`** in `/api/cluster/status` cluster.nodes[]. Same question as `/api/nodes/status` was — single field that conflates KV intent vs derived view. Should likely get the same `kvState`/`derivedStatus` split. | `aether/node/src/main/java/.../ManagementApiResponses.java:416`, `ClusterConfigRoutes.java` | ~half day; mirrors the `#24` work. |
| **Folding `node-slices`/`node-routes`/`node-inflight`/`node-metrics` kebab top-levels** under `aether nodes <verb> [id]` namespace. CLI UX cleanup — current kebab forms work but inconsistent with `aether nodes drain/activate/lifecycle`. | `aether/cli/src/main/java/.../AetherCli.java` — NodeCommand subcommands | ~half day; mechanical picocli refactor. Aliases possible if breaking is too aggressive. |

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

## Suggested next-session opener

Highest-leverage continuation: **Tier 1 — start with `ClusterConfigError` httpStatus() mapping.** This is the biggest sealed Cause hierarchy on the management surface (24 variants), all currently land on HTTP 500 with the new envelope. Adding per-variant status codes immediately improves operator API quality across `/api/cluster/config`, `/api/cluster/upgrade`, `/api/cluster/scale`, and the related cluster lifecycle routes.

Suggested approach (proven to work today):
1. Define `HttpStatusAware` interface in `integrations/http-routing/`
2. Have `ClusterConfigError` interface extend it with `default HttpStatus httpStatus() { return BAD_REQUEST; }`
3. Per-variant overrides where semantics differ (`VersionConflict` → 409, `QuorumSafetyViolation` → 409, `InvalidCoreCount` → 400, etc.)
4. Update `ProblemResponses.writeProblem(Cause)` / `ManagementRouter.writeError` to consult `HttpStatusAware` before defaulting to 500
5. Build verify, commit, advance tag

Then repeat per sealed type: `KeyNotFoundError` (404), `ConfigNotFoundError` (404), `UpgradeError.AlreadyAtVersion` (409), `MetricsError.StrategyChangeNotSupported` (501 — already wrapped in `HttpError`), `RateGuardError.LimitExceeded` (429), `CoordinatorError.NOT_LEADER` (409 — already wrapped at one site, others fall through).

Each follow-up commit ~half a day. Audit doc captures decisions; spec docs don't need additional pages (just per-domain change-list).

## Session metadata

- Date: 2026-05-20 (single working day)
- Commits: 5 substantive + handover
- Files touched: ~80 unique across all commits
- Build verifications: 4 independent `build-runner` runs, all green
- Tests passing at HEAD: 785
- Tag movements: `v1.0.0-rc1-candidate` advanced 5 times
