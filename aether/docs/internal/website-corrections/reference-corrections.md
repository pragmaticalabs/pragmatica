# Reference Page Corrections

Comparison of `https://pragmaticalabs.io/docs/reference.html` against actual codebase (release-1.0.0-rc1).

Generated: 2026-04-05

---

## 1. Annotation Table — Incorrect Entries

**Website shows:**

| Annotation | Target | Purpose |
|-----------|--------|---------|
| `@Subscription` | Method | Subscribes to a pub-sub topic |
| `@Scheduled` | Method | Periodic or cron-based execution |
| `@Notify` | Parameter | PostgreSQL LISTEN/NOTIFY |

**Corrections:**

| Issue | Website | Actual |
|-------|---------|--------|
| `@Subscription` | Listed as an annotation | **Does not exist.** Pub-sub uses `@ResourceQualifier(type = Subscriber.class, config = "...")` on custom annotations targeting METHOD |
| `@Scheduled` | Listed as a method annotation with attributes | `Scheduled` is a **marker interface**, not an annotation. Scheduled tasks use `@ResourceQualifier(type = Scheduled.class, config = "...")` on custom annotations targeting METHOD. Schedule params (cron, interval) live in TOML config, not annotation attributes |
| `@Notify` | "PostgreSQL LISTEN/NOTIFY" | **Wrong purpose.** `@Notify` is for NotificationSender (email/SMS), NOT PG LISTEN/NOTIFY. PG LISTEN/NOTIFY uses `@ResourceQualifier(type = PgNotificationSubscriber.class, config = "...")` on custom annotations targeting METHOD |
| `@Codec` | Listed as user-facing | **Internal annotation.** The slice processor generates codecs automatically for all types referenced in `@Slice` method signatures (request, response, event types). Developers never need `@Codec`. The examples in `notification-hub/` incorrectly use `@Codec` on records — these annotations are redundant. Remove from the public annotation table. |

**Correct annotation table:**

| Annotation | Target | Purpose |
|-----------|--------|---------|
| `@Slice` | Interface (TYPE) | Marks a service interface for annotation processing |
| `@ResourceQualifier` | Annotation (ANNOTATION_TYPE) | Meta-annotation for defining custom resource qualifiers |
| `@PgSql` | Parameter, Interface (PARAMETER, TYPE) | PostgreSQL type-safe persistence (AEP) |
| `@Sql` | Parameter (PARAMETER) | Generic SQL connector (default config: "database") |
| `@Http` | Parameter (PARAMETER) | HTTP client (default config: "http") |
| `@Notify` | Parameter (PARAMETER) | Notification sender — email/SMS (default config: "notification") |
| `@ContentStoreQualifier` | Parameter (PARAMETER) | Content store access |
| `@PartitionKey` | Record component | Marks partition key field for stream routing |

**Custom qualifiers (defined per-project, not built-in):**

| Pattern | Resource Type | Target | Purpose |
|---------|-------------|--------|---------|
| `@ResourceQualifier(type = Subscriber.class)` | Custom annotation | METHOD | Pub-sub topic subscription |
| `@ResourceQualifier(type = Scheduled.class)` | Custom annotation | METHOD | Scheduled task execution |
| `@ResourceQualifier(type = StreamPublisher.class)` | Custom annotation | PARAMETER | Stream publishing |
| `@ResourceQualifier(type = StreamSubscriber.class)` | Custom annotation | METHOD | Stream subscription |
| `@ResourceQualifier(type = StreamAccess.class)` | Custom annotation | PARAMETER | Stream pull access |
| `@ResourceQualifier(type = PgNotificationSubscriber.class)` | Custom annotation | METHOD | PG LISTEN/NOTIFY |
| `@ResourceQualifier(type = ConfigurationSection.class)` | Custom annotation | PARAMETER, METHOD | Typed config injection + update notification |
| `@ResourceQualifier(type = MethodInterceptor.class)` | Custom annotation | METHOD | Method-level interceptors |

---

## 2. CLI Command Groups — Incomplete

**Website lists 15+ groups.** Actual CLI (from `AetherCli.java`) has **33 subcommands**:

