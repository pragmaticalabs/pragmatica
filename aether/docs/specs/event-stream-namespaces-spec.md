# Event Stream Namespaces and Versioning — Specification

**Status:** Draft (RC1)
**Depends on:** `streaming-spec.md`, `in-memory-streams-spec.md`, `rbac-spec.md`
**Superseded by:** _(none)_
**Companion to:** GitHub issue #165

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
maven-derived-namespace := /* see §4 */
```

Reserved stream names (RC1): `latest`. Reserved namespace names (RC1): `system` (case-insensitive check at validation).

## 4. Namespace derivation

Application namespaces are derived from the declaring blueprint's Maven coordinates.

### 4.1. Rule

Given a blueprint Maven artifact with `groupId = G` and `artifactId = A`, where `A` ends with the mandatory `-blueprint` suffix:

```
namespace = G + "." + strip_suffix(A, "-blueprint")
```

Examples:

| groupId | artifactId | Derived namespace |
|---|---|---|
| `com.example` | `myapp-blueprint` | `com.example.myapp` |
| `org.pragmatica.aether` | `forge-blueprint` | `org.pragmatica.aether.forge` |
| `io.acme.billing` | `invoice-service-blueprint` | `io.acme.billing.invoice-service` |

### 4.2. Invariants

- `-blueprint` suffix is **mandatory** on blueprint artifactIds. Blueprint builds must fail if the suffix is missing (see §15).
- The Maven coordinate pair `(groupId, artifactId)` is globally unique by Maven's own rules, so derived namespaces are globally unique by construction.
- Two deploys of the same blueprint (same coords, different Maven version) resolve to the **same** namespace. Streams survive blueprint version bumps.
- A rename of `groupId` or `artifactId` produces a different namespace. Old and new streams are independent from the cluster's perspective.

### 4.3. Reserved check

The derived namespace must not equal any reserved token (case-insensitive). The only reserved token for RC1 is `system`. This is enforced both:

- At **build time** by the jbct blueprint tooling (`GenerateBlueprintMojo`), with a clear error pointing at the blueprint artifact.
- At **runtime** by the cluster, which refuses to deploy any blueprint whose derived namespace is reserved.

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

- **No external write path.** HTTP and client APIs expose read access only. Publishing to `system:*` is possible only from framework code running in the cluster.
- **Read access requires admin role.** See §11 for integration with `rbac-spec.md`.
- **Registration is driven by the framework at bootstrap**, not by blueprint deploy. The cluster creates and holds references to system streams for its entire lifetime.
- **Lifecycle.** System streams live as long as the cluster runs. The framework's producer reference is always held (§8), so reference count never drops to zero.

### 6.2. First tenant

`system:cluster-events:1.0.0` — the structured cluster event stream (see issue #165 scope). Replaces the current per-node `RingBuffer<ClusterEvent>` with a namespace-addressed stream.

### 6.3. Future system streams

Additional system streams may be introduced by framework versions. Each is independently addressable, independently versioned, and independently retained. Adding a system stream is a minor-version change in the framework.

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

1. **Blueprint declaration** — a blueprint's `resources.toml` declaring a stream resource (producer or consumer). Reference lives as long as the blueprint is deployed.
2. **Framework producer** — for system streams, framework code holds an implicit reference for the cluster's lifetime.
3. **Live producer handle** — any open publisher handle outside of a blueprint-declared resource (rare; mainly operator tooling).
4. **Live consumer handle** — any open subscriber/tail connection.
5. **Durable consumer group** — a consumer group record persisted in the registry holds a reference whether or not any consumer is currently connected. Removing the group releases its reference.

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

Refcount increment and decrement flow through consensus on the KV-Store registry entry. A reference attempt concurrent with the last-reference release deterministically either:

- Succeeds against the existing stream (decrement scheduled before the increment sees a refcount of 0) — stream is resurrected.
- Fails with "stream does not exist" (decrement committed, entry removed before the new reference took effect) — client retries, creating a fresh stream at the same address.

Callers treat both as normal.

### 8.6. Explicit deletion

`aether stream delete <address>` is an operator command that removes all references and deletes the stream. Acts as a force-purge, not as a graceful retirement. Requires admin role.

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
- For an **anonymous tail** (no consumer group — e.g., `/api/streams/.../tail` HTTP streaming, `/ws/events` subscribers), the version is resolved per-connection and the subscriber gets whatever is `latest` at connect time. A disconnect followed by a reconnect may see a newer version.
- To advance a durable consumer group to a newer version, the operator creates a new consumer group at the new version. The old group remains on the old version until it is removed.

### 9.4. Registration

- Registration is **automatic on blueprint deploy**. When a blueprint is deployed, each of its declared stream resources is resolved; if the `(namespace, stream, version)` triple does not exist, it is registered.
- Registration is **idempotent** per triple. A re-deploy of a blueprint that references an already-registered version is a no-op for registration.
- System streams are registered by the framework at cluster bootstrap, not via blueprint.

## 10. Access control

### 10.1. Coarse model (RC1)

- **`system` namespace:**
  - Writes: not exposed via any external API. Framework code only.
  - Reads: require **admin role** (role name as defined in `rbac-spec.md`). Applies to both HTTP route access (`/api/streams/system/...`) and any slice-level subscribe calls.
- **Application namespaces:**
  - Writes and reads within the namespace's owning blueprint: allowed by blueprint declaration.
  - Reads across namespaces (app A reads from app B's namespace): **denied by default**. An explicit RBAC grant is required. RC1 does not define a configuration surface for cross-namespace grants — this is deferred to a later ticket. For RC1, cross-namespace reads are simply unavailable outside of system namespace reads by admins.
  - Reads from `system` namespace by an app slice: denied unless the slice's principal has admin role.

### 10.2. Principal threading

All subscribe and publish entry points on the streaming subsystem take an explicit `Principal` parameter. Access decisions are made inside the subsystem based on that Principal and the namespace of the resolved address. Thread-local or ambient principal lookup is not used.

### 10.3. Future work

Fine-grained ACLs (per-stream roles, read-only consumer grants, per-partition policies) are deferred. The RC1 shape is the minimum viable gating and is forward-compatible with a richer policy model.

## 11. `resources.toml` syntax

Stream resources in a blueprint's `resources.toml` take one of two forms.

### 11.1. Internal stream (owned by this blueprint)

```toml
[streams.orders]
version = "1.0.0"               # required; exact triplet (producer) or "latest" (consumer only)
role    = "producer"            # "producer" | "consumer"
# partition_count, retention, etc. per streaming-spec.md
```

The namespace is **implicit** — it is the blueprint's derived namespace (§4). The stream's resolved address is `{derived-namespace}:orders:1.0.0`.

Parser rules:

- `version` is required. Omission is a parse error. Producer declarations must specify an exact triplet; consumer declarations may specify a triplet or the literal `"latest"`.
- The stream name (`orders` in the example) must match the charset rules in §3.2.

### 11.2. External stream (consumer referring to another blueprint's namespace, or system namespace)

```toml
[streams.inventory_feed]
source = "io.acme.inventory:stock-updates:2.0.0"
role   = "consumer"
```

The `source` field is a fully-qualified three-component address. Presence of `source` signals an external stream; the `version` field is not used in this form (the version is part of `source`).

Parser rules:

- `source` must parse as `namespace:stream:version` per §3.2.
- `role` must be `"consumer"`. Writing to external streams is not expressible via blueprint declaration for RC1 (deferred along with cross-namespace write grants).
- At runtime, the subscribe call may be denied by §10 if the blueprint's principal lacks the required role.

### 11.3. Incompatible forms

The parser rejects:

- Absence of both `source` and the `name:version` pair (ambiguous).
- Presence of both `source` and `version` on the same resource (contradictory).
- A `name` declared in the section header containing a colon (reserved for the `source` form).

## 12. HTTP API shape

`/api/streams/{namespace}/{stream}[/{version}]`

- Namespace is a single path segment; its internal `.` and `-` characters are legal in URL path segments and pass through unescaped.
- Stream is a single path segment.
- Version is optional. When omitted, resolution follows the reader's `latest` semantics (§9.2). An explicit `?version=latest` is equivalent.

Examples:

```
GET  /api/streams/system/cluster-events                         # latest
GET  /api/streams/system/cluster-events/1.0.0                   # specific
GET  /api/streams/com.example.myapp/orders/1.0.0                # app stream
POST /api/streams/system/cluster-events/subscribe               # not valid — system namespace has no external write path; admin-read only
```

Access control per §10 applies at the route layer.

## 13. Circular-dependency rule

System-namespace stream lifecycle events must **not** be produced into `system:cluster-events:*`. Specifically, the cluster event aggregator filters out lifecycle events (`STREAM_REGISTERED`, `STREAM_DELETED`, and analogous events) whose address falls under `system:*`. Without this filter, creating `system:cluster-events:1.0.0` would produce an event describing the creation of `system:cluster-events:1.0.0` and publish that event into `system:cluster-events:1.0.0` — a self-referential loop.

Application-namespace stream lifecycle events are produced normally.

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

1. Blueprint artifactId ends with `-blueprint`. Build fails with a clear message otherwise.
2. Derived namespace is not reserved (case-insensitive check against `{"system"}`). Build fails otherwise.
3. Each stream resource in `resources.toml` satisfies the grammar and parser rules in §3.2 and §11.
4. Each producer stream resource specifies an exact version (not `"latest"`).

Build-time validation catches configuration errors early; runtime validation (§15.1) catches any that slip past (e.g., from hand-edited blueprints or legacy toolchains).

### 15.1. Runtime validation

The cluster re-runs §15 checks on blueprint deploy. A blueprint failing any check is rejected at deploy time with a specific error naming the offending field.

## 16. RC1 acceptance checklist

- Three-component address parser and validator implemented; rejection messages cite the offending component.
- `system` namespace reserved; build-time and runtime checks in place.
- Blueprint coordinate → namespace derivation implemented in jbct blueprint build.
- KV-Store key layout per §7 implemented in the streaming subsystem.
- Reference counting for streams: attach/detach paths increment/decrement; last-reference release cleans registry, data, and cursors.
- Name-mapper resolves `latest` for readers and exact version for writers per §9.
- Consumer group cursors include version; anonymous tails resolve per-connection.
- `resources.toml` parser accepts both internal and external forms, rejects invalid combinations.
- `/api/streams/{namespace}/{stream}[/{version}]` route with admin gate for `system` namespace reads.
- System stream lifecycle events filtered from `system:cluster-events` to prevent self-referential loop.
- Existing streaming specs updated with pointer to this spec and consistent KV-key examples.

## 17. Open questions (non-blocking for RC1)

1. **Cross-namespace read grants** — what's the configuration surface for one app reading another's streams? RC1 denies these by default; a dedicated spec will follow in RC2 if demand emerges.
2. **Schema registry** — version is in the address precisely so schema binding is unambiguous when schemas land. That spec is deferred.
3. **Federation** — a 4-part address (cluster-id prefix) is the natural extension. Deferred; out of scope for RC1.
4. **Version range resolution** — `^1.0.0`, `~1.0`, etc. Not needed for RC1; exact pin plus `latest` covers the space.
5. **Pre-release versions** — not supported by `MAJOR.MINOR.PATCH`-only format. If needed, widen to full SemVer 2.0.0 later (backward-compatible).

## 18. Change log

| Date | Change | Source |
|---|---|---|
| 2026-04-22 | Initial draft | #165 design discussion |
