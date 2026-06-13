# Knowledge-Gathering Pipelines

**Status:** Landed (1.0.0-rc1)
**Scope:** `org.pragmatica.lang` — `Result`, `Option`, `Promise`
**API:** `mapWith` / `flatMapWith` / `ensureWith` (six combinators per carrier)
**API reference:** the `Result`/`Option`/`Promise` class headers (`jbct doc Result`).

---

## Overview

JBCT pipelines rest on two ideas. **Parse, don't validate**: input is validated once, at the
boundary, and wrapped into types whose mere existence proves validity. **Knowledge gathering**:
processing continues until the request has collected enough knowledge to answer the caller —
each step *adds* knowledge, no step discards it.

The first idea is covered elsewhere (Parse, Don't Validate). This document develops the second
to the same depth: how to design the data structures that flow between pipeline steps, and how
the context-preserving combinators added in Pragmatica Core 1.0.0-rc1 — `mapWith`, `flatMapWith`,
`ensureWith` — turn those structures into one-line pipeline stages.

## The Problem: What Flows Between Steps?

A use case rarely answers from its input alone. A typical flow validates the request, loads a
profile, checks an entitlement, fetches related data, and only then builds the response. The
question every pipeline design must answer: **what travels between the steps?**

The obvious answers all share one defect. A mutable context bag, passing only what each step
needs, a god-record of `Option` fields — each lets invalid pipeline states compile, because
nothing in the types says which knowledge is present at which point. Reordering or skipping a
step then fails at runtime instead of at compile time. (*Why not the obvious alternatives?*,
near the end, dissects each; what matters here is what they have in common.)

The requirement they miss: **at every point in the pipeline, the type should express exactly the
knowledge gathered so far** — no more, no less. This is "make invalid states unrepresentable"
applied to pipeline *progress*.

## The Data Structure: Knowledge-Accreting Stage Records

### First attempt: concrete chaining

The direct translation of "each stage = previous stage + new knowledge":

```java
record ValidRequest(UserId userId) {}
record WithProfile(ValidRequest request, Profile profile) {}
record WithArticles(WithProfile request, List<Article> articles) {}
```

This already delivers the core guarantee — holding a `WithArticles` proves the profile and
articles were gathered, in that order. But `WithArticles` is welded to one pipeline shape: it
can only ever follow `WithProfile`. A stage that gathers articles after a *different*
predecessor needs a new record.

### The refinement: the previous stage as a type parameter

```java
record Request(String userId) {}                              // raw input, anything, even null

record ValidRequest(UserId userId) {}                         // parsed, valid by construction
record UserProfile<T>(T request, Profile profile) {}          // whatever was known before + a profile
record UserArticles<T>(T request, List<Article> articles) {}  // whatever was known before + articles
```

Two things change, both significant.

First, **stage records become pipeline-agnostic**. `UserProfile<T>` says "whatever was known
before, plus a profile" — it composes after any predecessor, so the same stage record serves
every flow that gathers a profile.

Second, **the composed type is the progress proof**. A value of type

```java
UserArticles<UserProfile<ValidRequest>>
```

is a machine-checked statement: the request was validated, then a profile was gathered, then
articles. Reordering stages, skipping one, or consuming knowledge before it exists is a compile
error, not a runtime surprise. The compiler tracks pipeline progress for you.

### Conventions

- **Component name `request`** for the previous-stage reference, uniformly. Access chains then
  read mechanically: `a.request().request().userId()`.
- **The canonical constructor is the stage factory.** Its shape — `(T previous, Knowledge
  gathered)` — is not an accident; it is the exact shape the pipeline combinators expect (next
  section). Keep it.
- **A validating stage adds a static factory** returning `Result`, following the value-object
  convention (`QuotaScoped.quotaScoped(...)` below).

### Reaching back: access patterns

For shallow pipelines (the common case — three to five stages), `request()` chains are fine and
self-documenting.

When an *operation* needs several pieces of accumulated knowledge and should remain reusable
across pipelines, bound the container with capability interfaces instead of naming a concrete
stage type:

```java
interface HasUserId { UserId userId(); }

record UserProfile<T extends HasUserId>(T request, Profile profile) implements HasUserId {
    @Override
    public UserId userId() { return request.userId(); }
}
```

The forwarding implementation makes the fact reachable in one call at *any* depth, and an
operation declared as `<T extends HasUserId> Promise<Enriched<T>> enrich(T container)` composes
into any pipeline that has gathered a user id — without knowing anything else about it.

## Fitting the Pipeline: the mapWith Family

### The stage shape, and what it used to cost

Every knowledge-gathering stage has the same three-part shape: take some of the accumulated
knowledge, run an operation on it, combine the operation's output with the *original* container
into the next stage. Before 1.0.0-rc1, that shape forced a nested lambda with a captured
binding:

```java
// ❌ before: nesting + capture, just to keep the container alive
validRequest.flatMap(valid -> profiles.fetch(valid.userId())
                                      .map(profile -> new UserProfile<>(valid, profile)));

// ✅ after: source accessor + operation + next-stage constructor
validRequest.mapWith(ValidRequest::userId, profiles::fetch, UserProfile::new);
```

### The six combinators

Each of `Result`, `Option`, and `Promise` provides the same six methods:

```java
// whole-object forms — operation sees the full container
mapWith(operation, factory)       // op: T -> M<B>,  factory: (T, B) -> U   (pure)
flatMapWith(operation, factory)   // op: T -> M<B>,  factory: (T, B) -> M<U> (may fail)
ensureWith(operation)             // op: T -> M<B>;  B discarded, T continues

// field-scoped forms — getter projects, operation sees only the projection
mapWith(getter, operation, factory)
flatMapWith(getter, operation, factory)
ensureWith(getter, operation)
```

| Combinator | Operation result | Factory | On operation failure/empty |
|---|---|---|---|
| `mapWith` | passed to factory together with the **original** container | pure | propagates; factory not invoked |
| `flatMapWith` | same | may fail; its failure propagates | propagates; factory not invoked |
| `ensureWith` | **discarded** — chain continues with the original container | — | propagates |

The semantics carry across the three monads mechanically: on `Option`, "failure" means empty and
propagates as empty; on `Promise`, the chain genuinely awaits the operation before proceeding.

### Why the factory slot fits the data structure

Look at the factory's shape: `(T, B) -> U`. That is *exactly* the canonical constructor of a
knowledge-accreting stage record — `(previous container, new knowledge) -> next stage`. The data
structure and the combinator family are two halves of one design: a well-shaped stage is a
single line of method references, with no lambda bodies at all:

```java
.mapWith(ValidRequest::userId, profiles::fetch, UserProfile::new)
//        ^ which knowledge      ^ what to do     ^ what the next stage looks like
```

### A complete pipeline

```java
Promise<Response> handle(Request raw) {
    return ValidRequest.validRequest(raw)                                  // Result<ValidRequest>
        .async()                                                           // → Promise<ValidRequest>
        .mapWith(ValidRequest::userId, profiles::fetch, UserProfile::new)  // Promise<UserProfile<ValidRequest>>
        .ensureWith(p -> p.request().userId(), rateLimiter::check)         // transient gate; container unchanged
        .mapWith(p -> p.request().userId(), articles::byAuthor, UserArticles::new)
                                              // Promise<UserArticles<UserProfile<ValidRequest>>>
        .map(Response::from);                                              // knowledge → answer
}
```

Note where the coupling lives. `profiles.fetch(UserId)` and `articles.byAuthor(UserId)` are
written against their narrow natural inputs and know nothing about containers or pipelines; the
projection happens in the getter slot, one expression per stage, at the wiring site. The same
operation composes into any pipeline whose container can produce a `UserId`.

### Gating stages: ensureWith

Some stages have a success/failure outcome that must gate the pipeline but produce nothing a
later stage reads. A rate-limit check, a fire-and-forget audit write, a notification: the outcome
*is* knowledge — "the request may proceed" — but it is **transient**, consumed by the act of
continuing rather than carried forward. No later step will ask for proof, so there is nothing to
accrete. That is `ensureWith`:

```java
.ensureWith(p -> p.request().userId(), rateLimiter::check)
```

The operation must succeed (failure fails the chain), its result is discarded, and the container
flows through untouched. Contrast with `onSuccess`: a `Consumer` that cannot fail and — on
`Promise` — does not gate the chain. `ensureWith` is the *fallible, gating* counterpart that was
previously missing; before it, these stages either lost the container or ended in the
`.map(ignored -> original)` shuffle.

`ensureWith` is where parse-don't-validate runs out: the one place a fallible check legitimately
yields no type, precisely because nothing downstream will ask for proof. The moment a later step
*does* need the fact, that is the signal the operation should return evidence rather than
pass/fail. An audit write whose confirmation is consumed downstream is then a `mapWith`, not an
`ensureWith`:

```java
.mapWith(p -> p.request().userId(), audit::write, Audited::new)   // record Audited<T>(T request, AuditId id)
```

The same audit operation lands in two homes, decided by whether anyone reads the id. A
load-bearing outcome always carries a value to accrete; a pass/fail with no value is, by that
token, transient — **a load-bearing check that returns only a boolean is itself the
parse-don't-validate anti-pattern.**

### Validating stage constructors: flatMapWith

Parse-don't-validate applies at stage boundaries too. When entering the next stage is itself
conditional on the gathered knowledge, give the stage record a validating factory and use
`flatMapWith`:

```java
record QuotaScoped<T>(T request, Quota quota) {
    static <T> Result<QuotaScoped<T>> quotaScoped(T request, Quota quota) {
        return Verify.ensure(quota, q -> q.remaining() > 0, QUOTA_EXHAUSTED)
                     .map(q -> new QuotaScoped<>(request, q));
    }
}

validRequest.flatMapWith(ValidRequest::userId, quotas::lookup, QuotaScoped::quotaScoped);
```

Operation failure and constructor rejection both propagate as the chain's failure. The result is
a pipeline in which *holding a `QuotaScoped<...>` proves quota was available* — the
progress-proof property extends to business conditions, not just data presence.

### Stages that need several facts: the whole-object forms

When one projection is not enough, drop the getter and let the operation reach through the
container:

```java
.mapWith(p -> recommender.suggest(p.request().userId(), p.profile().interests()),
         Recommended::new)
```

For cross-pipeline reuse of such operations, prefer capability bounds (`HasUserId`,
`HasProfile`) over concrete container types, as shown earlier.

### What mapWith is NOT for

**Multi-projection decomposition stays with `all(...)`.** There are deliberately no
`mapWith2(getter1, getter2, ...)` arities. When a stage decomposes one value into several
fallible projections, that is the instance `all(...)` → `MapperN` job.

**Independent parallel operations stay with Fork-Join.** `mapWith` runs *one* operation. When a
stage gathers several independent pieces concurrently, fork-join them and accrete the results in
one step — the identity projection keeps the container in play:

```java
.all(Promise::success,                              // keep the container itself
     v -> profiles.fetch(v.userId()),               // independent fetch 1
     v -> preferences.fetch(v.userId()))            // independent fetch 2
.map(Enriched::new)                                 // (ValidRequest, Profile, Prefs) -> Enriched<...>
```

with `record Enriched<T>(T request, Profile profile, Prefs prefs)` — the canonical-constructor
convention extends naturally to stages that gather more than one fact at once.

### Design notes

- **The operation is always effectful** (returns the carrier). A pure operation needs no
  combinator: `map(t -> factory(t, f(t.field())))` already covers it.
- **Purity lives in the method name, not in overloads.** `Fn1<B, A>` and `Fn1<Result<B>, A>`
  erase identically, so same-name pure/effectful overloads would be ambiguous for
  implicitly-typed lambdas. Hence `mapWith` (pure factory) vs `flatMapWith` (fallible factory);
  only arity (whole-object vs field-scoped) overloads safely.
- **Equivalences (the combinators are sugar over the monadic core).**
  `mapWith(op, factory) ≡ flatMap(t -> op.apply(t).map(b -> factory.apply(t, b)))`;
  `flatMapWith(op, factory) ≡ flatMap(t -> op.apply(t).flatMap(b -> factory.apply(t, b)))`;
  `ensureWith(op) ≡ flatMap(t -> op.apply(t).map(_ -> t))`. The field-scoped forms compose the
  getter into the operation (`t -> op.apply(getter.apply(t))`). The 3-arg `mapWith` is also
  expressible as `all(M::success, t -> op.apply(t.field())).map(factory)` — the family adds no
  new semantics, only the lambda-free shape.

## Why not the obvious alternatives?

Three designs reach for the space between steps before the accreting record does. Each fails the
*express exactly the knowledge so far* requirement in its own way:

| Design | What it is | Why it fails |
|---|---|---|
| **Mutable context bag** | a `ProcessingContext` (or `Map<String, Object>`) whose fields steps populate as they go | the type is blind to progress — reordering steps compiles and fails at runtime; mutability poisons Fork-Join thread safety; every reader must reconstruct the temporal protocol by hand |
| **Pass only what's needed** | each step takes exactly its input, returns exactly its output | clean in isolation, but earlier knowledge is gone — a late step needing an early value must re-fetch it, thread it through every intermediate signature, or smuggle it in a field |
| **God-record of options** | one record with `Option<Profile>`, `Option<List<Article>>`, … | invalid states are representable again — a consumer cannot tell "not gathered yet" from "none exists"; the context bag's temporal coupling, with extra ceremony |

The accreting stage record removes the defect at its root: the *type itself* advances with the
pipeline, so "knowledge not yet gathered" is not a value a later step can hold — it is a state
that does not compile.

## Boundaries and Trade-offs

**Type-parameter growth.** Five stages of accretion produce
`E<D<C<B<A>>>>`-shaped types. Within a single use case this is rarely written out — local
inference (`var`) and the final `.map(Response::from)` absorb it. When a composed type *does*
need naming (a step interface boundary, a sub-pipeline seam), that is the signal to **flatten
into a named milestone record**:

```java
record EnrichedOrder(ValidRequest request, Profile profile, List<Article> articles) {}
```

Rule of thumb: accrete generically *within* a use case; flatten to a named record at boundaries
other code must speak about.

**Deep `request()` chains.** Two hops read fine; three or more is the same flattening signal —
or a capability interface if the depth comes from operation reuse rather than pipeline length.

**When not to use knowledge gathering at all.** A two-step pipeline where each step needs only
the previous step's output is a plain Sequencer — `flatMap` is the right tool, and a stage
record would be ceremony. Reach for accretion when a later step needs *earlier* knowledge; that
is the defining trigger.

**Cost.** Stage records are shallow wrappers holding references — no data is copied, and the
per-stage allocation is one small object. The structure is also immutable by construction, so
accreted containers cross Fork-Join boundaries safely.

**Reordering.** Changing stage order changes the composed type and breaks exactly the
expressions that depended on the old order. This is the design working as intended: the
compiler, not the test suite, reports pipeline-shape mistakes.

## Testing

Stage records with canonical constructors need no dedicated tests — there is nothing to get
wrong. Validating stage factories are tested like value objects: success plus one test per
rejection rule. Pipelines are tested per the evolutionary strategy: stub the step operations,
assert the happy path, then one test per failing step — including the `ensureWith` stages, whose
failure must fail the chain, and whose success must leave the container untouched (assert the
same instance flows through).

## Summary

- Each pipeline stage's container = **previous container as a type parameter + new knowledge**;
  the composed type is a compiler-checked proof of pipeline progress.
- The canonical constructor `(T, Knowledge)` of a stage record is exactly the factory shape
  `mapWith`/`flatMapWith` expect — a well-shaped stage is one lambda-free line: getter,
  operation, constructor.
- Operations take their **narrow natural input**; the getter slot couples them to the pipeline
  at the wiring site only.
- `ensureWith` = transient gates whose outcome no later step reads (gate, discard, pass through);
  `flatMapWith` = validating stage constructors; whole-object forms + capability bounds = stages
  needing several facts. A load-bearing outcome returns evidence and is accreted, not gated away.
- Multi-projection decomposition belongs to `all(...)`; independent parallel gathering belongs
  to Fork-Join with an accreting combine step.
- Accrete within a use case; flatten to named milestone records at boundaries; a deep
  `request()` chain is the flattening signal.
- The family is sugar over the existing monadic core (see *Equivalences*); it adds shape, not
  semantics. The authoritative per-method reference is the `Result`/`Option`/`Promise` class
  headers.