| Actual Command | In Website? | Notes |
|---------------|-------------|-------|
| `status` | Yes | |
| `nodes` | Yes | |
| `slices` | Yes | |
| `node-slices` | **No** | Per-node slice listing |
| `routes` | Yes | |
| `node-routes` | **No** | Per-node route listing |
| `metrics` | Yes | |
| `health` | Yes | |
| `scale` | Yes | |
| `deploy` | Yes | Subcommands: list, status, promote, rollback, complete |
| `artifact` | Yes | |
| `blueprint` | Yes | |
| `config` | Yes | |
| `logging` | Yes | |
| `events` | Yes | |
| `node` | Yes | drain, activate, shutdown |
| `cluster` | Yes | Subcommands: bootstrap, list, use, remove, status, export, apply, drain, destroy, scale, upgrade, migrate, tasks |
| `observability` | Yes | |
| `alerts` | Yes | |
| `thresholds` | Yes | |
| `backup` | Yes | trigger, list, restore |
| `schema` | Yes | |
| `invocation-metrics` | **No** | Per-method P50/P95/P99 |
| `controller` | **No** | Scaling controller config |
| `scheduled-tasks` | **No** | Scheduled task management |
| `topology` | **No** | Topology graph |
| `workers` | **No** | Worker pool management |
| `ab-test` | **No** | A/B test management |
| `stream` | **No** | Stream management |
| `cert` | **No** | Certificate management |
| `storage` | **No** | AHSE storage management |

**Missing from website:** 11 command groups (node-slices, node-routes, invocation-metrics, controller, scheduled-tasks, topology, workers, ab-test, stream, cert, storage)

---

## 3. Management API Endpoints — Incomplete

**Website shows ~13 endpoints.** The actual API has 60+ endpoints across 13 route classes.

**Missing major endpoint groups:**

| Endpoint Group | Endpoints | Missing? |
|---------------|-----------|----------|
| `/api/node/drain/{id}` | POST | **Yes** |
| `/api/node/shutdown/{id}` | POST | **Yes** |
| `/api/node/activate/{id}` | POST | **Yes** |
| `/api/nodes/lifecycle` | GET | **Yes** |
| `/api/invocation-metrics` | GET | **Yes** |
| `/api/controller` | GET/PUT | **Yes** |
| `/api/logging/levels` | GET/PUT | **Yes** |
| `/api/thresholds` | GET/PUT | **Yes** |
| `/api/alerts` | GET | **Yes** |
| `/api/traces` | GET | **Yes** |
| `/api/observability/depth` | GET/PUT | **Yes** |
| `/api/scheduled-tasks` | GET | **Yes** |
| `/api/schema/status` | GET | **Yes** |
| `/api/schema/retry` | POST | **Yes** |
| `/api/backup` | POST | **Yes** |
| `/api/backups` | GET | **Yes** |
| `/api/backup/restore` | POST | **Yes** |
| `/api/workers` | GET | **Yes** |
| `/api/workers/health` | GET | **Yes** |
| `/api/streams` | GET | **Yes** |
| `/api/cluster/scale` | POST | **Yes** |
| `/api/cluster/upgrade` | POST | **Yes** |
| `/api/cluster/topology` | GET | **Yes** |
| `/api/cluster/tasks` | GET | **Yes** |
| `/api/storage` | GET | **Yes** |

The website should either list all endpoints or clearly state it's showing a subset.

---

## 4. Configuration Sections — Partially Correct

**Website shows:**

| Section | Purpose |
|---------|---------|
| `[app-http]` | Application HTTP server, API keys, RBAC |

**Actual:** The section name may be `[app_http]` or `[app-http]` — verify against `ConfigLoader.java`. Also verify if `[server]` is a separate section or part of `[cluster]`.

**Missing configuration sections:**

