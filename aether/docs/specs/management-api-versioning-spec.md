# Management API Versioning & Surface Consolidation

**Status:** Draft v0.1 · **Issue:** [#300](https://github.com/pragmaticalabs/pragmatica/issues/300) · **Date:** 2026-07-04 · **Author:** design-stream

Cross-refs: #226 (blueprint endpoint consolidation), #198 (slice HTTP API versioning — *separate surface*), #310 (`architecture/12-management.md` `/api/v1` doc drift).

---

## 1. Problem

1. **No version prefix.** All 181 `ManagementRoute` entries (`aether/aether-management-api/.../route/ManagementRoute.java:30-249`) mount at bare `/api/...`. Once RC freezes the wire, every rename post-GA is a breaking change for CLI, dashboards, harnesses, and third-party tooling. Pre-GA, a rename is free (no-backward-compat policy). This is the last window.
2. **Dual overlapping surfaces**, resolved only by a *double route-resolution path* in `ManagementRouter.dispatch` (`aether/node/.../routes/ManagementRouter.java:66-92`): first the `ManagementRoute` enum table, then a fallback to the `RequestRouter` trie. Concrete duplicates:
   - **Streams:** `StreamRoutes.java` (flat engine surface: `STREAM_*`, `CONSUMER_GROUP_*`, name/partition-addressed) and `StreamApiRoutes.java` (namespaced catalog surface: `STREAMS_*`, `(namespace, stream, version)`-addressed) both mount under `/api/streams`. Collisions are disambiguated only by param *count*: `POST /api/streams/publish/{name}` (`STREAM_PUBLISH`, ManagementRoute.java:144) vs `POST /api/streams/publish/{ns}/{stream}/{ver}` (`STREAMS_PUBLISH`, :165); `GET /api/streams/groups/{id}` (:157) vs `GET /api/streams/groups/{ns}/{stream}/{ver}` (:164).
   - **Backups CLI:** `aether backups` (`AetherCli.java:3429`, subcommands trigger/list/restore) and `aether backup` (`AetherCli.java:3503`, create/restore/list) — a documented singular alias over the same three REST routes.
3. **Doc drift:** `management-api.md` documents bare `/api` (matches code); `architecture/12-management.md` claims `/api/v1` routes that were never implemented (#310). Two documents, two answers.

## 2. Design

### 2.1 Canonical scheme: path prefix `/api/v1`, composed at one site

URI path prefix, not header/media-type versioning: curl-able, harness-greppable, cacheable, and consistent with the slice-facing #198 decision (path mode is the default there too).

**Single composition site.** Every consumer already derives paths from `ManagementRoute.prefix()`:

- inbound match: `RouteMatcher.build` indexes `r.prefix()` (RouteMatcher.java:26-30);
- outbound assembly (CLI, node-to-node forwarding): `RouteAssembler.assemble` starts from `route.prefix()` (RouteAssembler.java:25);
- handler registration: `ManagementRoutes.route(mr)` uses `mr.prefix()` (ManagementRoutes.java:16).

Therefore: enum literals become **version-relative suffixes** (`"/nodes/status"`), and the constructor composes `API_BASE + suffix` with `static final String API_BASE = "/api/v1"`. Unversioned routes (§2.2) use an explicit `raw("/health/live")` constructor variant. This makes an unversioned `/api` route *unrepresentable* rather than merely discouraged, and the version lives in exactly one constant. (Alternative considered: keep literals and string-rewrite `/api/` → `/api/v1/` in the accessor — zero literal churn but keeps per-route strings load-bearing; rejected.)

CLI (`AetherCli.fetch/post` → `route.assemble`), RBAC (`ManagementRoutePermissions` is `EnumMap<ManagementRoute, ...>`-keyed), and route forwarding inherit the prefix with no per-call-site changes.

### 2.2 What stays unversioned

| Path | Why |
|---|---|
| `/health/live`, `/health/ready` | Probe convention; wired into k8s/compose infra; must never move. |
| `/repository/**` (5 routes) | Maven wire protocol — layout dictated by Maven clients, not ours to version. |
| `/`, `/index.html`, `/css/**`, `/js/**`, `/vendor/**` | Dashboard static assets (ManagementServer.java:1144). |
| `/ws` (WebSocket endpoints) | Session-oriented; versioned via subprotocol later if ever needed. |

### 2.3 Deprecation stance pre-GA: hard cutover

No aliases, no dual-serving, no `Sunset` headers. Old `/api/...` paths return 404 with the standard `ProblemDetail`. Rationale: pre-GA no-compat policy; an alias layer is exactly the double-resolution debt this issue removes. This also **obsoletes the alias/Sunset migration steps in #226** — when #226 lands, it lands as a hard rename too.

### 2.4 Single route resolution

The `RequestRouter` fallback in `ManagementRouter.dispatch` (ManagementRouter.java:86-92) is removed. The `ManagementRoute` enum becomes the *only* routing authority:

1. `RouteMatcher.match` resolves the enum entry; miss → 404.
2. Enum entry with no registered handler → 501 `ProblemDetail` (today: silent fall-through to the trie, ManagementRouter.java:82-84).
3. **Implementation gate:** before deletion, audit that no `RouteSource` registers a route reachable *only* via the fallback (all routes built through `ManagementRoutes.route(mr)` are enum-named by construction; hand-built `Route.method(...)` registrations are the risk). Startup assertion: every registered route name must be an enum constant.

### 2.5 Dual-surface consolidation rules

| Dual surface | Rule (winner) |
|---|---|
| `StreamApiRoutes` vs `StreamRoutes` under `/api/streams` | **Full merge into the namespaced catalog (owner decision, 2026-07-04 — pre-GA, no compat constraints: "build what we planned in one shot").** One surface: `/api/v1/streams/**`, catalog-addressed `(namespace, stream, version)`. The flat engine surface is DELETED; raw/system streams surface under the reserved **`system` namespace**; engine diagnostics (partitions, replicas, raw read, consumers) become **sub-resources of the catalog identity** (§3.2). Param-count disambiguation disappears; `StreamRoutes.java` is retired. |
| `aether backups` vs `aether backup` CLI | Plural `aether backups` wins (matches `scheduled-tasks` etc.); absorbs the singular's verb set as `create` / `list` / `restore` (`trigger` renamed `create`); singular command deleted. REST surface unchanged (POST/GET on one collection is not a duplicate). |
| `POST /api/blueprints` vs `/api/blueprints/publish` vs `/api/blueprints/deploy` | Owned by #226; not respecified here. This spec only (a) reserves `/api/v1/blueprints/**`, (b) converts #226's migration to hard-cutover (§2.3). |
| Enum table vs `RequestRouter` trie | Enum wins; trie fallback deleted (§2.4). |

### 2.6 Version-bump policy going forward

- **v1 freezes at GA.** Until GA, v1 itself may still change (it is the pre-freeze workbench).
- **Additive changes** (new routes, new optional request fields, new response fields) do **not** mint v2. Clients must ignore unknown fields.
- **Breaking changes** (remove/rename a route, change semantics, remove/retype a response field) mint `/api/v2` for the whole surface — no per-route versioning.
- **Post-GA dual-serve:** v_{n-1} is served in parallel for ≥1 minor release, with `Deprecation`/`Sunset` response headers (same mechanism #198 §8 builds for slices). Hard cutover is a pre-GA privilege only.
- **SemVer linkage:** the management API version is *independent* of the product version (v1 is expected to span product 1.x; v2 would normally coincide with a product major, but is not required to). Discoverability: `GET /api/v1/whoami` response gains an `apiVersion` field (additive).

## 3. Migration table (wire-freeze artifact)

Covers all 181 enum entries. HTTP methods and path parameters are unchanged unless a row says otherwise. Authoritative source remains the enum; this table is the review artifact.

### 3.1 Prefix-only changes (116 distinct paths, all methods/param-arities on each)

| Old | New |
|---|---|
| `/api/ab-tests` | `/api/v1/ab-tests` |
| `/api/ab-tests/conclude` | `/api/v1/ab-tests/conclude` |
| `/api/ab-tests/create` | `/api/v1/ab-tests/create` |
| `/api/ab-tests/metrics` | `/api/v1/ab-tests/metrics` |
| `/api/alerts` | `/api/v1/alerts` |
| `/api/alerts/active` | `/api/v1/alerts/active` |
| `/api/alerts/clear` | `/api/v1/alerts/clear` |
| `/api/alerts/history` | `/api/v1/alerts/history` |
| `/api/alerts/inject` | `/api/v1/alerts/inject` |
| `/api/artifacts/metrics` | `/api/v1/artifacts/metrics` |
| `/api/audit/commands` | `/api/v1/audit/commands` |
| `/api/backups` | `/api/v1/backups` |
| `/api/backups/restore` | `/api/v1/backups/restore` |
| `/api/blueprints` | `/api/v1/blueprints` |
| `/api/blueprints/deploy` | `/api/v1/blueprints/deploy` (shape owned by #226) |
| `/api/blueprints/publish` | `/api/v1/blueprints/publish` |
| `/api/blueprints/status` | `/api/v1/blueprints/status` |
| `/api/blueprints/validate` | `/api/v1/blueprints/validate` |
| `/api/certificates` | `/api/v1/certificates` |
| `/api/certificates/configure-short-validity` | `/api/v1/certificates/configure-short-validity` |
| `/api/cluster/await-quiesced` | `/api/v1/cluster/await-quiesced` |
| `/api/cluster/config` | `/api/v1/cluster/config` |
| `/api/cluster/generation` | `/api/v1/cluster/generation` |
| `/api/cluster/governors` | `/api/v1/cluster/governors` |
| `/api/cluster/journal` | `/api/v1/cluster/journal` |
| `/api/cluster/keys` | `/api/v1/cluster/keys` |
| `/api/cluster/keys/audit` | `/api/v1/cluster/keys/audit` |
| `/api/cluster/keys/revoke` | `/api/v1/cluster/keys/revoke` |
| `/api/cluster/membership` | `/api/v1/cluster/membership` |
| `/api/cluster/migrate` | `/api/v1/cluster/migrate` |
| `/api/cluster/migrate/plan` | `/api/v1/cluster/migrate/plan` |
| `/api/cluster/provisioning` | `/api/v1/cluster/provisioning` |
| `/api/cluster/scale` | `/api/v1/cluster/scale` |
| `/api/cluster/status` | `/api/v1/cluster/status` |
| `/api/cluster/storage` | `/api/v1/cluster/storage` |
| `/api/cluster/topology` | `/api/v1/cluster/topology` |
| `/api/cluster/topology/auto-heal` | `/api/v1/cluster/topology/auto-heal` |
| `/api/cluster/topology/auto-heal/disable` | `/api/v1/cluster/topology/auto-heal/disable` |
| `/api/cluster/topology/auto-heal/enable` | `/api/v1/cluster/topology/auto-heal/enable` |
| `/api/cluster/topology/circuit-breaker` | `/api/v1/cluster/topology/circuit-breaker` |
| `/api/cluster/topology/circuit-breaker/reset` | `/api/v1/cluster/topology/circuit-breaker/reset` |
| `/api/cluster/upgrade` | `/api/v1/cluster/upgrade` |
| `/api/config` | `/api/v1/config` |
| `/api/config/nodes` | `/api/v1/config/nodes` |
| `/api/config/overrides` | `/api/v1/config/overrides` |
| `/api/controller/config` | `/api/v1/controller/config` |
| `/api/controller/evaluate` | `/api/v1/controller/evaluate` |
| `/api/controller/status` | `/api/v1/controller/status` |
| `/api/deploy` | `/api/v1/deploy` |
| `/api/deploy/complete` | `/api/v1/deploy/complete` |
| `/api/deploy/promote` | `/api/v1/deploy/promote` |
| `/api/deploy/rollback` | `/api/v1/deploy/rollback` |
| `/api/dht/inject` | `/api/v1/dht/inject` |
| `/api/dht/replication-map` | `/api/v1/dht/replication-map` |
| `/api/events` | `/api/v1/events` |
| `/api/health` | `/api/v1/health` |
| `/api/invocations/metrics` | `/api/v1/invocations/metrics` |
| `/api/invocations/metrics/slow` | `/api/v1/invocations/metrics/slow` |
| `/api/invocations/metrics/strategy` | `/api/v1/invocations/metrics/strategy` |
| `/api/logging/levels` | `/api/v1/logging/levels` |
| `/api/metrics` | `/api/v1/metrics` |
| `/api/metrics/backfill` | `/api/v1/metrics/backfill` |
| `/api/metrics/comprehensive` | `/api/v1/metrics/comprehensive` |
| `/api/metrics/derived` | `/api/v1/metrics/derived` |
| `/api/metrics/history` | `/api/v1/metrics/history` |
| `/api/metrics/prometheus` | `/api/v1/metrics/prometheus` |
| `/api/metrics/timeouts` | `/api/v1/metrics/timeouts` |
| `/api/metrics/transport` | `/api/v1/metrics/transport` |
| `/api/nodes` | `/api/v1/nodes` |
| `/api/nodes/drain` | `/api/v1/nodes/drain` |
| `/api/nodes/endpoint` | `/api/v1/nodes/endpoint` |
| `/api/nodes/inflight` | `/api/v1/nodes/inflight` |
| `/api/nodes/lifecycle` | `/api/v1/nodes/lifecycle` |
| `/api/nodes/live` | `/api/v1/nodes/live` |
| `/api/nodes/metrics` | `/api/v1/nodes/metrics` |
| `/api/nodes/promote` | `/api/v1/nodes/promote` |
| `/api/nodes/routes` | `/api/v1/nodes/routes` |
| `/api/nodes/shutdown` | `/api/v1/nodes/shutdown` |
| `/api/nodes/slices` | `/api/v1/nodes/slices` |
| `/api/nodes/status` | `/api/v1/nodes/status` |
| `/api/observability/depth` | `/api/v1/observability/depth` |
| `/api/ownership` | `/api/v1/ownership` |
| `/api/routes` | `/api/v1/routes` |
| `/api/scale` | `/api/v1/scale` |
| `/api/scheduled-tasks` | `/api/v1/scheduled-tasks` |
| `/api/scheduled-tasks/executions-by-node` | `/api/v1/scheduled-tasks/executions-by-node` |
| `/api/scheduled-tasks/inject` | `/api/v1/scheduled-tasks/inject` |
| `/api/scheduled-tasks/pause` | `/api/v1/scheduled-tasks/pause` |
| `/api/scheduled-tasks/resume` | `/api/v1/scheduled-tasks/resume` |
| `/api/scheduled-tasks/state` | `/api/v1/scheduled-tasks/state` |
| `/api/scheduled-tasks/trigger` | `/api/v1/scheduled-tasks/trigger` |
| `/api/schema/baseline` | `/api/v1/schema/baseline` |
| `/api/schema/history` | `/api/v1/schema/history` |
| `/api/schema/migrate` | `/api/v1/schema/migrate` |
| `/api/schema/retry` | `/api/v1/schema/retry` |
| `/api/schema/status` | `/api/v1/schema/status` |
| `/api/schema/undo` | `/api/v1/schema/undo` |
| `/api/slices` | `/api/v1/slices` |
| `/api/slices/config` | `/api/v1/slices/config` |
| `/api/slices/status` | `/api/v1/slices/status` |
| `/api/slices/topology` | `/api/v1/slices/topology` |
| `/api/storage` | `/api/v1/storage` |
| `/api/storage/snapshot` | `/api/v1/storage/snapshot` |
| `/api/stream-namespaces/get` | `/api/v1/stream-namespaces/get` (fold-in candidate, §5 Q2) |
| `/api/stream-namespaces/list` | `/api/v1/stream-namespaces/list` (fold-in candidate, §5 Q2) |
| `/api/thresholds` | `/api/v1/thresholds` |
| `/api/traces` | `/api/v1/traces` |
| `/api/traces/inject` | `/api/v1/traces/inject` |
| `/api/traces/stats` | `/api/v1/traces/stats` |
| `/api/ttm/status` | `/api/v1/ttm/status` |
| `/api/ttm/training-data` | `/api/v1/ttm/training-data` |
| `/api/versions` | `/api/v1/versions` |
| `/api/whoami` | `/api/v1/whoami` (response gains `apiVersion`) |
| `/api/workers` | `/api/v1/workers` |
| `/api/workers/endpoints` | `/api/v1/workers/endpoints` |
| `/api/workers/health` | `/api/v1/workers/health` |

### 3.2 Stream surface merge (12 engine routes fold into the catalog — owner decision 2026-07-04)

One addressing model: the catalog identity `(namespace, stream, version)` is THE public stream
identity. The reserved **`system` namespace** holds streams that exist outside app blueprints
(management-created, harness/diagnostic, audit/lifecycle): a raw engine stream `name` surfaces as
`system/{name}/1`. Internally the engine keeps its flat key; `StreamManager` gains the
catalog↔engine identity mapping as the single resolution point (the semantic change accepted with
this decision). Engine diagnostics become sub-resources of the catalog identity — they work for
EVERY stream, app or system, which the split design could not offer.

| Enum entry (old, flat engine) | Old | New (merged) |
|---|---|---|
| `STREAM_CREATE` | `POST /api/streams` | `POST /api/v1/streams/system` (mints `system/{name}/1`; body unchanged) |
| `STREAM_LIST` | `GET /api/streams` | **merged** into `STREAMS_LIST` `GET /api/v1/streams` (now lists all namespaces incl. `system`) |
| `STREAM_GET` | `GET /api/streams/{name}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/partitions` (detail = partitions sub-resource) |
| `STREAM_PARTITION` | `GET /api/streams/{name}/{partition}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/partitions/{p}` |
| `STREAM_DELETE` | `DELETE /api/streams/{name}` | **merged** into catalog `STREAMS_DELETE` (system ns) |
| `STREAM_PUBLISH` | `POST /api/streams/publish/{name}` | **merged** into `STREAMS_PUBLISH` `POST /api/v1/streams/{ns}/{stream}/{ver}/publish` (identity-first, §3.4) |
| `STREAM_READ` | `GET /api/streams/read/{name}/{partition}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/read/{p}` |
| `STREAM_REPLICAS` | `GET /api/streams/replicas/{name}/{partition}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/replicas/{p}` |
| `STREAM_CONSUMERS` | `GET /api/streams/consumers/{name}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/consumers` |
| `CONSUMER_GROUP_JOIN` | `POST /api/streams/groups/join` | **merged** into catalog groups: `POST /api/v1/streams/{ns}/{stream}/{ver}/groups/join` |
| `CONSUMER_GROUP_LEAVE` | `POST /api/streams/groups/leave` | `POST /api/v1/streams/{ns}/{stream}/{ver}/groups/leave` |
| `CONSUMER_GROUP_STATUS` | `GET /api/streams/groups/{id}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/groups/{id}` |

`StreamRoutes.java` is deleted; `StreamApiRoutes` absorbs the diagnostic sub-resources (its
`ensureStreamExists` — post-#410, committed-config-aware — is the survivor, resolving that
duplication too). Catalog (`STREAMS_*`) routes otherwise keep their suffixes: prefix-only
`/api/streams/...` → `/api/v1/streams/...`. Diagnostics on APP streams (e.g. the 02-chaos
replica-failover probes) address them by their blueprint identity — no `system` detour.

**Consumers to re-point (beyond the §4 sweep):** integration harness stream helpers
(`stream_publish`, replicas probes — path SHAPE changes, not just prefix), CLI `aether stream(s) *`
subcommands (unify on the catalog addressing), `ManagementServer` write-gate prefix (single base
now), durable-pubsub-spec §9 DLQ routes (drafted as `/api/topics/...` — mount under
`/api/v1/topics/**`, unaffected by this merge).

### 3.3 Namespace fold-in + catalog normalization (Q2/Q3 resolved, owner decision 2026-07-05)

Same free-window logic as the §3.2 merge: pre-GA renames are free; these two warts would otherwise
ship into v1. **Rule: identity-first resource shapes** — `/api/v1/streams/{ns}/{stream}/{ver}/{sub}`;
verb-prefix paths (`/streams/{verb}/{params...}`) are eliminated. §3.2's merged rows already read
in this shape.

| Enum entry | Old (verb-first) | New (identity-first) |
|---|---|---|
| `STREAMS_LIST` | `GET /api/streams/list` | `GET /api/v1/streams` |
| `STREAMS_VERSIONS_LIST` | `GET /api/streams/versions/{ns}/{stream}` | `GET /api/v1/streams/{ns}/{stream}` (the stream resource = its versions) |
| `STREAMS_LATEST` | `GET /api/streams/latest/{ns}/{stream}` | `GET /api/v1/streams/{ns}/{stream}/latest` |
| `STREAMS_METADATA` | `GET /api/streams/metadata/{ns}/{stream}/{ver}` | `GET /api/v1/streams/{ns}/{stream}/{ver}` (the version resource = its metadata) |
| `STREAMS_TAIL` | `GET /api/streams/tail/{ns}/{stream}/{ver}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/tail` |
| `STREAMS_EVENTS` | `GET /api/streams/events/{ns}/{stream}/{ver}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/events` |
| `STREAMS_GROUPS_LIST` | `GET /api/streams/groups/{ns}/{stream}/{ver}` | `GET /api/v1/streams/{ns}/{stream}/{ver}/groups` |
| `STREAMS_PUBLISH` | `POST /api/streams/publish/{ns}/{stream}/{ver}` | `POST /api/v1/streams/{ns}/{stream}/{ver}/publish` |
| `STREAMS_PUBLISH_BATCH` | `POST .../publish-batch/{ns}/{stream}/{ver}` | `POST /api/v1/streams/{ns}/{stream}/{ver}/publish-batch` |
| `STREAMS_GROUP_CREATE` | `POST .../groups/create/...` | `POST /api/v1/streams/{ns}/{stream}/{ver}/groups` |
| `STREAMS_GROUP_DELETE` | `DELETE .../groups/delete/...` | `DELETE /api/v1/streams/{ns}/{stream}/{ver}/groups/{group}` |
| `STREAMS_DELETE` | `DELETE /api/streams/delete/{ns}/{stream}/{ver}` | `DELETE /api/v1/streams/{ns}/{stream}/{ver}` |
| `STREAM_NAMESPACES_LIST` | `GET /api/stream-namespaces/list` | `GET /api/v1/streams/namespaces` |
| `STREAM_NAMESPACES_GET` | `GET /api/stream-namespaces/get/{...}` | `GET /api/v1/streams/namespaces/{ns}` (note: current entry declares a `(namespace, stream, version)` param triple — audit and reduce to `{ns}` during implementation) |

`StreamNamespacesRoutes` handlers change accordingly (path-param shift, not just prefix); the
`/api/stream-namespaces` root disappears. HTTP-method semantics carry the verb (create=POST on the
collection, delete=DELETE on the identity) — no verb suffixes remain anywhere in the stream tree.

### 3.4 Unchanged (unversioned by design)

`GET /health/live`, `GET /health/ready` (incl. `{id}` variants), `GET|PUT|POST|DELETE /repository/**` (`MAVEN_METADATA`, `ARTIFACT_*`, `REPOSITORY_ARTIFACTS_LIST`).

## 4. Blast radius

| Site | Change |
|---|---|
| `ManagementRoute.java` (aether-management-api) | Literals → suffixes + `API_BASE` composition + `raw(...)` variant. `RouteMatcher`/`RouteAssembler`/`ManagementRoutes.route` inherit — no changes. |
| `ManagementRouter.java:66-92` (aether/node) | Delete `RequestRouter` fallback; matched-but-unregistered → 501 (§2.4). |
| `ManagementServer.java:1250` | `STREAM_WRITE_PATH_PREFIX = "/api/streams"` write-gate → cover both new bases, or convert to enum-keyed check (preferred). |
| CLI | Inherits via `route.assemble()`. Exceptions to fix: hardcoded `endpoint + "/api/cluster/config"` and `"/api/cluster/keys"` in `BootstrapPhaseFormation.java:243,263`. Delete `aether backup` singular (`AetherCli.java:3496-3503`); rename `backups trigger` → `backups create`. |
| Dashboard | `aether/dashboard/src/main/resources/dashboard/index.html:62` `fetch('/api/nodes/status')` → new path; audit remaining fetches/WS paths in that module. |
| Integration harness | `api_get`/`api_post` (`tests/integration/lib/common.sh:340-357`) take full literal paths — mechanical sed sweep of `/api/` → `/api/v1/` across `tests/integration/**` (greppable, wire-honest) rather than helper-injected prefix (hides the real path). Stream-engine tests re-pathed per §3.2; `TC-NEW-G4-kv-store-backup` re-targeted to `aether backups`. |
| Java sweep | `grep -rn '"/api/' aether --include='*.java'` — fix remaining literals (tests, k6 configs on the *management* port only; slice-app-port `/api/v1/urls/` in k6 belongs to #198's surface, untouched). |
| Docs | `management-api.md` (base URL + every path), `cli.md` (backups), `architecture/12-management.md` (#310 fold-in), `feature-catalog.md`, `CHANGELOG.md`. |

Non-collision note: slice-served routes (e.g. `/api/v1/urls/` in k6) live on the slice HTTP port, not the management port — `/api/v1` on the management port is free. #198's auto-inserted version segment is `{prefix}/v{N}/...` (suffix position) and stays independent of this spec.

## 5. Open questions

1. **Fallback-only routes:** does any `RouteSource` register a route *not* named after an enum entry (reachable only via the trie fallback)? Must be audited to zero before §2.4 deletes the fallback — if nonzero, those routes first get enum entries.
2. ~~`/api/stream-namespaces/{list,get}`~~ — **RESOLVED (2026-07-05): folded into `/api/v1/streams/namespaces{,/{ns}}`** (§3.3); the separate root disappears.
3. ~~Catalog suffix normalization~~ — **RESOLVED (2026-07-05): identity-first shapes across the whole stream tree** (§3.3); no verb suffixes remain.
4. ~~`stream-engine` naming~~ — **moot (2026-07-04):** the full-merge decision (§3.2) eliminates the separate engine surface entirely.
