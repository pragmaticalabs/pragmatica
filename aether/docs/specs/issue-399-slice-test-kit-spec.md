<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
See LICENSE in the repository root for full terms.
-->

# Slice Test Kit — Design Specification

**Issue:** [#399](https://github.com/pragmaticalabs/pragmatica/issues/399) — "Slice test kit: spin a slice with fakes/testcontainers + typed client (no full forge)"
**Milestone:** v1.0.0-rc2 · **Labels:** `enhancement`, `rc2` · **Status:** DRAFT — for approval, not implementation
**Related:** #392 (forge can't load `@Http` slices), #14 (wishlist origin), Ember/Forge in-JVM runtime

> This document is a design proposal. It does not change any code. Decisions reserved for the
> maintainer are marked **OPEN QUESTION** with options and a recommendation. Other choices are
> stated as `[ASSUMPTION]` and can be overridden in review.

---

## 1. Problem & Goal

### 1.1 The testing gap

A slice author today has exactly two ways to test a slice, and neither fits the common case:

1. **Unit-test the impl record directly.** Call the hand-written static factory with hand-rolled
   doubles, e.g. `UrlShortener.urlShortener(InMemoryUrlPersistence.inMemoryUrlPersistence())`
   (`examples/url-shortener/src/test/java/.../shortener/UrlShortenerTest.java:19`). This is fast but
   **bypasses all generated machinery**: the generated `{Interface}Factory`, resource resolution
   through `ResourceProviderFacade`, and the interceptor/`Aspect` stack. It proves the business logic
   but not that the slice is *wired correctly* — the exact class of defect #392 describes ("forge
   can't even load `@Http` slices").

2. **Deploy the whole blueprint on a forge / Ember cluster.** `Ember.cluster(5).start()` spins up a
   real five-node cluster with Rabia consensus, SWIM, DHT, QUIC transport and a 60-second
   stabilization await (`aether/ember/src/main/java/.../EmberCluster.java:334-382`,
   `EmberInstance.java:35-43`). Node construction alone assembles ~30 config subsystems
   (`EmberCluster.java:630-686`). This is heavy, slow, and Docker- or multi-JVM-shaped — wrong
   granularity for "does *this one slice* behave when I give it a fake HTTP backend and a real
   Postgres?".

There is no first-class middle path: **instantiate one slice through its real generated factory,
inject a fake or a testcontainer-backed resource per dependency, and drive it via its own typed
interface** — no consensus, no cluster, no deploy archive.

### 1.2 Goal

Ship a reusable **Slice Test Kit** that lets a plain JUnit test:

- **G1 — Spin one slice** through its real generated `{Interface}Factory` and `Aspect` stack, with
  no forge, no cluster, no archive.
- **G2 — Swap each resource** (`@PgSql`/`@Sql`, `@Http`, `@Notify`, pub-sub `Publisher`, cache,
  scheduled, …) for either an **in-memory fake** or a **testcontainer-backed real** resource, chosen
  per-resource.
- **G3 — Drive it via a typed client** — the slice's own generated interface
  (`client.place(request) → Promise<Response>`), not raw HTTP or method-name-string dispatch.
- **G4 — Assert on side-effects** — emitted domain events ("facts"), published pub-sub topics, sent
  notifications, and DB rows / applied migrations.
- **G5 — Replace the ad-hoc doubles** (`Stub*` in `aether-invoke`, `InMemory*` in examples) with one
  shared, maintained fake catalog.

### 1.3 Non-goals

- Not a new runtime. No consensus, membership, DHT, routing, or deploy FSM.
- Not a replacement for E2E cluster tests (`aether/e2e-tests`, `forge-tests`) — those still cover
  topology, placement, failover.
- Not multi-slice orchestration in the MVP (one slice-under-test; its *slice* dependencies are
  scripted, see §6).

### 1.4 Acceptance (from the issue)

> A slice with a `@PgSql` store plus an `@Http` dependency is testable end-to-end in a plain unit
> test with no forge archive.

This spec meets it by faking the `@Http` dependency (scripted `HttpClient`) and backing `@PgSql`
with a testcontainer Postgres (real SQL + real migrations) — or, when the test only needs logic, an
in-memory `SqlConnector` fake.

---

## 2. Background — how a slice is instantiated today (the seam)

The kit is deliberately *not* new machinery; it plugs into three existing, small abstractions.

### 2.1 The generated factory

For every `@Slice` interface, the slice-processor generates `{Interface}Factory` with two static
entry points (`jbct/slice-processor/src/main/java/.../generator/FactoryClassGenerator.java:42-53`,
naming at `:73-91`). The concrete shape, from a real generated file
(`examples/url-shortener-v2/target/generated-sources/annotations/.../shortener/UrlShortenerFactory.java`):

```java
// Typed entry point — returns the slice's own interface (the "typed client"):
public static Promise<UrlShortener> urlShortener(Aspect<UrlShortener> aspect,        // :32
                                                 SliceCreationContext ctx) {
    return Promise.all(
        ctx.resources().provide(PgSqlConnector.class, "database").map(UrlPersistenceFactory::urlPersistence),
        ctx.resources().provide(Publisher.class, "click-events", ProvisioningContext.provisioningContext())
    ).map((persistence, clickPublisher) ->
        aspect.apply(UrlShortener.urlShortener(persistence, clickPublisher)));   // :38-40
}

// Runtime entry point — wraps the impl in a Slice (methods(), codec(), stop()):
public static Promise<Slice> urlShortenerSlice(Aspect<UrlShortener> aspect, SliceCreationContext ctx) { … } // :43
```

Key facts:

- The method name is `{camelCaseInterfaceName}` / `{camelCaseInterfaceName}Slice` — derived from the
  interface, **stable and referenceable as a method handle** (e.g. `UrlShortenerFactory::urlShortener`).
- Every resource dependency is resolved by `ctx.resources().provide(ResourceType.class, "section"[, ctx])`
  (`UrlShortenerFactory.java:35-36`). **This call is the entire injection seam.**
- Interceptors declared on the slice compose into `aspect` (`FactoryClassGenerator.java:51-53`).
- The typed entry point returns the impl **directly** — no serialization, no method-name dispatch.
  Serialization/`SliceCodec` only exists on the `…Slice` path for the remote/cluster transport
  (`UrlShortenerFactory.java:70-120`), which the kit does not exercise.

### 2.2 `SliceCreationContext` + `ResourceProviderFacade`

`SliceCreationContext` is a 3-method interface with public constructors
(`aether/slice-api/src/main/java/.../SliceCreationContext.java:15-52`):

```java
interface SliceCreationContext {
    SliceInvokerFacade invoker();       // for slice→slice dependency proxies
    ResourceProviderFacade resources(); // for @Resource dependencies  ← the seam
    ConfigFacade config();
    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker, ResourceProviderFacade resources) { … }
}
```

`ResourceProviderFacade` is a **two-method interface** (`.../ResourceProviderFacade.java:11-18`):

```java
interface ResourceProviderFacade {
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
    <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);
    default Promise<Unit> releaseAll(String sliceId) { return Promise.unitPromise(); }
}
```

The production implementation, `SpiResourceProvider`, discovers `ResourceFactory` SPIs via
`ServiceLoader.load(ResourceFactory.class)` and resolves config from the global `ConfigService`
(`aether/resource/api/src/main/java/.../SpiResourceProvider.java:45`, `:83-98`, `:62-66`). **The kit
supplies its own `ResourceProviderFacade` instead** — a map of `(resourceType, section) → instance`
— so it never touches ServiceLoader or `ConfigService`.

### 2.3 `Aspect`

`Aspect<T>` is a functional wrapper with a ready-made identity
(`aether/slice-api/src/main/java/.../Aspect.java:8-13`): `Aspect.identity()` returns `t -> t`. The
MVP defaults the interceptor stack to identity; a test may compose real interceptors later (§6).

### 2.4 Consequence

To spin a slice the kit only needs:
`factoryMethod.apply(Aspect.identity(), sliceCreationContext(fakeInvoker, fakeResources)).await(...)`.
Everything else — fakes, capture, containers — hangs off the `fakeResources` map. **No code in
`aether/node`, `aether/ember`, or the consensus stack is touched.**

---

## 3. Public API surface

All types live in a new module `aether/slice-testkit` (§7), package
`org.pragmatica.aether.testkit`. The API is a fluent builder producing a `SliceUnderTest<T>` handle.

### 3.1 Entry point & builder

```java
public final class SliceTestKit {
    /// Start a kit for the slice built by the given generated typed factory method.
    /// `factory` is a method reference, e.g. `OrderIntakeFactory::orderIntake`.
    static <T> SliceTestKit<T> forSlice(Fn2<Aspect<T>, SliceCreationContext, Promise<T>> factory);

    /// Register a fake or container-backed resource for one (type, section) coordinate.
    <R> SliceTestKit<T> withResource(Class<R> resourceType, String configSection, R instance);

    /// Convenience overloads that also register capture handles (see §3.3):
    <E> SliceTestKit<T> withPublisher(String topic, CapturingPublisher<E> publisher);
    SliceTestKit<T> withNotifications(CapturingNotificationSender sender);
    SliceTestKit<T> withHttp(String section, FakeHttpClient client);

    /// Provision a resource via a testcontainer instead of a fake (see §5.2).
    SliceTestKit<T> withContainer(Class<?> resourceType, String configSection, ContainerResource<?> spec);

    /// Override the interceptor stack (default = Aspect.identity()).
    SliceTestKit<T> withAspect(Aspect<T> aspect);

    /// Scripted responses for a slice→slice dependency (deferred; see §6).
    <S> SliceTestKit<T> withSliceDependency(String artifact, S stubImpl);

    /// Build & provision. Awaits factory resolution with a bounded timeout; fails the test on
    /// unresolved resources (missing map entry) with a precise "no fake/container for X:section".
    SliceUnderTest<T> build();
}
```

### 3.2 The slice-under-test handle

```java
public interface SliceUnderTest<T> extends AutoCloseable {
    T client();                          // G3 — the slice's own typed interface
    DbAssertions db(String section);     // real-container query helper (§5.2)
    <E> List<E> published(String topic); // G4 — captured pub-sub events ("facts")
    List<Notification> notifications();   // G4 — captured @Notify sends
    List<HttpCall> httpCalls(String section);
    void close();                        // releases fakes + stops any containers
}
```

### 3.3 Fake catalog (generalized from existing doubles)

Each fake is a hand-written implementation of the *resource interface* (not a mock framework),
capturing inputs for assertions. They generalize the ad-hoc doubles already in the tree
(`InMemoryUrlPersistence.java:14-38`, `Stub{Serializer,ClusterNetwork,DeploymentManager}` in
`aether-invoke`):

| Fake | Implements | Captures / behaviour |
|------|-----------|----------------------|
| `CapturingPublisher<E>` | `Publisher<E>` (`.../slice-api/.../Publisher.java:12`) | appends each `publish(e)` to a list → `published()` |
| `CapturingNotificationSender` | `NotificationSender` | records each `send(n)`; returns scripted `NotificationResult` |
| `FakeHttpClient` | `HttpClient` (`.../resource/api/.../http/HttpClient.java`) | scripted `onGet/onPost(path → HttpResult)`; records calls |
| `InMemorySqlConnector` | `SqlConnector` (`.../resource/api/.../db/SqlConnector.java`) | scripted row sets per SQL pattern; records executed statements |
| `InMemoryPgSqlConnector` | `PgSqlConnector` | same, for `@PgSql` |
| `ScriptedInvoker` | `SliceInvokerFacade` | returns scripted `MethodHandle`s for slice deps (§6) |

> `[ASSUMPTION]` The DB fakes are **statement-recording + response-scripting**, not a SQL engine.
> Tests that need real SQL semantics (joins, constraints, migrations) use the container path (§5.2).
> This keeps the fake honest — it never *pretends* to run SQL. See §5.3 (Honest-guarantees note).

### 3.4 Full example test (acceptance scenario)

A slice with a `@PgSql` store, an `@Http` dependency, and a pub-sub `Publisher`:

```java
// --- Slice under test (author's code) ---
@Slice
public interface OrderIntake {
    record PlaceRequest(String sku, int qty) {}
    record PlaceResponse(long orderId, String status) {}
    record OrderPlaced(long orderId, String sku, int qty) {}

    Promise<PlaceResponse> place(PlaceRequest request);

    static OrderIntake orderIntake(OrderStore store,               // @PgSql-backed persistence facade
                                   @Http HttpClient inventory,      // external HTTP dependency
                                   @OrderEvents Publisher<OrderPlaced> events) {
        record orderIntake(OrderStore store, HttpClient inventory, Publisher<OrderPlaced> events)
                implements OrderIntake {
            @Override public Promise<PlaceResponse> place(PlaceRequest req) {
                return inventory.get("/stock/" + req.sku())
                    .flatMap(stock -> store.insertOrder(req.sku(), req.qty()))
                    .flatMap(orderId -> events.publish(new OrderPlaced(orderId, req.sku(), req.qty()))
                        .map(_ -> new PlaceResponse(orderId, "ACCEPTED")));
            }
        }
        return new orderIntake(store, inventory, events);
    }
}
```

```java
// --- The test, using the kit ---
class OrderIntakeSliceTest {
    @Test
    void place_persistsOrder_reservesStock_andEmitsFact() {
        var inventory = FakeHttpClient.scripted()
            .onGet("/stock/ABC", HttpResult.ok("{\"available\":5}"));
        var events = CapturingPublisher.<OrderIntake.OrderPlaced>capturing();

        try (var sut = SliceTestKit.forSlice(OrderIntakeFactory::orderIntake)
                // real Postgres for @PgSql — runs the slice's own schema/ migrations:
                .withContainer(PgSqlConnector.class, "database", Containers.postgres().withSchemaFrom("schema/"))
                // fake external HTTP:
                .withHttp("inventory", inventory)
                // capturing pub-sub publisher:
                .withPublisher("order-events", events)
                .build()) {

            OrderIntake client = sut.client();               // G3 — typed client

            var response = client.place(new OrderIntake.PlaceRequest("ABC", 2))
                                 .await(timeSpan(5).seconds())
                                 .unwrap();

            // Assert on the response ...
            assertThat(response.status()).isEqualTo("ACCEPTED");

            // ... on the emitted fact (G4) ...
            assertThat(events.published()).singleElement()
                .satisfies(e -> assertThat(e.sku()).isEqualTo("ABC"));

            // ... on the outbound HTTP call (G4) ...
            assertThat(inventory.calls()).anyMatch(c -> c.path().equals("/stock/ABC"));

            // ... and on real DB rows (G4) ...
            sut.db("database").query("SELECT sku, qty FROM orders")
               .assertRows(row -> row.string("sku").equals("ABC") && row.integer("qty") == 2);
        }
    }
}
```

The same test with a **fully in-memory** DB (no Docker) swaps one line:

```java
.withResource(PgSqlConnector.class, "database",
              InMemoryPgSqlConnector.scripted().onInsert("INSERT INTO orders%", 1L))
```
and asserts on `((InMemoryPgSqlConnector) …).statements()` instead of `sut.db(...)`.

---

## 4. Fakes vs Testcontainers

### 4.1 Per-resource selection

Selection is **per (resourceType, configSection)** at build time — a slice can fake HTTP while
containerizing Postgres, exactly the acceptance scenario. Both paths produce the same thing: an
object registered in the kit's `ResourceProviderFacade` map, returned from `provide(type, section)`.

| Path | How the instance is produced | When to use |
|------|------------------------------|-------------|
| **Fake** | Kit's hand-written in-memory impl of the resource interface | logic tests, fast CI, no Docker, capture-based assertions |
| **Testcontainer** | Kit starts a container, builds a config record, calls the **real** `ResourceFactory.provision(config)` | real SQL/migrations, driver-level behaviour, integration confidence |

### 4.2 The testcontainer path reuses real factories

For `@PgSql`/`@Sql`, the kit does **not** reinvent connection handling. It:

1. Starts a `PostgreSQLContainer` (same pattern as
   `integrations/db/postgres-async/src/test/java/.../DatabaseExtension.java:27-94`).
2. Builds a `DatabaseConnectorConfig` whose `asyncUrl` points at the mapped port.
3. Calls `new AsyncSqlConnectorFactory().provision(config)` directly
   (`aether/resource/db-async/src/main/java/.../AsyncSqlConnectorFactory.java:37-40`) — a real
   `SqlConnector`/`PgSqlConnector`. **No global `ConfigService`** is needed because `provision(config)`
   takes the config record directly (§2.2 sidestep).
4. Applies the slice artifact's `schema/` migrations to the container so `@PgSql` compile-validated
   queries run against the real schema (reusing the deployment schema manager; see §6 deferral note).
5. Registers the connector in the map and exposes `sut.db(section)` for row assertions.

The same shape generalizes to other containerized backends later (MySQL, SMTP sink for `@Notify`,
WireMock/httpd for `@Http`).

### 4.3 Honest-guarantees note

Per the repo's consistency-lens rule, the kit must **name what each path actually guarantees**:

- A **fake** `SqlConnector` guarantees *the slice called these statements and received these scripted
  rows* — it does **not** validate SQL, constraints, transactions, or migrations.
- A **container** `PgSqlConnector` guarantees *real PostgreSQL semantics for the queries the test
  exercises* — bounded by the migrations actually applied and the driver used.

The kit surfaces this in fake type names (`InMemory…`, `Fake…`, `Capturing…`) and documents it so a
green fake-path test is never mistaken for DB-level validation.

### 4.4 OPEN QUESTION — default resource strategy

**Should `withResource(...)` default to fakes (containers opt-in) or vice versa?**

- **Option A — fakes by default, containers opt-in (`.withContainer(...)`).** *Recommended.* Matches
  the repo's "fast in-JVM proof first, expensive path as final gate" sequencing rule; keeps the
  common logic test Docker-free; makes the container a deliberate, visible choice.
- **Option B — containers by default, fakes opt-in.** Higher fidelity out of the box, but every test
  needs Docker/Colima and pays container startup; contradicts the fast-first discipline.
- **Option C — no default; every resource must be explicitly `.withFake(...)` or `.withContainer(...)`.**
  Most explicit, zero surprise, but more verbose for the frequent all-fakes case.

**Recommendation: Option A.** Container usage is explicit and rare-by-default; fakes stay frictionless.

---

## 5. Reuse vs New

Aggressively reuse; the kit is thin glue over existing seams.

| Concern | Reuse (existing) | New (in `slice-testkit`) |
|---------|------------------|--------------------------|
| Slice instantiation | Generated `{Interface}Factory` typed method (`UrlShortenerFactory.java:32`) | method-ref adapter (`Fn2<Aspect,ctx,Promise<T>>`) |
| Creation context | `SliceCreationContext.sliceCreationContext(...)` (`SliceCreationContext.java:24`) | build it from kit fakes |
| Resource seam | `ResourceProviderFacade` interface (`ResourceProviderFacade.java:11`) | `MapResourceProvider` (map-backed impl) |
| Interceptors | `Aspect.identity()` (`Aspect.java:11`) | default wiring + optional real-aspect composition |
| DB container | `PostgreSQLContainer` pattern (`DatabaseExtension.java:27-94`) | `ContainerResource` lifecycle wrapper |
| Real DB connector | `AsyncSqlConnectorFactory.provision(config)` (`AsyncSqlConnectorFactory.java:37`) | config builder from container coordinates |
| DB migrations | deployment schema manager (`aether/aether-deployment` schema) | migration-runner shim for a single section |
| Fakes | generalize `InMemoryUrlPersistence` (`:14-38`) + `Stub*` (`aether-invoke/src/test`) | one maintained fake catalog (§3.3) |
| Invoker (slice deps) | `SliceInvokerFacade` (`SliceInvokerFacade.java:11`) | `ScriptedInvoker` (deferred, §6) |

**Explicitly NOT reused:** `Ember`/`EmberCluster`/`AetherNode`, consensus, SWIM, DHT, routing,
deploy FSM, `ForgeServer`. The kit's whole value is *not* paying for them.

**Migration of ad-hoc doubles (G5):** `Stub{Serializer,Deserializer,ClusterNetwork,DeploymentManager}`
and `StubRouteHandlerFactory` in `aether-invoke/src/test` and the `InMemory*Persistence` in examples
become either (a) instances of the kit's fake catalog or (b) obsolete once tests move to the kit. This
is a follow-up cleanup, tracked separately from the MVP so the kit lands first and adoption is
incremental.

---

## 6. Module placement & licensing

### 6.1 Module

New Maven module **`aether/slice-testkit`** (`org.pragmatica.aether:aether-slice-testkit`).

**Dependencies (compile scope):**
- `aether/slice-api` — `SliceCreationContext`, `ResourceProviderFacade`, `Aspect`, `Publisher`,
  `Slice`, `SliceInvokerFacade`, `ProvisioningContext`.
- `aether/resource/api` — `ResourceFactory`, `PgSqlConnector`, `SqlConnector`, `HttpClient`,
  `DatabaseConnectorConfig`.
- `aether/resource/notification` — `NotificationSender`.
- `core` — `Result`/`Option`/`Promise`/`Fn`.

**Optional / opt-in dependencies (for the container path only):**
- `aether/resource/db-async` — real `AsyncSqlConnectorFactory` (and `db-jdbc` if JDBC fallback wanted).
- `org.testcontainers:*` and `org.testcontainers:postgresql`.
- Deployment schema manager (`aether/aether-deployment`) for migration application.

**OPEN QUESTION — one module or two?**

- **Option A — single `aether/slice-testkit` with Testcontainers as an `optional`/`provided`
  dependency.** *Recommended.* Simplest to consume; the fakes-only user never triggers the container
  code path, and `optional=true` keeps Testcontainers off the transitive classpath unless the user
  adds it. Risk: an all-fakes user who calls `.withContainer(...)` without adding Testcontainers gets
  a `NoClassDefFoundError` — mitigated by a clear guard message.
- **Option B — split `aether/slice-testkit` (fakes, zero container deps) + `aether/slice-testkit-containers`
  (Testcontainers + real factories).** Cleaner dependency hygiene; the fakes module has a tiny,
  Docker-free footprint. More modules to maintain and version.

**Recommendation: Option A** for rc2 (one module, Testcontainers `optional`); revisit Option B if the
container surface grows (MySQL/SMTP/HTTP backends).

### 6.2 Dependency direction (invariant)

`slice-testkit` depends **inward** on runtime API modules; **nothing in the runtime depends on it.**
It is consumed only at `test` scope by slice projects (examples and downstream authors). It must
**never** appear in the compile/runtime classpath of `aether/node`, `aether/ember`, or any deployed
artifact. `[ASSUMPTION]` A build check (or a simple reverse-dependency lint) enforces this.

### 6.3 Licensing

Every module under `aether/**` carries the BSL-1.1 SPDX header (`docs/legal/bsl-header.txt`); the
API modules the kit builds on (`slice-api`, `resource/api`) are already BSL-1.1 (verified in
`ResourceFactory.java:1-4`, `Aspect.java:1-4`, `SliceCreationContext.java:1-4`).

**OPEN QUESTION — BSL-1.1 or Apache-2.0 for the kit?**

- **Option A — BSL-1.1 (match `aether/**`).** *Recommended.* Consistent with the tree; adds **no new
  encumbrance** because any slice author already compiles against BSL-1.1 `slice-api`/`resource/api`
  to write a slice at all. Test/dev use is not restricted by BSL's production-use clause.
- **Option B — Apache-2.0 (relax for a developer-facing tool).** Signals "freely usable tooling" to
  external slice authors. But it would be an Apache module physically under `aether/**` (an exception
  to the tree convention) and its BSL-1.1 dependencies still govern the APIs it exposes, so the
  practical freedom gained is limited.

**Recommendation: Option A (BSL-1.1)** — consistent, and the kit exposes BSL-1.1 API types regardless.

---

## 7. Scope — rc2 MVP vs later

### 7.1 rc2 MVP (minimal usable kit)

Ordered risk-first (the seam is the load-bearing, highest-leverage piece):

1. **Injection seam** — `MapResourceProvider` (map-backed `ResourceProviderFacade`) + kit-built
   `SliceCreationContext` + `Aspect.identity()` default + `forSlice(factoryMethodRef).build()`
   awaiting the typed `Promise<T>`. *(unlocks everything; ~small)*
2. **Typed client** — `SliceUnderTest.client()` returns the slice interface; call methods directly.
3. **Fake catalog v1** — `CapturingPublisher`, `CapturingNotificationSender`, `FakeHttpClient`,
   `InMemorySqlConnector`/`InMemoryPgSqlConnector` (statement-recording + scripted rows).
4. **Assertion helpers** — `published(topic)`, `notifications()`, `httpCalls(section)`, and
   statement/row inspection on the DB fakes.
5. **Testcontainer Postgres path** — `Containers.postgres().withSchemaFrom("schema/")`, real
   `AsyncSqlConnectorFactory`/`PgSqlConnectorFactory`, migration application, `sut.db(section)` row
   assertions. *(This is what makes the acceptance's "@PgSql end-to-end" real; kept in-scope because
   it mostly reuses `DatabaseExtension` + an existing real factory.)*
6. **Missing-resource diagnostics** — `build()` fails fast with "no fake/container registered for
   `PgSqlConnector`:`database`" listing every unresolved `provide(...)` coordinate.
7. **Docs** — `aether/docs/slice-developers/testing-slices.md` currently documents only the E2E
   *cluster* harness (`AetherCluster`, Testcontainers) — it does **not** cover per-slice unit testing.
   Add a Slice-Test-Kit section (or split the file) as part of the MVP.

**MVP acceptance:** the §3.4 example test passes in both variants (container Postgres + fake HTTP,
and all-fakes) with no forge archive and no cluster.

**OPEN QUESTION — is the testcontainer path in the rc2 MVP or deferred?**
- **Option A — include it (recommended):** the issue title and acceptance both name `@PgSql` +
  testcontainers; a fake-only MVP wouldn't validate real SQL/migrations, which is the point of `@PgSql`.
- **Option B — defer it:** ship fakes + typed client + capture assertions first (smallest safe
  increment), add containers in a fast follow. Lower rc2 risk, but only partially meets acceptance.

### 7.2 Deferred (post-MVP / rc3)

- **Real interceptor composition** — build the `Aspect` from real cache/retry/circuit-breaker/metrics
  factories (`aether/resource/interceptors`) so tests can assert caching/retry behaviour.
- **Slice→slice dependencies** — `ScriptedInvoker` returning scripted `MethodHandle`s for multi-slice
  wiring; true multi-slice-in-one-JVM composition.
- **Subscriber & Scheduled** — drive `@ResourceQualifier(Subscriber)` handlers by feeding events, and
  `Scheduled` methods via a virtual clock (deterministic firing without real timers).
- **Stream / durable-entity "facts"** — capture `aether-stream` appends and durable-entity events
  (`InMemoryDurableEntity` already exists at `aether/resource/durable-entity`) as first-class assertion
  surfaces (`streamAppends(stream)`).
- **Container matrix** — MySQL/Oracle/etc. for `@Sql`; SMTP sink (GreenMail) for `@Notify`;
  WireMock/httpd for real `@Http`.
- **Migration-runner extraction** — clean, reusable single-section migration application if step
  MVP-5 shows the deployment schema manager is awkward to call standalone.
- **Double-migration** — replace `Stub*`/`InMemory*` usages across the repo with the kit's catalog.

---

## 8. Open questions (consolidated)

1. **§4.4 Default resource strategy** — fakes-by-default (rec. Option A) vs containers-by-default vs
   explicit-per-resource.
2. **§6.1 Module shape** — single module with `optional` Testcontainers (rec.) vs split fakes /
   containers modules.
3. **§6.3 License** — BSL-1.1 to match `aether/**` (rec.) vs Apache-2.0 as developer tooling.
4. **§7.1 Testcontainer path in MVP** — include for real `@PgSql` acceptance (rec.) vs defer to rc3.
5. **Terminology — "facts".** The issue lists "emitted facts" *and* "pub-sub" separately. This spec
   treats a "fact" as an emitted domain event captured at its sink: pub-sub `Publisher.publish(...)`
   in the MVP, plus stream appends / durable-entity events when those capture surfaces land (§7.2).
   **Confirm** this interpretation, or point to a distinct first-class "fact" primitive if one is
   intended (none exists in the tree today — grep for `Fact` finds only `Factory`).
6. **Typed vs Slice entry point.** The kit drives the **typed** factory method (`…()`), bypassing
   `SliceCodec` serialization. Should there be an opt-in "through the codec" mode to exercise
   request/response serialization round-trips too? (rec.: defer; add a `.throughCodec()` toggle later.)
7. **Async assertion ergonomics.** Standardize on `Promise.await(TimeSpan)` + AssertJ (as in
   `UrlShortenerTest`), or add kit sugar (`sut.call(client -> client.place(req))` returning the
   unwrapped value with a default timeout)? (rec.: add thin sugar; keep raw `await` available.)

---

## 9. References

### Internal — the injection seam
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/SliceCreationContext.java:15-52` — creation context + public constructors.
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/ResourceProviderFacade.java:11-18` — the 2-method resource seam.
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/Aspect.java:8-13` — interceptor wrapper + `identity()`.
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/Publisher.java:12` — pub-sub publish interface (capture target).
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/ProvisioningContext.java:24-57` — provisioning context record.
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/SliceInvokerFacade.java:11-16` — slice→slice dependency seam.

### Internal — provisioning & generation
- `aether/resource/api/src/main/java/org/pragmatica/aether/resource/ResourceFactory.java:12-42` — SPI contract.
- `aether/resource/api/src/main/java/org/pragmatica/aether/resource/SpiResourceProvider.java:45,62-98` — production ServiceLoader/config resolver (what the kit replaces).
- `aether/resource/db-async/src/main/java/org/pragmatica/aether/resource/db/async/AsyncSqlConnectorFactory.java:15-40` — real DB factory reused by the container path; `provision(config)` needs no global ConfigService.
- `jbct/slice-processor/src/main/java/org/pragmatica/jbct/slice/generator/FactoryClassGenerator.java:42-53,73-91` — generated factory entry points + naming.
- `examples/url-shortener-v2/target/generated-sources/annotations/org/pragmatica/aether/example/urlshortener/shortener/UrlShortenerFactory.java:32-136` — a real generated factory (typed `…()` + `…Slice()` paths).
- `aether/resource/api/src/main/java/org/pragmatica/aether/resource/db/{PgSqlConnector,SqlConnector}.java`, `.../http/HttpClient.java`, `aether/resource/notification/.../NotificationSender.java` — resource interfaces the fakes implement.

### Internal — what the kit avoids, and doubles to generalize
- `aether/ember/src/main/java/org/pragmatica/aether/ember/EmberCluster.java:334-382,630-686` & `EmberInstance.java:35-43` — the heavy 5-node in-JVM path the kit replaces.
- `aether/aether-invoke/src/test/java/org/pragmatica/aether/invoke/Stub{Serializer,Deserializer,ClusterNetwork,DeploymentManager}.java`, `.../http/StubRouteHandlerFactory.java` — ad-hoc doubles to fold in (G5).
- `examples/url-shortener/src/test/java/.../shortener/{InMemoryUrlPersistence.java:14-38,UrlShortenerTest.java:19}` — the current impl-only test pattern being superseded.
- `integrations/db/postgres-async/src/test/java/org/pragmatica/postgres/DatabaseExtension.java:27-94` — `PostgreSQLContainer` lifecycle pattern the container path reuses.
- `aether/docs/slice-developers/resource-reference.md:9-17` — resource → (type, config section) mapping.
- `aether/docs/slice-developers/testing-slices.md` — currently E2E-cluster only; to be extended with the kit.

### External
- [Testcontainers for Java — PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/) — container backing for the real `@PgSql`/`@Sql` path.
- [JUnit 5 extension model](https://junit.org/junit5/docs/current/user-guide/#extensions) — lifecycle pattern already used by `DatabaseExtension`.

---

## 10. Assumptions

- **[A1]** The kit drives the generated **typed** factory method via a method reference
  (`Factory::name`); no reflection and no per-slice codegen are required.
- **[A2]** DB fakes record statements + script responses; they do not execute SQL. Real SQL/migrations
  come from the container path only (§4.3).
- **[A3]** `slice-testkit` is consumed at `test` scope only and never enters any runtime/deploy
  classpath (§6.2).
- **[A4]** The MVP interceptor stack is `Aspect.identity()`; real interceptor composition is deferred.
- **[A5]** "Facts" = emitted domain events captured at their sink (pub-sub in MVP), pending
  confirmation (§8.5).
- **[A6]** One slice-under-test per kit instance; slice-dependency scripting and multi-slice
  composition are deferred (§7.2).
