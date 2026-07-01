# One descriptor, many boundaries: unify VO↔boundary mapping (DB + HTTP + facts)

**Issue:** [#397](https://github.com/pragmaticalabs/pragmatica/issues/397) — "One descriptor, many boundaries: unify VO↔boundary mapping (DB + HTTP + facts) — name TBD"
**Milestone:** v1.0.0-rc2 · **Labels:** enhancement, rc2
**Related:** #388 (VO↔column / `PgRepr`, shipped), #385 (compile-time error→HTTP mapping), #396 (first-class typed topics)
**Status:** DESIGN — for user approval. This document does **not** implement anything.

---

## 0. TL;DR

A value object (VO) today declares *how it maps to a raw representation* three separate times:

1. **DB** — via `PgRepr<T,P>` (`lower`/`lift`), discovered by the `static pgRepr()` convention (#388, shipped).
2. **HTTP** — not at all: path/query segments are bound as raw JDK primitives; a VO path parameter silently degrades to `aString()` and never re-parses into the VO.
3. **Facts** — via hand-written `TypeCodec` per VO, whose *readers throw* (`UUID.fromString`, `URI.create`) rather than surface a typed `Cause`.

This spec proposes **one neutral, author-facing descriptor** — working title `Mapping<T,P>` (**name is an open question reserved for the user, §3**) — that a VO declares once and that *every* boundary consumes: DB columns, HTTP path/query segments, and fact codecs. It generalizes `PgRepr` verbatim, keeps the descriptor **transport-neutral** (no HTTP/JDBC/wire type ever appears in VO code — a hard constraint the user has flagged), and preserves parse-don't-validate: a bad raw value fails with a typed `Cause`, never an exception or a silent default.

The critical design question this spec answers is **§5: the primitive `P` differs per boundary** (a `SeatId` is a `UUID` column but a `String` path segment). The resolution is a *P↔wire composition table* owned by the framework, so the VO still declares a single `Mapping<SeatId, UUID>`.

---

## 1. Problem & goal

### 1.1 Current state — three mechanisms, one concept

The concept "how does value object `T` relate to its raw representation `P`, reversibly and safely?" is expressed three incompatible ways.

**DB (single source, good shape).** `PgRepr<T,P>` is a pure pair of functions the VO already owns:

```java
// aether/slice-api/.../repr/PgRepr.java:34
public record PgRepr<T, P>(Fn1<P, T> lower, Fn1<Result<T>, P> lift) { ... }
```

- `lower : T → P` — total unwrap (`Fn1<P, T>` in Pragmatica's `Fn1<R, ARG>` order; can never fail).
- `lift  : P → Result<T>` — fallible re-parse (`Fn1<Result<T>, P>`; parse-don't-validate at the boundary).

Discovery is reflection-free: the VO exposes `public static PgRepr<Self, P> pgRepr()`, and `PgReprResolver` reads the *declared return type* at compile time to learn `P`, then codegen emits literal bind/decode calls (`PgReprResolver.java:50,74-93`). The typed decode failure is `RowDecodeError.RowDecode(column, cause)` (`RowDecodeError.java:18-32`), wrapped around each column decode by the generated row mapper (`FactoryGenerator.java:453-457`).

**HTTP (missing).** Path/query binding parses raw strings into JDK primitives only:

```java
// integrations/http-routing/.../PathParameter.java:23-28
public interface PathParameter<T> {
    Result<T> parse(String value);   // <-- this IS `lift` with P = String
}
```

`PathParameter.parse` is *structurally identical to `lift`* (String → `Result<T>`, typed `ParameterError` failure → 400). But the route generator only knows JDK types and **falls back to `aString()` for anything unrecognised** (`RouteSourceGenerator.java:1086-1093`), then feeds that raw `String` straight into the slice method's parameter record (`RouteSourceGenerator.java:724-757`). Consequence: to accept a `SeatId` in a path, a slice author must declare the record component as `String` (or `UUID`) and re-parse *inside the handler* — the VO's own `lift` is never invoked at the boundary, and the "parse failure → typed 400" guarantee is lost.

**Facts (hand-written, throwing).** Each VO gets a bespoke `TypeCodec` whose reader is infallible-by-throwing:

```java
// aether/node/.../NodeCodecs.java:138-144
private static TypeCodec<Uuid> uuidCodec() {
    return new TypeCodec<>(Uuid.class, deterministicTag("...Uuid"),
        (codec, buf, val) -> writeString(buf, val.value().toString()),
        (codec, buf) -> new Uuid(java.util.UUID.fromString(readString(buf)))); // throws on corrupt input
}
```

These are decoupled from `PgRepr` (a VO can define one and not the other, or define them inconsistently) and violate the no-exceptions idiom on the read path.

### 1.2 Goal

- **One declaration per VO.** A VO owns exactly one descriptor expressing `T ↔ P`. Every boundary derives its binding from it. Single source of truth.
- **Transport-neutral.** The descriptor mentions only `T`, `P`, `Fn1`, `Result`, `Cause` — never `String`-for-path, `ByteBuf`-for-wire, `Row`-for-DB, or HTTP status. *(Hard constraint: no HTTP-specifics in VO code.)*
- **Parse-don't-validate everywhere.** A corrupt raw value fails with a typed `Cause` at the boundary — `RowDecodeError` (DB), a 400 `ParameterError` (HTTP), a codec error (facts) — never a thrown exception, never a silent default.
- **Opt-in, never inferred.** A descriptor exists only when the VO declares it. The framework never guesses a mapping from record shape. A *missing or ambiguous* descriptor where one is required is a **compile error**, not a runtime surprise (acceptance criterion from #397).
- **Non-goal (this ticket):** it does not change VO validation logic, does not introduce runtime reflection, and does not unify the *transport wire formats* themselves — only the VO↔primitive leg.

### 1.3 Acceptance criterion (#397)

> One per-VO descriptor binds a SQL column, lifts an HTTP path segment (parse failure → typed 400), and codecs a fact — all from one declaration; a missing/ambiguous descriptor is a compile error.

This spec proposes to satisfy DB + HTTP in the first cut and facts shortly after (see scope, §7). The "one declaration" and "compile error on missing/ambiguous" properties are designed in from the start.

---

## 2. The generic shape

### 2.1 Core type (single-primitive form)

`PgRepr` is already the right shape; the generalization strips the DB-specific name and docstring and moves it to a boundary-neutral home.

```java
package org.pragmatica.aether.slice.mapping;   // NAME is an open question — see §3

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;

/// Pure, reflection-free descriptor of how a value object T maps to and from a single primitive
/// representation P. Carries no DB/HTTP/wire dependency — only Fn1 and Result from Pragmatica Core —
/// so a VO can declare its boundary representation without importing persistence or transport.
public record Mapping<T, P>(Fn1<P, T> lower, Fn1<Result<T>, P> lift) {

    /// From the VO's total accessor (T -> P) and fallible factory (P -> Result<T>).
    public static <T, P> Mapping<T, P> of(Fn1<P, T> lower, Fn1<Result<T>, P> lift) {
        return new Mapping<>(lower, lift);
    }

    /// Accepted-risk hatch: decode can never fail. Use only when the raw value provably reconstructs
    /// a valid VO (trusted internal enum ordinal, etc.). Corruption is silently accepted — a visible,
    /// deliberate choice, identical to PgRepr.trusted (PgRepr.java:44).
    public static <T, P> Mapping<T, P> trusted(Fn1<P, T> lower, Fn1<T, P> infallibleLift) {
        return new Mapping<>(lower, infallibleLift.then(Result::success));
    }
}
```

This is byte-for-byte `PgRepr` with the name and package changed and the DB-only wording removed. Everything #388 proved (round-trip laws, `trusted` semantics, the `PgReprTest` cases at `PgReprTest.java:27-73`) carries over unchanged.

**Discovery convention.** Same reflection-free mechanism as `PgRepr`: a VO exposes a conventionally-named `public static Mapping<Self, P> mapping()`. Each boundary's code generator reads the declared return type to learn `P`; the method is never invoked at compile time. Because it is a single static method with a fixed signature, a VO can carry *at most one* mapping — two is a compile error by construction (`PgReprResolver.java:22-24` reasoning), so no ambiguity check is needed.

```java
record SeatId(UUID raw) {
    static Result<SeatId> seatId(UUID raw) { ... }     // fallible factory
    static Mapping<SeatId, UUID> mapping() {           // ONE declaration, every boundary
        return Mapping.of(SeatId::raw, SeatId::seatId);
    }
}
```

### 2.2 Composite / multi-column form (multiple primitives ↔ one VO)

Single-primitive covers the wrapper 90% (IDs, enums, `Percent`). Multi-field VOs — `Money(amountMinor, currency)`, `Email(localPart, domain)` — need a form that maps to **N** primitives. `PgRepr` explicitly deferred this to a "future `PgComposite`" (`PgRepr.java:28-30`); this spec proposes the shape but recommends deferring the *implementation* (§7).

Proposed composite descriptor — an ordered list of named primitive legs plus a single fallible assembler:

```java
/// One VO <-> N named primitive components. Ordered; component order is the canonical order used by
/// every boundary (column order, path-segment order, fact field order).
public record CompositeMapping<T>(List<Component<T, ?>> components, Fn1<Result<T>, List<Object>> lift) {
    /// A single primitive leg: its name, its primitive type token, and the total accessor T -> Pi.
    public record Component<T, P>(String name, Class<P> primitiveType, Fn1<P, T> lower) {}
}
```

- `lower` is per-component and total (as before).
- `lift` is a *single* fallible assembler over the tuple of decoded primitives — the one place a multi-field VO can reject a bad combination (e.g. currency/amount mismatch).
- Boundary mapping: DB → N columns; facts → N primitives in order; HTTP → N path/query segments **or** a compile error if the route binds it to a single opaque segment (a single `/{money}` segment cannot carry two primitives — see §4.2).

This mirrors machinery the codebase already has: the multi-column row mapper (`FactoryGenerator` `MapperColumn` list) and the record-component walk in `RouteSourceGenerator.buildMergedConstructorExpr` (`RouteSourceGenerator.java:968-1008`). A type-safe builder (`Mapping.composite().add(...).assembledBy(...)`) can hide the `List<Object>` from authors; the `List<Object>` above is the erased internal form, not the author-facing API.

**Recommendation:** specify the composite contract now (so the single-primitive API is forward-compatible), implement it later. The single-primitive `Mapping<T,P>` and `CompositeMapping<T>` should share a sealed supertype so a resolver can return "some mapping" uniformly:

```java
sealed interface BoundaryMapping permits Mapping, CompositeMapping {}
```

*(Naming of the supertype rides on §3.)*

---

## 3. THE NAME — **OPEN QUESTION (reserved for the user)**

The ticket forbids shipping `Repr`. The name is author-facing and public, so it is chosen deliberately. Below are candidates with honest trade-offs. **The final pick is the user's.**

The repo's *honest-guarantees / consistency-lens* culture matters here: a name must not overclaim a property the type does not have. The type is a **total unwrap + partial (fallible) re-parse** between a rich type and one raw type. That immediately disqualifies two popular candidates.

| Candidate | Verdict | Reasoning |
|-----------|---------|-----------|
| `Codec` | **Rejected — collides** | `@Codec`, `SliceCodec`, `TypeCodec` already exist in `org.pragmatica.serialization` (`Codec.java:26`, `SliceCodec.java:31`, `NodeCodecs.java:16`). Same word, adjacent concept (facts serialization *is* one of the three boundaries) — guaranteed confusion. |
| `Iso` | **Rejected — overclaims** | An isomorphism is a *total* bijection with an exact round-trip both ways. `lift` is fallible (`P → Result<T>`), so this is not an iso. Naming it `Iso` asserts totality the type does not provide — a consistency-lens violation. |
| `Lens` | **Rejected — miscategorises** | A lens is total `get`/`set` focusing a field *within a structure*. Our pair is a fallible conversion *between two types*, not a focus into a whole. FP-literate readers would be actively misled. |
| `Wire` | **Weak — transport-flavoured** | Connotes on-the-wire serialization; misleads for the DB-column and path-segment cases, and the whole point is transport-*neutrality*. |
| `Adapter` | **Weak — vague/overloaded** | GoF "adapter" = interface-shape wrapping; says nothing about bidirectionality or the fallible edge. Also clashes conceptually with the existing `http-routing-adapter` module. |
| `Mapping` | **Recommended (safe)** | Neutral, honest, approachable to app authors. "VO↔boundary mapping" is exactly the concept and claims nothing false. Mild downside: "mapping" is overloaded generally (ORM/`Map`), but never *wrongly* here. Convention reads well: `static Mapping<SeatId, UUID> mapping()`. |
| `Prism` | **Recommended (precise)** | The *technically correct optic*: a prism is total `build : A → S` + partial `match : S → Option/Result<A>` — precisely `lower` + `lift`. Pleases FP-literate reviewers and is honest. Downside: jargon; app authors writing VOs may not know optics. Reads as `static Prism<SeatId, UUID> prism()`. |

**Recommendation:** **`Mapping`** as the primary (honest, approachable, zero overclaim), with **`Prism`** as the precise-but-jargon alternative for a more FP-literate audience. Rule out `Codec` (collision), `Iso`/`Lens` (overclaim/miscategorise), `Wire`/`Adapter` (vague/transport-flavoured).

Whatever is chosen drives three follow-on names, which should be decided together:
- the **type** (`Mapping` / `Prism` / …),
- the **discovery convention method** (`mapping()` / `prism()` / …),
- the **composite variant** (`CompositeMapping` / `CompositePrism` / …) and sealed supertype.

This document uses `Mapping` / `mapping()` throughout as a placeholder; a global rename is mechanical once the user decides.

---

## 4. Per-boundary application

For each boundary: how the descriptor is **discovered**, how it is **applied** (bind + decode), and what the **typed failure** looks like. The common thread: the boundary generator resolves the VO's `mapping()`, uses `lower` to bind and `lift` to decode, and wraps decode failures in that boundary's typed `Cause`.

### 4.1 DB columns (already live via #388 — rename only)

- **Discovery:** `MappingResolver.resolve(TypeMirror)` finds `static Mapping<Self,P> mapping()`, reads `P` from the return type (today: `PgReprResolver.resolve`, `PgReprResolver.java:50`).
- **Bind:** `Vo.mapping().lower().apply(value)` — literal, total (`PgReprResolver.Binding.lowerExpr`, `PgReprResolver.java:33`).
- **Decode:** `RowDecodeError.guard("col", row.getX("col").flatMap(Vo.mapping().lift()))` (`FactoryGenerator.java:453-457`).
- **Typed failure:** `RowDecodeError.RowDecode(column, cause)` — sealed `Cause`, names the column, carries the underlying `lift` failure, surfaces on the `Result`/`Promise` the caller is already on (`RowDecodeError.java:18-32`).

Under this spec the DB boundary is *unchanged in behaviour*; only the type/convention name moves (§6). This is the already-proven boundary.

### 4.2 HTTP path & query segments (new binding)

**The gap.** `PathParameter<T>.parse(String) → Result<T>` (`PathParameter.java:23-28`) is exactly `lift` specialised to `P = String`, and its failure `ParameterError.InvalidParameter` (`ParameterError.java:16`) already maps to a 400. What's missing is that `RouteSourceGenerator` never *discovers* a VO's `mapping()` — it only recognises JDK types and falls back to `aString()` (`RouteSourceGenerator.java:1086-1093`).

**Discovery.** In `RouteSourceGenerator.segmentArg` (`RouteSourceGenerator.java:1021-1028`) and the query-param equivalent, before falling back to `aString()`, run `MappingResolver.resolve(componentType)`:
- **not a VO mapping** → existing JDK behaviour (unchanged output for `String`/`Integer`/… — byte-identical).
- **is a VO mapping** with primitive `P` → emit a **composed** path parser (below).

**Apply — the P↔String composition (see §5).** A path segment is always a `String`. A VO's mapping is `T ↔ P` where `P` may be `UUID`, `Long`, etc. The generator composes the framework-owned `String → P` parser (the existing `PathParameter.aUuid()/aLong()/…` family) with the VO's `lift`:

```java
// Generated for a `SeatId seat` path component whose mapping is Mapping<SeatId, UUID>:
PathParameter<SeatId> seatParam =
    raw -> PathParameter.aUuid().parse(raw)          // String -> Result<UUID>   (framework-owned P-leg)
                        .flatMap(SeatId.mapping().lift());  // UUID  -> Result<SeatId> (VO-owned)
```

Equivalently, a small helper keeps generated code terse:

```java
PathParameter.mapped(PathParameter.aUuid(), SeatId.mapping())   // proposed factory on PathParameter
```

The slice author's method can now take the **VO directly** in its parameter record; the generated constructor call feeds the *lifted* value, not a raw string. The `TYPE_TO_PATH_PARAMETER` table (`RouteSourceGenerator.java:73-121`) gains no VO entries — VO handling is resolver-driven, orthogonal to the JDK table.

**Typed failure.** A parse failure (bad String→P, *or* a `lift` rejection) is a `ParameterError`/VO `Cause` that the router already turns into a **typed 400** — same path the JDK parsers use today. No new failure plumbing.

**Transport-neutrality (hard constraint) is preserved.** The VO declares only `Mapping<SeatId, UUID>`. It never mentions `String`, `PathParameter`, or `400`. The `String↔UUID` leg is *framework-owned and keyed by `P`* (§5), so no HTTP concept leaks into VO code. This is the explicit invariant the user flagged.

**Composite on a single segment → compile error.** A `CompositeMapping<Money>` cannot bind to a single `/{money}` path segment (one string, two primitives). The generator must reject this with a precise diagnostic ("value object `Money` maps to 2 primitives and cannot bind to a single path segment; bind its components to separate segments/query params"), consistent with the "missing/ambiguous → compile error" criterion. Multi-segment composite binding is deferred (§7).

### 4.3 Facts codecs (descriptor-driven, later cut)

**The gap.** Hand-written `TypeCodec`s (`NodeCodecs.java:96-158`, `WorkerCodecs.java:82-90`) duplicate per-VO serialization and *throw* on corrupt reads (`UUID.fromString`, `URI.create`).

**Discovery + apply.** A descriptor-driven codec for a VO with `Mapping<T,P>`:
- **write:** `lower` the VO to `P`, then write `P` with the primitive codec `SliceCodec` already owns (`SliceCodec` has primitive tags for `String`, `Long`, `UUID`-as-string, etc., `SliceCodec.java:35-56`).
- **read:** read `P` with the primitive codec, then `lift` — and on failure **return a typed codec `Cause`** instead of throwing.

```java
// Descriptor-driven fact codec (sketch), replacing hand-written uuidCodec():
static <T, P> TypeCodec<T> mappedCodec(Class<T> type, int tag, TypeCodec<P> primitive, Mapping<T, P> m) {
    return new TypeCodec<>(type, tag,
        (codec, buf, val) -> primitive.write(codec, buf, m.lower().apply(val)),
        (codec, buf) -> m.lift().apply(primitive.read(codec, buf))
                         .orElseThrow(/* or: surface as typed deserialization Cause — see open Q */));
}
```

**Typed failure — open tension.** The fact read path is currently *infallible-by-signature* (`TypeCodec` reader returns `T`, not `Result<T>`). Making descriptor-driven decode *honestly* typed requires either (a) a `Result`-returning reader variant on `TypeCodec`/`SliceCodec`, or (b) a documented "throw a typed `CodecError` cause" bridge at the deserialization boundary. This is why facts is proposed as a **later cut** (§7): unlike DB and HTTP, it needs a small change to the serialization SPI to honour parse-don't-validate on read. **OPEN QUESTION (§8, Q4).**

**Consistency win.** Once descriptor-driven, `Email`/`Uuid`/`Url`/`NonBlankString`/`MethodName` codecs (`NodeCodecs.java:113-158`) collapse to `mappedCodec(...)` calls; a VO can no longer have a DB mapping that disagrees with its fact codec. `Email`/multi-field VOs specifically require the **composite** form (they serialize two fields today, `NodeCodecs.java:113-121`), which is why facts and composite are natural companions in the same later cut.

---

## 5. The crux: the primitive `P` differs per boundary

This is the load-bearing design decision and deserves to be explicit, because a naïve "one `Mapping<T,P>` serves all boundaries" is subtly wrong.

**Observation.** For `SeatId`:
- DB column type is `uuid` → the natural `P` is **`UUID`** (bound natively; matters for indexes/perf).
- HTTP path segment is always a **`String`**.
- Fact wire form could be `UUID`-as-16-bytes, `UUID`-as-string, etc.

A single `Mapping<SeatId, UUID>` cannot *directly* serve a `String` path segment. Three ways to resolve it:

- **(A) Canonical `P` + framework-owned `P↔wire` legs (RECOMMENDED).** The VO declares `Mapping<T, P>` against its **domain primitive** `P` (the natural DB/domain type: `UUID`, `Long`, `String`, enum-name). Each boundary composes the *primitive↔its-own-wire* leg from a small **framework-owned table keyed by `P`**:
  - DB: `P` bound natively to the column (no extra leg).
  - HTTP: compose `String→P` from the existing `PathParameter.aUuid()/aLong()/aInteger()/…` family (`PathParameter.java:53-160`), then `lift`.
  - Facts: compose the `SliceCodec` primitive codec for `P`, then `lift`.

  The VO stays *single-declaration and transport-neutral* — it names only `P`, never `String`/`ByteBuf`. Boundaries own the `P↔wire` leg generically. **When `P` is not in the framework's known-primitive table** (e.g. an exotic `byte[]`), the boundary that needs the missing leg emits a **compile error** ("no HTTP String-parser for primitive type `X`; provide a String-based mapping or bind differently") — honest, and matches the compile-error criterion.

- **(B) Per-boundary descriptors.** A VO carries several mappings (`mapping()` for canonical, `httpMapping()` for the `String` form, …). Honest but multiplies the surface and re-opens the "which one is authoritative?" ambiguity the whole ticket is trying to close. Rejected as the default; may be a rare *escape hatch* for a VO whose HTTP form legitimately differs from its DB form.

- **(C) Canonicalise on `String`.** Force `P = String` for every VO so all boundaries share it. Rejected: DB loses native typed columns (a `uuid`/`bigint` column forced to `text` breaks indexing/among-DB portability).

**Recommendation: (A).** It yields exactly "one descriptor, many boundaries" with the primitive as the pivot and a tiny, framework-owned, well-understood set of `P↔wire` conversions (the same `UUID/Long/Integer/String/BigDecimal/…` set the HTTP and serialization layers already implement). It also makes the "known primitive set" the single place that defines which VOs are HTTP-bindable, which is a clean, inspectable contract.

**OPEN QUESTION (§8, Q3):** the exact membership of the framework's known-primitive `P↔wire` table (which primitives get an auto String-leg and an auto fact-leg), and whether (B)'s per-boundary override escape hatch is in scope for rc2.

---

## 6. Migration from `PgRepr` (must not break #388)

`PgRepr` shipped in #388 with **internal-only consumers**: the type + `pgRepr()` convention (slice-api), `PgReprResolver` + `FactoryGenerator` (pg-codegen), `RowDecodeError` (resource-api), and tests. No published-API external consumer exists (pre-1.0, rc2). This makes absorption cheap.

| Option | What happens to `PgRepr` | Trade-off |
|--------|--------------------------|-----------|
| **(A) Absorb (RECOMMENDED)** | Rename `PgRepr` → `Mapping` (chosen name), move to `org.pragmatica.aether.slice.mapping`, rename convention `pgRepr()` → `mapping()`. `PgReprResolver` becomes `MappingResolver` looking for `mapping()`. `PgRepr` **deleted**. | Cleanest single-source outcome; exactly the "build→observe→overhaul" the ticket sequenced (#388 was the DB-first proof, this is the overhaul). Cost: touches #388's just-shipped surface (one type, one convention, one resolver, its tests) — all internal, low risk, one mechanical pass. |
| **(B) DB specialization** | Keep `PgRepr` as a thin subtype/wrapper of `Mapping` for DB; add generic `Mapping` for HTTP/facts; keep both conventions (`pgRepr()` + `mapping()`). | Non-breaking to #388, but leaves two conventions and re-introduces the ambiguity ("which does the DB read — `pgRepr()` or `mapping()`?") the ticket wants gone. |
| **(C) Alias/deprecate** | Freeze `PgRepr` name/API; add `Mapping`; make `pgRepr()` return a `Mapping` view; mark `PgRepr` `@Deprecated`. | Non-breaking; smooth if external consumers existed — but none do, so the deprecation tail is pure legacy cruft in a pre-1.0 release. |

**Recommendation: (A) Absorb.** Given internal-only consumers and a pre-1.0 window, a clean rename is the honest outcome and avoids shipping a legacy name into 1.0. Concretely:
1. Add `Mapping<T,P>` (+ `CompositeMapping<T>` spec, sealed supertype) in a boundary-neutral package.
2. Rename convention `pgRepr()` → `mapping()`; update the two example/test VOs (`PgReprTest.java:27`, `PgReprMappingTest`).
3. Rename `PgReprResolver` → `MappingResolver`; retarget it to `mapping()` returning `Mapping`.
4. Delete `PgRepr`. `RowDecodeError` is unaffected (it names columns/causes, not `PgRepr`).
5. Envelope note: `FactoryGenerator`/route codegen output changes → **bump `ENVELOPE_FORMAT_VERSION`** per project invariant #3 (`ManifestGenerator.java`).

**OPEN QUESTION (§8, Q2):** whether the user wants (A) clean absorption or prefers to keep `PgRepr` as a named DB specialization (B) for readability at DB call sites.

*(Discovery-resolver placement note.* `PgReprResolver` lives in `pg-codegen`; the HTTP route generator lives in `jbct/slice-processor` — different processors, different modules. Both need the *same* `mapping()` convention resolution over `javax.lang.model` `TypeMirror`. Either (i) duplicate the ~40-line resolver in each processor, or (ii) extract a shared processor-support module. Recommend (i) duplicate for rc2 — the resolver is tiny and the two processors share no processor-level dep today; revisit extraction if a third consumer appears. This is an integration detail, not a user decision.)*

---

## 7. Scope: rc2 vs later

The ticket's own sequencing is "prove `PgRepr` at DB first, then generalize — build→observe→overhaul." #388 was the proof. This spec is the overhaul; it should still land incrementally.

### 7.1 rc2 — minimal coherent first cut (RECOMMENDED)

The two **synchronous request-path** boundaries, which deliver the acceptance criterion's headline (one declaration binds a column *and* lifts a path segment → 400):

1. **`Mapping<T,P>` type + `mapping()` convention** in a boundary-neutral package (generalized `PgRepr`).
2. **DB boundary** retargeted to the new name (behaviour-identical; §6-A).
3. **HTTP path + query binding** via resolver + `P↔String` composition (§4.2, §5-A), typed 400 on failure; VO path/query components now supported and transport-neutral.
4. **Single-primitive only.** Composite path binding → precise compile error (§4.2).
5. **Envelope bump** for codegen output changes (§6).

This is self-contained, needs no serialization-SPI change, and satisfies "bind a SQL column + lift an HTTP path segment (→400) from one declaration."

### 7.2 Later (rc3+)

- **Facts boundary** (descriptor-driven codecs) — needs the `Result`-returning read decision (§4.3, Q4). Collapses the hand-written `NodeCodecs`/`WorkerCodecs` VO codecs.
- **Composite / multi-primitive** `CompositeMapping<T>` implementation across DB (N columns), facts (N fields), HTTP (N segments) — unblocks `Money`, `Email`.
- **`trusted` hatch** semantics validated per boundary (composes fine: `trusted` only removes `lift`'s fallibility; the `P↔wire` leg can still fail).
- **Convergence with #385 / #396** — the same descriptor is the substrate for type-checked error refs and typed topics; land after DB+HTTP+facts are unified.

**OPEN QUESTION (§8, Q1):** is DB+HTTP an acceptable rc2 cut (recommended), or does the user want facts (and therefore the serialization-SPI change + composite) pulled into rc2 to hit "all three from one declaration" literally within rc2?

---

## 8. Open questions

- **Q1 — rc2 scope.** DB+HTTP for rc2 with facts+composite in rc3 (recommended), or pull facts+composite into rc2 to satisfy "all three boundaries" literally? *(§7)*
- **Q2 — `PgRepr` migration.** Clean absorption/rename (recommended, A) vs keep `PgRepr` as a named DB specialization (B)? *(§6)*
- **Q3 — known-primitive `P↔wire` table.** Exact set of primitives that auto-get a String-leg (HTTP) and a fact-leg, and whether the per-boundary override escape hatch (§5-B) is in scope for rc2. *(§5)*
- **Q4 — facts typed decode.** Add a `Result`-returning reader to `TypeCodec`/`SliceCodec` (honest parse-don't-validate on read) vs bridge via a thrown typed `CodecError` at the deserialization boundary? *(§4.3)*
- **Q5 — THE NAME (reserved for the user).** `Mapping` (recommended, safe) vs `Prism` (precise, jargon) vs another; drives the convention method and composite names. *(§3)*
- **Q6 — composite author API.** Hide the erased `List<Object>` behind a typed builder (`Mapping.composite().add(...).assembledBy(...)`) — confirm the ergonomic target before implementing composite. *(§2.2)*

---

## 9. Assumptions

- **[ASSUMPTION]** `PgRepr` has no external (published-API) consumers — only the slice-api type/convention, pg-codegen resolver/generator, resource-api `RowDecodeError`, and tests. Verified by repo grep (`pgRepr()` appears only in those seven files). A clean rename is therefore safe.
- **[ASSUMPTION]** The framework's existing `PathParameter` JDK-type parsers (`aUuid`-style for `UUID`, `aLong`, `aInteger`, `aString`, `aDecimal`, date/time) and `SliceCodec` primitive codecs together cover the `P` types that real VOs use for IDs/enums (`UUID`, `Long`, `Integer`, `String`, enum-name-as-`String`). VOs whose `P` is outside this set are HTTP-bindable only via an explicit rule and otherwise produce a compile error (§5). *(Note: a `PathParameter.aUuid()` factory is assumed; if absent it is a trivial addition alongside the existing family in `PathParameter.java`.)*
- **[ASSUMPTION]** Path/query segments are the HTTP binding sites in scope; request **body** binding stays JSON-mapper-driven (`SliceRequestContext.fromJson`, `SliceRequestContext.java:90`) and is out of scope — a VO inside a JSON body is handled by the JSON layer, not this descriptor.
- **[ASSUMPTION]** Changing codegen output requires an `ENVELOPE_FORMAT_VERSION` bump (project invariant #3); the exact new value is set at implementation time.
- **[ASSUMPTION]** Multi-field VOs (`Email`, `Money`) are the primary drivers for the composite form and can wait for rc3; no rc2 VO on the DB or HTTP path requires composite binding.

---

## 10. References

### Internal — DB descriptor (the thing being generalized)
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/repr/PgRepr.java:34` — the `PgRepr<T,P>(lower, lift)` record; `:44` `trusted`; `:28-30` composite deferred.
- `aether/slice-api/src/test/java/org/pragmatica/aether/slice/repr/PgReprTest.java:27-73` — `pgRepr()` usage + round-trip/`trusted` laws.
- `aether/pg-tools/pg-codegen/.../processor/PgReprResolver.java:50,74-93` — reflection-free `mapping()`-style discovery (learns `P` from return type).
- `aether/pg-tools/pg-codegen/.../processor/FactoryGenerator.java:39-42,131-134,453-457` — generated bind (`lower`) + guarded decode (`lift` + `RowDecodeError`).
- `aether/resource/api/src/main/java/org/pragmatica/aether/resource/db/RowDecodeError.java:18-32` — typed DB decode failure.

### Internal — HTTP boundary (new binding site)
- `integrations/http-routing/.../PathParameter.java:23-28` — `parse(String)→Result<T>` (the String-analog of `lift`); `:53-160` the JDK parser family; `:38-50` `spacer`.
- `integrations/http-routing/.../QueryParameter.java` — query analog (`Result<Option<T>>`).
- `integrations/http-routing/.../ParameterError.java:6-30` — typed parse failure → 400.
- `jbct/slice-processor/.../routing/RouteSourceGenerator.java:73-121` (`TYPE_TO_PATH_PARAMETER`), `:1021-1028` (`segmentArg`), `:1086-1093` (`aString` fallback for unknown types — the gap), `:724-757` (path→record constructor wiring), `:968-1008` (record-component walk, composite precedent).
- `jbct/slice-processor/.../routing/PathParam.java:12-24` — path param model (defaults type to `String`).
- `aether/http-routing-adapter/.../impl/SliceRequestContext.java:90,128-148` — raw `List<String>` path params + JSON body binding (body out of scope).

### Internal — facts boundary (later cut) + name collision
- `integrations/serialization/api/.../Codec.java:26` — `@Codec` annotation (**name collision** with candidate `Codec`).
- `integrations/serialization/api/.../CodecFor.java:28` — `@CodecFor` external-type declaration.
- `integrations/serialization/api/.../SliceCodec.java:31-56` — runtime codec interface + primitive tag space.
- `aether/node/.../NodeCodecs.java:96-158` — hand-written throwing VO codecs (`Email`, `Uuid`, `Url`, `NonBlankString`, `MethodName`).
- `aether/node/.../worker/WorkerCodecs.java:82-90` — same pattern.

### Internal — functional idioms the descriptor must fit
- `core/src/main/java/org/pragmatica/lang/Functions.java:29-30` — `Fn1<R, T1>` (`R apply(T1)`; the `Fn1<OUT, IN>` order used above).
- `Result<T>`, `Option<T>`, `Cause` — Pragmatica Core (parse-don't-validate, typed errors, no exceptions).

### External
- [GitHub issue #397](https://github.com/pragmaticalabs/pragmatica/issues/397) — this ticket (labels `enhancement`, `rc2`; milestone v1.0.0-rc2).
- Related: [#388](https://github.com/pragmaticalabs/pragmatica/issues/388) (`PgRepr`), [#385](https://github.com/pragmaticalabs/pragmatica/issues/385) (typed error refs), [#396](https://github.com/pragmaticalabs/pragmatica/issues/396) (typed topics).
