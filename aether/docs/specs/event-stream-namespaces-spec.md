# Event Stream Namespaces and Versioning — Specification

**Status:** RC1
**Depends on:** `streaming-spec.md`, `in-memory-streams-spec.md`, `rbac-spec.md`
**Superseded by:** _(none)_
**Companion to:** GitHub issue #165
**Forward references:** GitHub issue #205 (fine-grained stream RBAC, deferred to on-demand)

---

## 1. Purpose

Define how streams in Aether are addressed, versioned, access-controlled, and lifecycle-managed. Existing streaming specs assume flat string stream names. This document layers a three-component addressing scheme on top, covering:

- How streams are uniquely named across a cluster
- How schema evolution is supported without in-place migration
- How system (framework) streams are separated from application streams
- How access to streams is authorized
- When a stream is created and when it disappears

## 2. Goals / Non-goals

**Goals (RC1):**

- Three-component stream addresses with unambiguous parsing
- Namespace derivation that eliminates cross-application collisions by construction
- Multiple versions of the same stream coexisting under independent retention
- Reference-counted lifecycle: streams exist only while they have a reference
- Reserved system namespace for framework-internal streams (`system:*`), with admin-gated reads and no external write path
- Configuration surface (`resources.toml`) extended with minimal syntax
- `/api/streams/...` URL shape using path segments

**Non-goals (RC1):**

- Schema registry / schema bytes storage (deferred)
- Version ranges in addresses (e.g., `^1.0.0`) — RC1 supports only exact version or literal `latest`
- Cross-cluster / federated stream addresses (deferred; address is extensible to a 4-part form later)
- Fine-grained per-stream ACLs (RC1 uses coarse role-based gating for the system namespace only)
- Schema migration tooling for existing deployments (no existing deployments — streams are new)

## 3. Address shape

Every stream in Aether is uniquely identified by a three-component address:

```
<namespace>:<stream>:<version>
```

| Component | Role | Derivation |
|---|---|---|
| `namespace` | Isolation boundary | Either the reserved token `system`, or derived from the declaring blueprint's Maven coordinates |
| `stream` | Logical name chosen by the stream owner | Free choice within charset rules |
| `version` | Schema version pin | `MAJOR.MINOR.PATCH` triplet |

### 3.1. Textual form

- Canonical textual form uses `:` as the component separator.
- The address `com.example.myapp:orders:1.0.0` identifies one stream. No abbreviated form.
- When used in URLs, components are separated by `/` (see §12).

### 3.2. Grammar

```
address        := namespace ":" stream ":" version
namespace      := "system" | maven-derived-namespace
stream         := [a-z][a-z0-9-]{0,63}  ; no leading/trailing hyphen; no "--" ; excludes reserved names
version        := integer "." integer "." integer
maven-derived-namespace := [a-z0-9._-]+  ; Maven-legal characters; max 128 chars total (see §4)
```

Reserved stream names (RC1): `latest`. Reserved namespace tokens (RC1): `system` exact AND any namespace whose first dot-segment is `system` (i.e., the prefix `system.*`). Case-insensitive check at validation.

## 4. Namespace derivation

Application namespaces are derived from the declaring blueprint's Maven coordinates.

### 4.1. Rule

Given a blueprint Maven artifact with `groupId = G` and `artifactId = A`:

```
namespace = G + "." + A
```

Examples:

| groupId | artifactId | Derived namespace |
|---|---|---|
| `com.example` | `url-shortener` | `com.example.url-shortener` |
| `org.pragmatica.aether` | `forge` | `org.pragmatica.aether.forge` |
| `io.acme.billing` | `invoice-service` | `io.acme.billing.invoice-service` |

Blueprint identity is signalled by the Maven **classifier** `blueprint` on the produced JAR (see
`GenerateBlueprintMojo`, constant `CLASSIFIER = "blueprint"`), not by a suffix on the artifactId.
The artifactId is taken verbatim; no stripping, no required marker.

### 4.2. Invariants

- The Maven coordinate pair `(groupId, artifactId)` is globally unique by Maven's own rules, so derived namespaces are globally unique by construction.
- Two deploys of the same blueprint (same coords, different Maven version) resolve to the **same** namespace. Streams survive blueprint version bumps.
- A rename of `groupId` or `artifactId` produces a different namespace. Old and new streams are independent from the cluster's perspective.

### 4.3. Reserved check

The derived namespace must not match any reserved pattern (case-insensitive). For RC1 the reserved patterns are:

- The exact token `system`.
- Any namespace whose first dot-separated segment is `system` (i.e., `system.*`). This closes the family of system-adjacent names against accidental collision and against social-engineering vectors (e.g., a malicious blueprint claiming `system.audit` to look authoritative).

Total namespace length (after derivation) must not exceed **128 characters**. This protects KV-Store key bounds and is generous for any practical Maven coordinate. The cap can be raised in a future minor version without breaking existing namespaces.

Both rules are enforced:

- At **build time** by the jbct blueprint tooling (`GenerateBlueprintMojo`), with a clear error pointing at the blueprint artifact and the offending pattern (reserved match or length).
- At **runtime** by the cluster, which refuses to deploy any blueprint whose derived namespace fails either check.

