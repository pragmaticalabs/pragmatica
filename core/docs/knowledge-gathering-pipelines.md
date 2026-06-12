# Knowledge-Gathering Pipelines

**Status:** Landed (1.0.0-rc1)
**Scope:** `org.pragmatica.lang` — `Result`, `Option`, `Promise`
**API:** `mapWith` / `flatMapWith` / `ensureWith` (six combinators per carrier)

---

## 1. The pipeline model

JBCT pipelines are built around two core ideas:

- **Parse, don't validate.** Input data is validated once, at the boundary, and wrapped into
  value objects and use-case-specific containers. The first pipeline stage receives already-valid
  data; no later stage re-checks it.
- **Knowledge gathering.** Processing continues until the request has collected enough knowledge
  to answer the caller. Each stage *adds* knowledge; none discards it.

Together these give a simple, type-safe model for pipeline data containers:

- the initial container holds the validated input;
- each subsequent container holds **newly gathered knowledge plus a reference to the previous
  stage's container**.

Making the previous container a type parameter keeps stages freely composable across different
pipelines while retaining full type safety:

```java
record Request(String userId) {}                              // raw input, anything, even null

record ValidRequest(UserId userId) {}                         // parsed, valid by construction
record UserProfile<T>(T request, Profile profile) {}          // previous stage + profile
record UserArticles<T>(T request, List<Article> articles) {}  // previous stage + articles
```

Knowledge gathered earlier is always reachable through the `request()` chain. The container types
accrete: `UserArticles<UserProfile<ValidRequest>>` says precisely which knowledge exists at this
point — the compiler tracks pipeline progress for you.

## 2. The combinator family

Each stage needs the same three-step shape: run an effectful operation (often on just one piece of
the accumulated knowledge), then combine the **original container** with the operation's result
into the next stage's container — or fail. Hand-written, that is a nested `flatMap` with a captured
binding. The `mapWith` family flattens it; each carrier (`Result`, `Option`, `Promise`) provides:

```java
// whole-object forms: operation sees the full container
<B, U> Result<U> mapWith    (operation: T -> Result<B>, factory: (T, B) -> U)
<B, U> Result<U> flatMapWith(operation: T -> Result<B>, factory: (T, B) -> Result<U>)
<B>    Result<T> ensureWith (operation: T -> Result<B>)

// field-scoped forms: getter projects, operation sees only the projection
<A, B, U> Result<U> mapWith    (getter: T -> A, operation: A -> Result<B>, factory: (T, B) -> U)
<A, B, U> Result<U> flatMapWith(getter: T -> A, operation: A -> Result<B>, factory: (T, B) -> Result<U>)
<A, B>    Result<T> ensureWith (getter: T -> A, operation: A -> Result<B>)
```

Semantics:

| Combinator | Operation result | Factory | On operation failure |
|---|---|---|---|
| `mapWith` | passed to factory together with the **original** value | pure | propagates; factory not invoked |
| `flatMapWith` | same | may fail; its failure propagates | propagates; factory not invoked |
| `ensureWith` | **discarded** — chain continues with the original value | — | propagates |

`ensureWith` is a *gating* side effect: unlike `onSuccess` (a `Consumer` that cannot fail and, on
`Promise`, does not gate the chain), `ensureWith` waits for the operation and propagates its
failure. Use it for entitlement checks, audit writes, notifications — stages that must succeed but
produce no knowledge.

## 3. A pipeline, end to end

```java
Request.parse(raw)                                                      // Result<ValidRequest>
       .mapWith(ValidRequest::userId, profiles::fetch, UserProfile::new) // Result<UserProfile<ValidRequest>>
       .ensureWith(p -> entitlements.check(p.request().userId()))        // gate; container unchanged
       .mapWith(UserProfile::profile, articles::byAuthor, UserArticles::new)
       .map(Response::from);
```

Note what each slot is: the **getter** is a record accessor reference, the **operation** is a
service method reference written against its natural narrow input, the **factory** is the next
stage record's canonical constructor. A well-shaped pipeline stage contains no lambda bodies at
all. The same chain shape works on `Promise` for asynchronous stages.

## 4. Designing operations for reuse

Two questions come up immediately when applying the pattern.

**"My operation needs only a `UserId`, but the previous stage produces a rich container — how do I
avoid coupling?"** Write the operation against its narrow natural input (`Profile fetch(UserId)`
knows nothing about any pipeline). The projection lives at the *wiring site*, in the getter
argument of the field-scoped form. Coupling between pipeline shape and operation is confined to
one method reference per stage, so the same operation composes into any pipeline whose container
can produce a `UserId`.

**"If an operation consumes only a subset of the knowledge, is the rest lost?"** No — structurally
impossible: the factory always receives the full previous container. `ensureWith` covers the
consume-without-producing case. Knowledge is only ever dropped deliberately, by a `map` to a
response type at the end.

For operations that need *several* pieces of accumulated knowledge, use the whole-object form and
reach through the `request()` chain — or, when one operation must work across different pipelines,
bound the container type with capability interfaces:

```java
interface HasUserId { UserId userId(); }

<T extends HasUserId> Result<Enriched<T>> enrich(T container) { ... }
```

## 5. Design notes (why the API is shaped this way)

- **Purity lives in the name, not in overloads.** `Fn1<B, A>` and `Fn1<Result<B>, A>` erase
  identically, so pure-vs-effectful variants of the same parameter cannot be same-name overloads —
  implicitly-typed lambdas would be ambiguous. Hence `mapWith` (pure factory) vs `flatMapWith`
  (fallible factory), with the operation always effectful in both: a *pure* operation needs no
  combinator (`map(t -> factory(t, f(t.field())))` already covers it). Arity overloads
  (whole-object vs field-scoped) are safe.
- **One field only.** There is deliberately no `mapWith2(getter1, getter2, ...)`: multi-projection
  decomposition is `all(...)`'s job. `mapWith` exists for the one dominant shape — one effectful
  operation, original kept.
- **Equivalences.** `mapWith(op, factory)` ≡ `flatMap(t -> op.apply(t).map(b -> factory.apply(t, b)))`;
  the field forms compose the getter into the operation. The 3-arg form is also expressible as
  `all(Result::success, t -> op.apply(t.field())).map(factory)` — the combinators are sugar; the
  semantics are the existing monadic core's.
- **Factories are stage constructors.** The factory slot's `(T, B) -> U` shape is exactly the
  canonical constructor of a knowledge-accreting record — the API and the container pattern are
  two halves of one idiom. A fallible factory (`flatMapWith`) is a parse-don't-validate stage
  constructor.
