# Slice HTTP Media Types (`consumes` / `produces`) — Design Spec

| | |
|---|---|
| **Status** | Draft v0.1 |
| **Issue** | [#339](https://github.com/pragmaticalabs/pragmatica/issues/339) (OPEN) |
| **Date** | 2026-07-04 |
| **Author** | design-stream |
| **Implementation state** | Design approved 2026-06-23 (design walk); implementation **landed** in `130d5c1ee` (2026-06-24) on `release-1.0.0-rc2`. This spec codifies the as-landed design so the issue can be validated and closed against it. |
| **Companion docs** | [`http-stack-unification-design.md`](../internal/progress/http-stack-unification-design.md) (foundation, APPROVED) · [`media-type-versioning-design-discussion.md`](../internal/progress/media-type-versioning-design-discussion.md) (decision log) · [`api-versioning-and-media-types.md`](../slice-developers/api-versioning-and-media-types.md) (user guide) |

---

## 1. Problem

A slice's HTTP route definition captured path, parameters, version — but no request
(`consumes`) or response (`produces`) media type. Codegen baked JSON into **both
directions**. As of the pre-fix tree (`130d5c1ee^`):

- **Output:** `RouteSourceGenerator` hardcoded `.asJson()` at all 8 codegen variants
  (`jbct/slice-processor/.../routing/RouteSourceGenerator.java:489,523,560,580,611,642,681,718`).
  `SliceRouter.successToResponse` (`aether/http-routing-adapter/.../SliceRouter.java:77-93`)
  emitted `toString()` for `text/*` or JSON for everything else — a declared binary type got
  the right `Content-Type` header with a JSON body, and there was no raw-`byte[]` path.
- **Input:** codegen always emitted `.withBody(new TypeToken<…>(){})`, and `Route.withBody`
  was hardwired to `ctx.fromJson(type)` (`integrations/http-routing/.../Route.java`) — every
  typed body was JSON-deserialized. `RequestContext` already exposed `body()` /
  `bodyAsString()` / `multipartRequest()`, but a slice route could not opt into them.

Routes are declared in **`routes.toml`** (there is no Java route annotation); the fix
therefore lands in the TOML schema + slice-processor codegen + the unified serializer, not
in an annotation surface.

## 2. Design

### 2.1 Declaration surface — inline-table route entries (D2)

A route entry is either a bare string (unchanged) or an inline table with optional
`consumes` / `produces` fields (parsed at
`jbct/slice-processor/.../routing/RouteConfigLoader.java:338-365`):

```toml
[routes]
get    = "GET /{id}"                                                # bare string: JSON in / JSON out
export = { route = "GET /export", produces = "text/csv" }
upload = { route = "POST /upload", consumes = "application/octet-stream" }
form   = { route = "POST /submit", consumes = "multipart/form-data",
           produces = "text/html", security = "public" }
```

Both fields default to `application/json` (`RouteConfigLoader.resolveMedia:361-365`).
`consumes` is **single-valued** — one declared request type per route, no accept-lists.
The same fields ride inside versioned `[vN.routes]` inline tables (#198), so a route's
media type is part of its **per-version contract** (cross-version divergence is possible
by construction and bounded by the version key — D9).

### 2.2 Media-type model — category-driven, framework stays format-agnostic (D1)

`MediaType` (`jbct/slice-processor/.../routing/MediaType.java:34-141`) resolves the TOML
string to a `ContentCategory` + a codegen emit-expression:

- **Catalog** (`MediaType.java:43-66`): 19 known types mapping to `CommonContentType`
  constants (`text/plain|html|css|javascript|csv|event-stream`, `application/json|problem+json|xml|yaml|octet-stream|pdf|x-www-form-urlencoded`, `multipart/form-data`, `image/png|jpeg|gif|webp|svg+xml`). Matching is
  case-insensitive on the bare type (charset/params stripped).
- **Escape hatch** (`MediaType.java:100-130`): unknown types (e.g. Prometheus
  `text/plain; version=0.0.4`, vendor types) emit
  `ContentType.contentType("<raw>", ContentCategory.X)` with the category inferred from the
  prefix (`text/*`→TEXT, `*+json`→JSON, `*+xml`→XML, `application|image|audio|video/*`→BINARY);
  an uninferable category is a **build error**.

The framework never carries format-specific serializers (no CSV/PDF writers): the slice
produces the `String`/`byte[]`, the framework labels and passes it through.

### 2.3 Codegen — both directions

`RouteSourceGenerator`:

- **Output** (`RouteSourceGenerator.java:712-718`): JSON → `.asJson()` (byte-identical
  back-compat); anything else → `.as(<emitExpression>)`.
- **Input** (`RouteSourceGenerator.java:721-731`): body binding selected by `consumes`
  category — TEXT/HTML/XML → `.withStringBody()`, BINARY → `.withByteBody()`,
  MULTIPART → `.withMultipartBody()`, default (JSON) → `.withBody(new TypeToken<…>(){})`.

The three new `Route` builder methods (`integrations/http-routing/.../Route.java:376-384`,
impls `:1108-1121`) bind `ctx.bodyAsString()`, `ctx.body()` (raw), and
`ctx.multipartRequest()` respectively — the raw-bytes path in.

### 2.4 Compile-time type-consistency check (D3 — strict)

`validateMediaTypes` (`RouteSourceGenerator.java:558,689-700`) hard-errors via the
processor messager; rules live in the pure, unit-tested `MediaTypeTypeChecker`
(`jbct/slice-processor/.../routing/MediaTypeTypeChecker.java:37-72`):

- `produces` TEXT/HTML/XML → return `String` (or `byte[]`); BINARY → `byte[]` (or `String`);
  FORM_URLENCODED/MULTIPART as `produces` → rejected (input-only).
- `consumes` TEXT/HTML/XML → param `String`; BINARY → `byte[]`; MULTIPART →
  `MultipartRequest`; JSON → any type.

A media-type/signature mismatch is a **build error**, not a runtime 500.

### 2.5 Runtime output — unified serializer

`SliceRouter.successToResponse` (`aether/http-routing-adapter/.../SliceRouter.java:270-282`)
delegates to `ResponseSerializer.serialize(value, contentType, jsonCodec)`
(`integrations/net/http-types`, from the unification epic): JSON → `JsonCodec`;
TEXT/HTML/XML → `String`/`toString()` bytes; BINARY → `byte[]` **verbatim passthrough**.
`Content-Type` comes from the route's declared type
(`SliceRouter.headersForContentType:350-352`) — header and body can no longer contradict.

### 2.6 Content negotiation — none in v1 (D4)

`produces` is a **fixed, declared** response type. The request `Accept` header is ignored;
there is no negotiation and no `406 Not Acceptable` path. Accept-based negotiation and
vendor media-type versioning (`application/vnd.aether.x.v2+json`) are explicitly deferred
(#198 spec §13.4); nothing in the TOML schema blocks adding them later.

### 2.7 Error semantics

- **Declared-vs-signature mismatch** → compile-time error (§2.4). This is the primary
  defense; it removes the class of runtime type errors 415/406 would otherwise catch late.
- **Malformed JSON body** → existing `fromJson` failure path → error-mapped
  `ProblemDetail` response (unchanged).
- **Response serialization failure** → 500 (`SliceRouter.java:277-281`).
- **No 415 Unsupported Media Type:** the request's `Content-Type` header is **not**
  validated against `consumes`; the body is bound per the declared category regardless.
- **No 406:** follows from fixed output (§2.6).

The no-415/no-406 stance is deliberate for v1 (declared contract, not negotiated), but 415
enforcement is a candidate follow-up — see Open Questions.

### 2.8 Zero-config path — unchanged

Bare-string routes (and bare `[routes]` tables) remain valid and byte-identical: JSON in,
JSON out, `.asJson()` emitted, `withBody(fromJson)` binding, same headers. No existing
slice changes behavior. Envelope format version was bumped 1000 → 1001 for the codegen
change (current: 1007, `jbct/slice-processor/.../generator/ManifestGenerator.java:40`).

## 3. Per-operation behavior

| Route declaration | Method param (in) | Binding | Method return (out) | Emission | Content-Type |
|---|---|---|---|---|---|
| bare string (default) | POJO | `fromJson` | POJO | `JsonCodec` | `application/json; charset=UTF-8` |
| `consumes = "text/*"` (also HTML/XML) | `String` | `bodyAsString()` | — | — | — |
| `consumes = "application/octet-stream"` (any BINARY) | `byte[]` | `body()` raw | — | — | — |
| `consumes = "multipart/form-data"` | `MultipartRequest` | `multipartRequest()` | — | — | — |
| `consumes = "application/x-www-form-urlencoded"` | *(gap — see OQ-1)* | `fromJson` (wrong) | — | — | — |
| `produces = "text/csv"` (any TEXT/HTML/XML) | — | — | `String` | UTF-8 bytes | declared |
| `produces = "application/pdf"` (any BINARY) | — | — | `byte[]` | verbatim passthrough | declared |
| unknown type, e.g. `produces = "text/plain; version=0.0.4"` | — | — | per inferred category | per category | raw string as declared |

## 4. Blast radius (as landed in `130d5c1ee`)

- **slice-processor:** `MediaType`, `MediaTypeTypeChecker` (new), `RouteConfigLoader`,
  `RouteDsl`, `RouteSourceGenerator`; `ENVELOPE_FORMAT_VERSION` 1000→1001; negative +
  round-trip codegen tests in `slice-processor-tests`.
- **integrations/http-routing:** `Route` gains `withStringBody` / `withByteBody` /
  `withMultipartBody`. Depends on unified `ContentType`/`ContentCategory`/`CommonContentType`/`ResponseSerializer` in `integrations/net/http-types` (unification Phase A prerequisite).
- **aether:** `SliceRouter` output path via `ResponseSerializer`; `SliceManifest` touch;
  Forge e2e coverage (`SliceMediaTypeTest`, `EchoService` routes).
- **Docs:** slice-developer guide (`aether/docs/slice-developers/api-versioning-and-media-types.md`), feature catalog, envelope-versioning, CHANGELOG. No new Management-API endpoint → REST/CLI/docs triad not triggered.

## 5. Open questions

1. **OQ-1 — FORM_URLENCODED binding gap.** `MediaTypeTypeChecker.checkConsumes:70` accepts
   `FORM_URLENCODED` with any param type, but `bodyBindingCall` (`RouteSourceGenerator.java:725-731`)
   has no FORM_URLENCODED arm — it falls to the default and **JSON-parses a form body** at
   runtime. Either add a form-binding path (param type + `Route` builder) or reject
   `consumes = "application/x-www-form-urlencoded"` at compile time until one exists.
2. **OQ-2 — 415 enforcement.** Should the generated route (or `SliceRouter`) reject a
   request whose `Content-Type` contradicts the declared `consumes` with a typed 415
   `ProblemDetail`, instead of binding blindly? Cheap for BINARY/MULTIPART; for JSON it
   changes behavior for clients that omit the header. Decide before GA hardening.
3. **OQ-3 — streaming bodies.** `TEXT_EVENT_STREAM` exists in the catalog but both
   directions are fully buffered (`byte[]`/`String`); there is no chunked/streamed body
   path in or out. Defer or scope for rc3.
4. **OQ-4 — multiple `consumes`.** Single-valued today. Accepting a list (e.g. JSON *or*
   form) requires runtime dispatch on request `Content-Type` — interacts with OQ-2.
5. **OQ-5 — cross-version divergence lint.** Divergent `produces` across `[vN.routes]`
   versions of one logical route is allowed by construction (D9). Should the processor emit
   an informational note when it detects divergence, or stay silent?