The reserved set is expected to grow in future minor versions. The spec does not list speculative future reservations; operators should treat the list as extensible.

## 5. Version format

- Version is a three-part integer triplet: `MAJOR.MINOR.PATCH`.
- No pre-release tags. No build metadata. No `v` prefix.
- Comparison for resolving `latest` is lexicographic on the triplet: `(1,0,0) < (1,0,1) < (1,1,0) < (2,0,0)`.
- Versions are **immutable** once registered. To fix a bug in `1.0.0` you register `1.0.1`.
- Future widening to full SemVer 2.0.0 is backward-compatible — all current versions remain valid.

## 6. `system` namespace

The `system` namespace is reserved for framework-internal streams.

### 6.1. Rules

- **Closed write path, enforced at compile time.** Two publisher SPIs exist: `StreamPublisher<T>` (resolved by the slice runtime, available to apps) and `FrameworkStreamPublisher<T>` (sealed, resolved only by framework-internal injection). The `system:*` namespace is reachable **only** through `FrameworkStreamPublisher`. App slice code cannot obtain a publisher for system streams — the API surface does not expose one. This makes the framework-vs-app boundary a compile-time invariant rather than a runtime check.
- **HTTP write path closed.** `POST /api/streams/system/...` returns `405 Method Not Allowed` regardless of authenticated role. Even ADMIN cannot inject events into system streams via HTTP. Operators who need to test event flow use a non-system namespace.
- **Read access is open in RC1.** Subscribing to `system:*` from a slice is unrestricted. HTTP read routes follow the standard role buckets in §12. Fine-grained read RBAC (per-namespace gating) is deferred to issue #205.
- **Registration is driven by the framework at bootstrap**, not by blueprint deploy. The cluster creates and holds references to system streams for its entire lifetime.
- **Lifecycle.** System streams live as long as the cluster runs. The framework's producer reference is always held (§8), so reference count never drops to zero.

### 6.2. First tenant