| Section | Purpose | In Website? |
|---------|---------|-------------|
| `[backup]` | Backup configuration (enabled, interval, path, remote) | **No** |
| `[tls]` | TLS configuration (auto_generate, cluster_secret) | **No** |
| `[dht.replication]` | DHT replication config (cooldown, target_rf) | **No** |
| `[worker]` | Worker node config (group_name, zone, max_group_size) | **No** |
| `[scheduling.*]` | Scheduled task parameters (cron, interval, mode) | **No** |
| `[messaging.*]` | Pub-sub topic configuration | **No** |
| `[streams.*]` | Stream configuration (partitions, retention) | **No** |
| `[pg-notifications.*]` | PG LISTEN/NOTIFY channels | **No** |
| `[notification]` | Notification sender (SMTP, HTTP vendors) | **No** |
| `[interceptors.*]` | Method interceptor config (retry, circuit-breaker) | **No** |
| `[load_balancer]` | Passive LB configuration | **No** |
| `[observability]` | Observability depth threshold | **No** |

---

## 5. Environment Variables — Incomplete

**Website shows 3 environment variables.** The actual system supports many more:

| Variable | Purpose | In Website? |
|----------|---------|-------------|
| `AETHER_CLUSTER_SECRET` | Cluster TLS secret | Yes |
| `AETHER_SECRET_*` | Hetzner-style secrets resolution | **No** |
| `AETHER_API_KEY` | CLI API key | Shown in auth section |
| `AETHER_ENVIRONMENT` | Environment detection (LOCAL/DOCKER/KUBERNETES) | **No** |
| `AETHER_NODE_ID` | Override node ID | **No** |
| Cloud-specific vars (AWS_*, GOOGLE_*, AZURE_*) | Cloud provider auth | **No** |

---

## 6. Core Types — Missing Types

**Website lists:** Promise, Result, Option, Cause, Unit

**Missing important types:**

| Type | Purpose |
|------|---------|
| `Fn1<R, T>` | Single-arg function (used everywhere in JBCT) |
| `Tuple` / `Tuple2..Tuple15` | Type-safe tuples for `Promise.all()` / `Result.all()` |
| `TimeSpan` | Duration value object |
| `Verify` / `Verify.Is` | Validation predicates |
| `RowMapper<T>` | SQL result row mapping |
| `HttpResult<T>` | HTTP response wrapper |
| `PgNotification` | PG LISTEN/NOTIFY payload |
| `MethodName` | Validated slice method name |
| `SliceMethod<I, O>` | Method metadata record |

---

## 7. Port Numbers — Not Documented

The reference page mentions no port numbers. Critical defaults:

| Port | Purpose | Default |
|------|---------|---------|
| 8070+ | App HTTP (per node: 8070, 8071, ...) | Configurable |
| 5150 | Management API | Configurable |
| 8888 | Dashboard (Forge) | Configurable |
| 8080 | Passive Load Balancer | Configurable |
| 4000+ | Cluster (consensus, QUIC) | Configurable |
| 4001+ | SWIM (cluster port + 1) | Auto-derived |

---

## 8. Version Claim

**Website says:** "Release 1.0.0-rc1"

Correct for current state.

---

## Summary of Actions

| Priority | Issue | Action |
|----------|-------|--------|
| **Critical** | `@Subscription` doesn't exist | Remove or replace with `@ResourceQualifier(type = Subscriber.class)` pattern |
| **Critical** | `@Scheduled` is not an annotation with attributes | Replace with `@ResourceQualifier(type = Scheduled.class)` pattern |
| **Critical** | `@Notify` purpose wrong (email, not PG) | Fix description; add PgNotificationSubscriber for PG |
| **High** | 11 CLI groups missing | Add: node-slices, node-routes, invocation-metrics, controller, scheduled-tasks, topology, workers, ab-test, stream, cert, storage |
| **High** | 25+ API endpoints missing | Add or link to full API reference |
| **High** | 12 config sections missing | Add backup, tls, dht, worker, scheduling, messaging, streams, pg-notifications, notification, interceptors, load_balancer, observability |
| **Medium** | Missing core types | Add Fn1, Tuple, TimeSpan, Verify, RowMapper, HttpResult, PgNotification |
| **Medium** | No port numbers | Add default port table |
| **Low** | Environment variables incomplete | Add AETHER_SECRET_*, AETHER_ENVIRONMENT, etc. |
| **Low** | `@Codec` on example types | Remove redundant `@Codec` from notification-hub and step-composition examples — slice processor generates codecs automatically |