`system:cluster-events:1.0.0` — the structured cluster event stream (see issue #165 scope). Replaces the current per-node `RingBuffer<ClusterEvent>` with a namespace-addressed stream. The v1.0.0 envelope schema is locked in §6.4.

### 6.3. Future system streams

Additional system streams may be introduced by framework versions. Each is independently addressable, independently versioned, and independently retained. Adding a system stream is a minor-version change in the framework.

### 6.4. `system:cluster-events:1.0.0` envelope schema

Per §5, a stream's schema is immutable once registered. The v1.0.0 envelope is fixed by this section.

#### 6.4.1. Type model

`ClusterEvent` is a sealed interface. Each known framework event is a record implementing it. An open extension hatch (`ExtendedEvent`) permits framework extensions to define additional variants without modifying the sealed parent.

```java
sealed interface ClusterEvent permits
    NodeJoined, NodeLeft, /* ... 22 other framework variants ... */,
    StreamRegistered, StreamDeleted,
    ExtendedEvent {

    EventId id();          // total cluster ordering: per-node monotonic sequence + nodeId
    Instant timestamp();   // wall-clock UTC at emission (HLC if available)
    NodeId sourceNode();
}

non-sealed interface ExtendedEvent extends ClusterEvent {
    String discriminator();  // free-form unique tag identifying the variant
                             // recommended convention: FQN-of-record + ":" + variant-name
}
```

The v1.0.0 closed-set count is **26 variants**: the existing 24 framework event types plus `STREAM_REGISTERED` and `STREAM_DELETED` introduced by this spec.

#### 6.4.2. Consumer pattern

Consumers exhaust the sealed parent. Java's pattern matching enforces that every closed variant has a case AND that an `ExtendedEvent` arm is present:

```java
switch (event) {
    case NodeJoined nj          -> handleNodeJoined(nj);
    case DeploymentCompleted dc -> handleDeploymentCompleted(dc);
    /* ... all 24 closed cases ... */
    case StreamRegistered sr    -> handleStreamRegistered(sr);
    case StreamDeleted sd       -> handleStreamDeleted(sd);
    case ExtendedEvent ext      -> handleExtension(ext);  // registry dispatch, log, or no-op
}
```

The `ExtendedEvent` arm is the consumer's choice — typically a discriminator-keyed dispatch to per-extension handlers, or a structured log for operational visibility, or a no-op if the consumer doesn't care about extensions.

#### 6.4.3. Extension events — closed-write principle still applies

`ExtendedEvent` is an extension hatch for **framework extensions** (plugins, framework modules), not for arbitrary application slices. The §6.1 closed-write principle still applies: only `FrameworkStreamPublisher` callers can publish to `system:cluster-events:*`, regardless of whether the event is a closed-set variant or an `ExtendedEvent`. Apps cannot publish extended events into system streams.

#### 6.4.4. Versioning policy

Per §5, future schema evolution follows these rules:

| Change kind | Bump | Example |
|---|---|---|
| New variant added to the closed set | **MINOR** (1.x.0) | Adding `STREAM_RESIZED` |
| New optional payload field on existing closed-set record | **MINOR** | Adding `triggeredBy` to `NodeJoined` |
| New `ExtendedEvent` record introduced by an extension | **No version bump** — schema's openness was committed in v1.0.0 |
| Closed-set variant removed; `ExtendedEvent` removed; payload field renamed or semantics changed | **MAJOR** (x.0.0) | Removing `NodeDrainRequested`; renaming a field |

Consumers MUST gracefully ignore unknown closed-set tags they receive (forward compatibility for minor bumps). The compile-time exhaustiveness check on the sealed parent ensures consumers never *implicitly* miss a known variant — but at the wire-decode layer, an unknown closed-set tag from a newer framework version is decoded as an opaque "unhandled framework variant" event.

#### 6.4.5. Codec (implementation note)

Use `SliceCodec` (compile-time generated, deterministic hash-based tags, VLQ encoding) for the closed-set variants — same codec family used elsewhere in the codebase. `ExtendedEvent` uses a discriminator-prefixed wire form: `[ext-tag][discriminator-len][discriminator][payload-bytes]`. Closed-set wire form: `[compile-time-tag][payload-bytes]`. The decoder distinguishes by leading tag namespace.

## 7. Registry layout (KV-Store)

The cluster's KV-Store holds stream metadata under the following key structure:

```
stream-meta:{namespace}:{stream}:{version}
stream-refs:{namespace}:{stream}:{version}
stream-cursor:{namespace}:{stream}:{version}:{partition}:{consumerGroup}
```

### 7.1. Metadata entry

`stream-meta:{addr}` payload (minimal RC1 shape):

```
{
  registered_at: timestamp,
  registered_by: { kind: "blueprint" | "framework", identity: string },
  retention: { ... },               // per-version retention config; see streaming-spec.md
  partition_count: integer,         // immutable per-version
  access_policy: { system: bool }   // coarse RBAC flag; true only for system namespace
}
```

### 7.2. Reference count entry

`stream-refs:{addr}` payload tracks live references for lifecycle management (§8). Updates flow through consensus.

### 7.3. Cursor entry

Cursor keys extend the existing shape from `streaming-spec.md` by inserting the full address ahead of partition and consumer group. Every cursor is scoped to a specific `(namespace, stream, version)` triple — consumer groups are **not** shared across versions.

## 8. Lifecycle — reference counting

A stream exists if and only if at least one reference to it is held.

### 8.1. Reference sources

A reference is held by any of:

1. **Slice instance in ACTIVE state.** Every slice instance currently in ACTIVE state that declares a stream resource (producer or consumer binding) holds **one reference per declared `(stream, role)`**. Accounting is **per instance**, not per slice declaration: 5 ACTIVE replicas of a slice contribute 5 refs to each of its declared streams. Acquired on transition INTO ACTIVE; released on transition OUT (UNLOADING / FAILED / UNLOADED).
2. **Framework system stream.** For system streams, the framework holds one persistent reference per registered stream for the cluster's lifetime. The framework producer reference never releases while the cluster runs.
3. **Durable consumer group.** A consumer group record persisted in the registry holds a reference whether or not any consumer is currently connected, and whether or not any slice instance is currently ACTIVE. Removing the group (via `aether stream group delete` or HTTP DELETE — see §8.6, §12) releases its reference.

Operator inspection paths (`aether stream tail`, `aether stream peek`, `GET /api/streams/.../tail`) are **read-only queries and do not hold references**. Operators who need data preserved past slice undeploy create an explicit durable consumer group first (see §8.4).

Liveness criterion: slice ACTIVE state is the single source of truth for refs from sources #1. The cluster's existing slice lifecycle FSM (DOWNLOADING → LOADING → STARTING → ACTIVE → UNLOADING → UNLOADED → FAILED), already tracked via consensus-replicated `SliceNodeValue` writes, drives all increment/decrement operations. No separate heartbeat protocol or TCP-keepalive logic is required.

### 8.2. Creation

On the **first** reference, the cluster:

1. Validates the address (namespace, stream, version grammar and reserved checks).
2. Writes `stream-meta:{addr}` and `stream-refs:{addr} = 1` atomically via consensus.
3. Emits a cluster event announcing the stream (subject to §14).

A reference attempt that loses the consensus race (e.g., two blueprints register the same version in the same round) finds the stream already registered and proceeds to increment the refcount.

### 8.3. Destruction

When the **last** reference is released, the cluster:

1. Closes all runtime resources for the stream (partition leaders, write path, read path).
2. Deletes persisted data segments.
3. Deletes `stream-meta:{addr}`, `stream-refs:{addr}`, and all cursor entries.
4. Emits a cluster event announcing the deletion (subject to §14).

### 8.4. Retention vs. lifecycle

- Retention caps how much history is retained **within** the stream's lifetime.
- Retention does **not** extend lifetime. When the last reference is gone, data is deleted regardless of retention age.
- An operator who needs to preserve data past blueprint uninstall creates an explicit durable consumer group before uninstalling. The group holds the reference; data survives per retention until the group is removed.

### 8.5. Concurrency

Refcount increment and decrement **piggyback on slice-state KV writes** that already flow through consensus. Specifically: when `SliceNodeValue` transitions a slice instance into or out of ACTIVE, the same consensus round atomically updates the relevant `stream-refs:{addr}` entries for that slice's declared streams. No separate consensus path or refcount commit cadence is introduced.

Cleanup is **immediate** on refcount-to-zero: there is no soft-delete window, no resurrection grace period. Once the consensus decrement commits with the new value at zero, the cleanup steps in §8.3 begin. Operators or framework code that need data preserved across a brief gap must create a durable consumer group first (per §8.4).

A reference attempt concurrent with the last-reference release is resolved deterministically by consensus order:

- If the increment commits before the decrement commits, the stream remains alive at refcount ≥ 1; the new reference proceeds normally.
- If the decrement commits first and reaches zero, cleanup proceeds; a subsequent increment attempt finds the stream gone and creates a fresh stream at the same address (with empty data).

This is not a race in the conventional sense — consensus order makes both outcomes well-defined. Callers treat either as normal. Slice deployment FSMs are responsible for ensuring rolling restarts maintain replica overlap so that the refcount never accidentally reaches zero across a normal restart.

### 8.6. Operator CLI surface

The `aether stream` command group provides operator access to the streaming subsystem. Read-only commands do not hold references (see §8.1). Destructive commands prompt for interactive confirmation by default; `--force` skips the prompt for scripting.

| Command | Purpose | Role (existing mgmt-API model) |
|---|---|---|
| `aether stream list [--namespace <ns>]` | List streams, optionally filtered by namespace | `ALL_AUTHENTICATED` (read) |
| `aether stream show <address>` | Show metadata + current refcount | `ALL_AUTHENTICATED` (read) |
| `aether stream tail <address>` | SSE/WebSocket tail; read-only, no ref held | `ALL_AUTHENTICATED` (read) |
| `aether stream delete <address> [--force]` | Force-purge a specific stream version. Interactive confirmation prompts with the address and last-known refcount. `--force` skips the prompt | `ADMIN_ONLY` (destructive) |
| `aether stream group create <address> <group>` | Create durable consumer group; holds a reference until removed | `OPERATOR_AND_ABOVE` (write) |
| `aether stream group delete <address> <group> [--force]` | Remove durable consumer group; releases its reference (may cascade to stream deletion if last ref) | `OPERATOR_AND_ABOVE` (write) |

`aether stream delete` acts as a **force-purge**, not as a graceful retirement. There is no graceful "decommission" mode — the spec's mental model is reference-counted lifecycle (§8.1, §8.5), and the right way to retire a stream gracefully is to remove the references that hold it (undeploy the slices that produce/consume it, or remove the durable groups that retain it).

Role assignments use the existing management-API role model (`VIEWER` < `OPERATOR` < `ADMIN`); see §12 for HTTP route role bindings. Stream-level RBAC for slice publish/subscribe paths is separate and currently open in RC1 (see §10).

## 9. Resolution semantics

Stream addresses are resolved by the streaming subsystem's name-mapper at each attach or subscribe.

### 9.1. Writers (producers)

- Writers **must** specify an exact version. The literal `latest` is not valid for writes.
- A write to a version that is not registered fails with an explicit "version not found" error.

### 9.2. Readers (consumers)

- Readers may specify an exact version or the literal token `latest`.
- `latest` resolves to the highest registered version for that `(namespace, stream)` at subscribe time.
- If no version is registered, `latest` resolution fails with an explicit "no versions registered" error.

### 9.3. Pinning durability

Resolution is **pin-at-subscribe**:

- For a **durable consumer group**, the resolved version is stored in the consumer group's registry entry. Reconnects re-attach to the same version regardless of whether newer versions have been registered.
- For an **anonymous tail** (no consumer group — e.g., `/api/streams/.../tail` HTTP streaming, WebSocket subscribers), the version is resolved per-connection and **frozen for the connection's lifetime**. Once a tail is connected on version V, it receives V's events for as long as the connection stays open, even if a newer version is registered mid-stream. To pick up the newer version, the client disconnects and reconnects; the new connection's `latest` resolution may yield the newer version. The server does not push cutover messages or close connections in response to new-version registration.
- To advance a durable consumer group to a newer version, the operator creates a new consumer group at the new version. The old group remains on the old version until it is removed.

### 9.4. Registration

- Registration is **automatic on blueprint deploy**. When a blueprint is deployed, each of its declared stream resources is resolved; if the `(namespace, stream, version)` triple does not exist, it is registered.
- Registration is **idempotent** per triple. A re-deploy of a blueprint that references an already-registered version is a no-op for registration.
- System streams are registered by the framework at cluster bootstrap, not via blueprint.

## 10. Access control

The spec distinguishes two access-control layers that operate independently:

1. **Management API access control** (HTTP routes) — handled by the existing `VIEWER`/`OPERATOR`/`ADMIN` role model on `RoutePermission`/`RoutePermissionRegistry`. Specific bindings for stream routes are listed in §12. **All HTTP stream routes require authentication; none are anonymous.**
2. **Stream-level access control** (slice-level `publish`/`subscribe` calls) — open in RC1, deferred to issue #205 for fine-grained policy.

### 10.1. RC1 model — slice-level access

Slice-to-stream access is **open** in RC1:

- **Application namespaces.** Any slice can publish to or subscribe from any application-namespaced stream, in its own blueprint or in another's. Cross-namespace reads are **allowed without explicit grant**. This is the deliberate RC1 simplification — the whole point of namespacing is to enable cross-app composition; gating it out of the gate would defeat the model.
- **`system:*` reads.** Slice subscriptions to system streams (e.g., `system:cluster-events:1.0.0`) are unrestricted. Any slice can subscribe.
- **`system:*` writes.** Closed at compile time via the sealed-SPI split (`StreamPublisher` for apps, `FrameworkStreamPublisher` for framework). Apps cannot reach a system-namespace publisher; the API surface does not expose one. See §6.1.

This is the minimum viable model. It does not foreclose future RBAC; it explicitly defers it.

### 10.2. No `Principal` threading in RC1

Stream APIs (`publish`, `subscribe`, attach paths) **do not take a `Principal` parameter** in RC1. With no ACL gating to perform, threading principal through every call is dead weight. When fine-grained RBAC lands (issue #205), the API will be revised at that point to thread principal — informed by real use cases rather than speculation. The signature change is acknowledged as a future API break; it is preferable to carrying an unused parameter that may turn out to have the wrong shape.

Audit logging (which DOES need to record "who published what" for some scenarios) is handled via the management-API audit path (`AuditLog`), which already has principal context from the HTTP authentication layer. Slice-internal publishes are not audit-logged in RC1.

### 10.3. Forward reference

Issue **#205** (`on-demand`) tracks the design and implementation of fine-grained stream RBAC, including:

- Cross-namespace read grants — declaration site (producer-side allow-list, consumer-side intent declaration, operator-managed cluster grants, or hybrid).
- `system:*` read gating — restrict to admin or framework-internal capability.
- `Principal` threading on stream APIs.
- Audit logging for slice-level publish/subscribe.

The spec will be amended in §10 when that work lands.

## 11. `resources.toml` syntax

Stream resources in a blueprint's `resources.toml` take one of two forms. The shortcut/default rules favor minimal ceremony for the common single-version-single-blueprint case while preserving precision when needed.

### 11.1. Internal stream (owned by this blueprint)

The minimal form omits both `version` and `role` and looks identical to the existing flat shape:

```toml
[streams.notifications]
partitions = 4
retention = "time"
retention-value = "5m"
```

The namespace is **implicit** — it is the blueprint's derived namespace (§4). The stream's resolved address is `{derived-namespace}:notifications:1.0.0` (default version).

The fully explicit form pins both:

```toml
[streams.orders]
version = "1.0.0"               # exact triplet (producer) or "latest" (consumer-only)
role    = "producer"            # "producer" | "consumer"
# partition_count, retention, etc. per streaming-spec.md
```

#### 11.1.1. Field defaulting

| Field | Required | Default if omitted |
|---|---|---|
| `version` | No | `"1.0.0"` for producer roles, `"latest"` for consumer roles |
| `role` | No | inferred at slice-processor compile time from the binding (see §11.1.2) |
| `source` | No (mutually exclusive with `version`) | — (used only for external streams; see §11.2) |

#### 11.1.2. Role inference from slice binding

When `role` is omitted, the slice-processor inspects how the resource is bound in slice code:

- A constructor parameter typed as `StreamPublisher<T>` (or any subtype/wrapper) → **producer**.
- A method bound via a consumer annotation (e.g., `@OnEvent`) or a parameter typed as `StreamSubscriber` → **consumer**.
- Both bindings present in the same slice → **both roles**.

Inference is **strict**: ambiguous bindings (e.g., a `StreamPublisher<T>` parameter on a method also annotated as a consumer) are a build-time error citing the offending site.

#### 11.1.3. Validation rules

Build-time rules enforced by jbct:

- `[streams.X]` declaring `role = "producer"` (explicit or inferred) with `version = "latest"` → **error**. Producers must pin to an exact triplet.
- A blueprint registering more than one version of the same `(namespace, stream)` while at least one declaration omits `version` → **warning**. The default may resolve to a version other than the one the author intends; an explicit pin is recommended.
- The stream name (`notifications`, `orders`, etc.) must match the charset rules in §3.2.
- `version` and `source` cannot both appear on the same `[streams.X]` section (see §11.3).

These rules run in the build-time gate per §15. The runtime gate re-runs them on deploy.

### 11.2. External stream (consumer referring to another blueprint's namespace, or system namespace)

```toml
[streams.inventory_feed]
source = "io.acme.inventory:stock-updates:2.0.0"
role   = "consumer"
```

The `source` field is a fully-qualified three-component address. Presence of `source` signals an external stream; `version` is not used in this form (the version is part of `source`).

Parser rules:

- `source` must parse as `namespace:stream:version` per §3.2.
- `role` must be `"consumer"`. Writing to external streams is not expressible via blueprint declaration for RC1 (write grants are tied to the closed-write principle for system streams and to the deferred fine-grained RBAC of #205 for app-to-app).
- At runtime, the subscribe call is **not gated** by ACLs in RC1 (per §10.1, all reads are open). Future RBAC will gate via #205.

### 11.3. Incompatible forms

The parser rejects:

- Presence of both `source` and `version` on the same `[streams.X]` section (contradictory — one form or the other).
- Presence of both `source` and any other internal-stream-only fields (`partitions`, `retention`, etc.) — external-stream consumers do not declare those parameters; the source's owner does.
- A stream name in the section header containing a colon (reserved for the `source` form's address syntax).

## 12. HTTP API shape

URL shape: `/api/streams/{namespace}/{stream}[/{version}]`

- Namespace is a single path segment; its internal `.`, `_`, and `-` characters are legal in URL path segments and pass through unescaped.
- Stream is a single path segment.
- Version is optional. When omitted, resolution follows the reader's `latest` semantics (§9.2).

### 12.1. Routes

Role bindings reference the existing management-API role model (`VIEWER` < `OPERATOR` < `ADMIN`) and `RoutePermissionRegistry`. RC1 adds a DELETE-specific override to elevate stream version deletion to `ADMIN_ONLY`.

#### Read routes — `ALL_AUTHENTICATED` (any authenticated role)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/streams` | List streams. Query: `?namespace=<ns>&limit=N&cursor=X` |
| GET | `/api/streams/{ns}/{stream}` | List versions of a stream |
| GET | `/api/streams/{ns}/{stream}/{version}` | Stream metadata: refcount, partition_count, retention, registered_at |
| GET | `/api/streams/{ns}/{stream}/latest` | Resolve to highest registered version per §9.2 |
| GET | `/api/streams/{ns}/{stream}/{version}/tail` | Tail subscription. SSE for HTTP/2; WebSocket for bidirectional. Frozen-at-connect per §9.3 |
| GET | `/api/streams/{ns}/{stream}/{version}/groups` | List durable consumer groups for this version |

#### Write routes — `OPERATOR_AND_ABOVE` (existing `/api/streams` mutation default)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/streams/{ns}/{stream}/{version}/publish` | Publish a single event |
| POST | `/api/streams/{ns}/{stream}/{version}/publish-batch` | Batch publish; body is an array of events |
| POST | `/api/streams/{ns}/{stream}/{version}/groups` | Create durable consumer group; body specifies group name + initial position |
| DELETE | `/api/streams/{ns}/{stream}/{version}/groups/{group}` | Remove durable consumer group; releases its reference (may cascade to stream deletion if last ref) |

#### Destructive routes — `ADMIN_ONLY` (override)

| Method | Path | Purpose |
|---|---|---|
| DELETE | `/api/streams/{ns}/{stream}/{version}` | Force-purge a specific version. Mirrors §8.6 CLI `aether stream delete` |

`RoutePermissionRegistry` requires a DELETE-specific override under the `/api/streams` prefix to elevate this route from the prefix's default `OPERATOR_AND_ABOVE` to `ADMIN_ONLY`. The `groups/{group}` DELETE remains at `OPERATOR_AND_ABOVE`.

### 12.2. `system:*` HTTP behavior

The closed-write principle from §6.1 extends to HTTP: any HTTP write attempt against `system:*` returns **`405 Method Not Allowed`** regardless of authenticated role:

```
POST   /api/streams/system/cluster-events/1.0.0/publish        → 405
POST   /api/streams/system/cluster-events/1.0.0/publish-batch  → 405
POST   /api/streams/system/cluster-events/1.0.0/groups         → 405  # groups are write paths
DELETE /api/streams/system/cluster-events/1.0.0                → 405  # framework-only lifecycle
DELETE /api/streams/system/cluster-events/1.0.0/groups/X       → 405
```

Read routes against `system:*` follow the standard role buckets (`ALL_AUTHENTICATED` for GET). Operators who want to test event flow use a non-system namespace.

### 12.3. Status code conventions

| Code | Meaning |
|---|---|
| `200` | Success with body (GET) |
| `201` | Resource created (publish, group create) |
| `204` | Success with no body (delete) |
| `400` | Malformed address or version format |
| `404` | Namespace, stream, version, or group not found |
| `405` | HTTP write to `system:*` |
| `409` | Refcount race during destructive op (e.g., DELETE on a stream that just gained a new ref) |
| `410` | Stream existed but was deleted; address is re-registerable |

### 12.4. Tail protocol

Tail subscriptions support both:

- **Server-Sent Events (SSE)** — for HTTP/2 clients; one-way push from server.
- **WebSocket** — for clients that need bidirectional control frames (e.g., to ack receipt or to switch consumer-group offset).

Both share the framework's internal stream fan-out. The choice is per-connection: the client requests SSE via `Accept: text/event-stream` or upgrades to WebSocket via the standard handshake. The server selects the protocol from the request headers.

### 12.5. Examples

```
GET    /api/streams/system/cluster-events/1.0.0                 # metadata
GET    /api/streams/system/cluster-events/1.0.0/tail            # tail (SSE or WS)
GET    /api/streams/com.example.myapp/orders                    # list versions of orders
GET    /api/streams/com.example.myapp/orders/latest             # latest metadata
POST   /api/streams/com.example.myapp/orders/1.0.0/publish      # publish (OPERATOR)
DELETE /api/streams/com.example.myapp/orders/1.0.0              # force-purge (ADMIN)
```

Access control per §10 (slice level — open in RC1) and §12.1 (route level — existing role model) apply at their respective layers.

## 13. Lifecycle event ordering

Stream lifecycle events (`STREAM_REGISTERED`, `STREAM_DELETED`) follow strict ordering with respect to the stream's existence. This ordering is what allows a stream to record events about its own lifecycle — including creation and deletion of `system:cluster-events:*` itself — without circularity. Publishing data into a stream is a data-flow operation; it does **not** itself emit a new lifecycle event, so there is no recursive loop.

### 13.1. Registration order

1. The stream's `stream-meta:{addr}` and initial `stream-refs:{addr} = 1` are committed atomically via consensus. **Once committed, the stream exists and is addressable.**
2. The `STREAM_REGISTERED` event is then emitted.
3. The event is published into its target event stream (typically `system:cluster-events:1.0.0`). The publish succeeds because the stream exists per step 1.

### 13.2. Deletion order

1. The last reference is released; refcount reaches zero (per §8.5).
2. The `STREAM_DELETED` event is emitted **while the stream still exists**.
3. The event is published into its target event stream. The publish succeeds because the stream has not yet been torn down.
4. The cluster proceeds with cleanup per §8.3: closes runtime resources, deletes data segments, deletes `stream-meta:{addr}`, `stream-refs:{addr}`, and all cursor entries.

### 13.3. Bootstrap edge case

The very first registration of `system:cluster-events:1.0.0` itself has no pre-existing target event stream. The framework's implementation handles this by **logging the `STREAM_REGISTERED` event for `system:cluster-events:1.0.0` at the framework log level rather than publishing it into a stream**. This is an implementation note, not a spec requirement; subsequent registrations (including any future system event streams) follow the standard §13.1 flow with `system:cluster-events:1.0.0` as the target.

### 13.4. Application-stream lifecycle events

Application-namespace stream lifecycle events follow the same ordering rules and are published normally to `system:cluster-events:1.0.0`. There is no special filter for app-stream events.

## 14. Relationship to existing streaming specs

This document layers addressing and lifecycle semantics on top of the mechanical streaming subsystem. Existing specs retain ownership of their respective topics:

| Topic | Owner spec |
|---|---|
| Stream addressing, versioning, namespace derivation | **This spec** |
| Reference-counted lifecycle | **This spec** |
| Access control for stream operations | **This spec** (coarse); `rbac-spec.md` (role definitions) |
| Partition assignment, replication, consensus | `streaming-spec.md` |
| In-memory stream variant | `in-memory-streams-spec.md` |
| Read-forwarding wire protocol | `streaming-read-forwarding-spec.md` |
| Codec / payload serialization | `streaming-spec.md` |

### 14.1. Required edits to existing specs

- `streaming-spec.md`: KV key examples updated from `stream-meta:{streamName}` to `stream-meta:{namespace}:{stream}:{version}`. Cursor keys likewise extended. Add a pointer to this spec in the "Naming" section (if present) or early overview.
- `in-memory-streams-spec.md`: likewise.
- `streaming-read-forwarding-spec.md`: wire-protocol references to `streamName` as a flat string updated to use the three-component address.

These are mechanical consistency edits and are part of the same change set.

## 15. Build-time validation (jbct)

The blueprint build (jbct / `GenerateBlueprintMojo`) must enforce:

1. Derived namespace is not reserved (case-insensitive check against `{"system"}`). Build fails otherwise.
2. Each stream resource in `resources.toml` satisfies the grammar and parser rules in §3.2 and §11.
3. Each producer stream resource specifies an exact version (not `"latest"`).

Blueprint identity itself is signalled by the Maven classifier `blueprint` on the produced JAR
(`GenerateBlueprintMojo.CLASSIFIER`); there is no required suffix on the artifactId. The artifactId
flows into the namespace verbatim.

Build-time validation catches configuration errors early; runtime validation (§15.1) catches any that slip past (e.g., from hand-edited blueprints or legacy toolchains).

### 15.1. Runtime validation

The cluster re-runs §15 checks on blueprint deploy. Validation runs **synchronously, atomically, before any cluster state mutation**. If any check fails, the deploy is rejected; no `stream-meta` is written, no `stream-refs` is incremented, no slice transitions begin. The operator fixes the blueprint and re-deploys.

#### 15.1.1. All-failures aggregation

The validator collects **all** failures from a single deploy attempt rather than short-circuiting on the first. This avoids the iterative single-fix-redeploy treadmill that operators would otherwise face on multi-error blueprints.

Implementation: validation is composed via `Result.all(<check1>, <check2>, ...)` from Pragmatica core, which automatically combines all failures into a single composite error. Each check is a `Result<Unit>`; the composite carries the full failure set.

#### 15.1.2. Error transport

Failures are reported through three channels, all derived from the same composite error:

- **HTTP deploy API** (`/api/blueprint/deploy`): structured error in response body, listing each failing field path with the specific cause. Existing `/api/blueprint/validate` (`BLUEPRINT_VALIDATE` route) uses the same shape; the deploy route reuses that shape.
- **CLI** (`aether blueprint deploy`): human-formatted error block on stderr; non-zero exit code.
- **AuditLog**: every rejection logged with the failing field paths, the principal that attempted the deploy, and the blueprint identifier.

## 16. RC1 acceptance checklist

- Three-component `StreamAddress`, `StreamVersion`, `BlueprintNamespace` value types implemented; address parser/validator with structured rejection messages citing the offending component.
- Namespace charset (`[a-z0-9._-]+`, max 128 chars) enforced at parse time.
- `system` exact + `system.*` prefix reservation enforced at build time (`GenerateBlueprintMojo`) and runtime (deploy gate).
- Blueprint coordinate → namespace derivation (`namespace = groupId + "." + artifactId`) implemented in jbct blueprint build; classifier-based blueprint identity (existing `GenerateBlueprintMojo.CLASSIFIER = "blueprint"`).
- KV-Store key layout per §7 implemented:
  - `stream-meta:{ns}:{stream}:{version}`, `stream-refs:{ns}:{stream}:{version}`, `stream-cursor:{ns}:{stream}:{version}:{partition}:{group}`.
  - New `AetherKey` types added; existing flat `streamName` keys migrated.
- Sealed-SPI split: `StreamPublisher<T>` (apps) and `FrameworkStreamPublisher<T>` (sealed, framework-only). Apps cannot obtain a `system:*` publisher.
- Reference counting tied to slice ACTIVE state per §8.1: per-instance accounting; refcount updates piggyback on `SliceNodeValue` consensus writes.
- Lifecycle event ordering per §13: registration commits before event emit; deletion event publishes before cleanup.
- Cleanup on refcount-to-zero is immediate; no soft-delete window; no resurrection grace period.
- Anonymous tails frozen at connect per §9.3; durable consumer groups pin version in registry.
- `resources.toml` parser:
  - Accepts shortcut form (no `version`, no `role`) with defaults per §11.1.1.
  - Slice-processor infers `role` from binding per §11.1.2; ambiguous bindings are build-time errors.
  - Producer + `version = "latest"` is a build-time error.
  - Multi-version-with-omitted-version warning per §11.1.3.
  - Accepts external `source` form per §11.2; rejects mutually exclusive combinations per §11.3.
- HTTP routes per §12.1 wired into `ManagementRoute` with role bindings:
  - Reads → `ALL_AUTHENTICATED`, writes → `OPERATOR_AND_ABOVE`, stream-version DELETE → `ADMIN_ONLY` override in `RoutePermissionRegistry`.
  - System-namespace HTTP writes return `405 Method Not Allowed` regardless of role.
  - SSE + WebSocket tail subscription protocols.
- `system:cluster-events:1.0.0` registered at framework bootstrap with the v1.0.0 schema locked per §6.4 (sealed `ClusterEvent` + non-sealed `ExtendedEvent`; 26-variant closed set including `STREAM_REGISTERED`/`STREAM_DELETED`).
- Per-node `RingBuffer<ClusterEvent>` replaced by subscription to `system:cluster-events:1.0.0`.
- `aether stream` CLI commands per §8.6: `list`, `show`, `tail`, `delete --force`, `group create`, `group delete --force`.
- Build-time + runtime validation atomic with all-failures aggregation via `Result.all(...)`; error transport via API response, CLI stderr, AuditLog (§15.1).
- Companion specs updated with pointer to this spec and consistent KV-key examples (`streaming-spec.md`, `in-memory-streams-spec.md`, `streaming-read-forwarding-spec.md`).

## 17. Open questions (non-blocking for RC1)

1. **Fine-grained stream RBAC** — RC1 ships with open slice-level read/write access to all application namespaces and open reads on `system:*`. A dedicated design + implementation is tracked in issue **#205** (`on-demand`). Will add the configuration surface for cross-namespace grants, `system:*` read gating, and `Principal` threading on stream APIs.
2. **Schema registry** — version is in the address precisely so schema binding is unambiguous when schemas land. That spec is deferred.
3. **Federation** — a 4-part address (cluster-id prefix) is the natural extension. Deferred; out of scope for RC1.
4. **Version range resolution** — `^1.0.0`, `~1.0`, etc. Not needed for RC1; exact pin plus `latest` covers the space.
5. **Pre-release versions** — not supported by `MAJOR.MINOR.PATCH`-only format. If needed, widen to full SemVer 2.0.0 later (backward-compatible).

## 18. Change log

| Date | Change | Source |
|---|---|---|
| 2026-04-22 | Initial draft | #165 design discussion |
| 2026-05-04 | RC1 design walkthrough — 16 design items resolved. §3.2 namespace charset, §4.3 system.* prefix reservation + 128-char cap, §6 sealed-SPI write enforcement + open reads, new §6.4 cluster-events envelope schema with sealed `ClusterEvent` + `ExtendedEvent` extension hatch, §8.1 slice-ACTIVE-state refs, §8.5 immediate cleanup, §8.6 full CLI surface, §9.3 frozen-at-connect tails, §10 rewrite (open in RC1, no Principal threading, #205 forward reference), §11 shortcut defaults + role inference, §12 full HTTP route table with role bindings + system-write 405, §13 rewrite as ordering rule (no filter), §15.1 `Result.all(...)` aggregation. Acceptance checklist updated. Open question on cross-namespace RBAC promoted to issue #205 (on-demand). | RC1 design session |
