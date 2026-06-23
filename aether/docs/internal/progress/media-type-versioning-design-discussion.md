# Design Discussion — API Versioning (#198) + Media Types (#339)

**Status:** discussion prep (NO implementation until decisions are signed off).
**Purpose:** surface the design decisions — especially the media-type model #339 — so we can walk them one-by-one before writing code.

This complements the #198 GitHub spec (which covers the versioning structure, detection mechanisms, deprecation lifecycle). It does **not** restate that; it focuses on the **decision points that need a call**, with options + a recommendation each.

---

## 0. The crux (grounded in the code, not the ticket)

Both features touch the **same surface**: the slice `routes.toml` route definitions — there is **no Java route annotation**; routes are declared in TOML and compiled by the slice-processor.

Data flow (today):
```
routes.toml  ([routes] flat table, entries are bare strings "GET /{id}")
  → RouteConfigLoader  (jbct/slice-processor/.../routing/RouteConfigLoader.java)
  → RouteConfig { Map<String,RouteDsl> routes, prefix, security, errors }
  → RouteSourceGenerator  (8 codegen variants; hardcodes .asJson() ×8, .withBody(fromJson) ×4)
  → generated {Slice}Routes.routes() : Stream<Route<?>>
  → RequestRouter.findRoute(method, path)   (route key = (method, path); NO version, NO media type)
  → SliceRouter.successToResponse(value, contentType)  (text/* → toString; else → JSON, hardcoded)
```

What exists vs absent:
- **API versioning:** 100% absent (the #198 spec is a draft; no `/vN/`, no `API-Version` header, no `Deprecation`/`Sunset`).
- **Media types:** response is JSON or `text/*` (via `toString()`) only; request body is always `fromJson`. No `consumes`/`produces` anywhere.
- **Route framework already supports** `.as(ContentType)` / `.asText()` / `Route.contentType()` on the output side, and `RequestContext` already exposes `body()` (raw), `bodyAsString()`, `fromJson()`, `multipartRequest()` on the input side. **The plumbing exists; only the slice layer fails to surface it.**

Because both features edit the same route entries, they must be **co-designed**. The agreed direction (handover): route entries become **inline tables** so a version restructure and a media-type field land in one consistent schema:
```toml
get = { route = "GET /{id}", produces = "text/csv", consumes = "application/json" }
get = "GET /{id}"   # bare string still valid → JSON in / JSON out (back-compat)
```

---

## 1. MEDIA TYPES (#339) — the decisions to discuss

### D1. Serialization model — *the central question*
`produces`/`consumes` declare a **Content-Type header**. The real question is **how the method's return value becomes bytes of that type** (and how the request bytes become the parameter). Proposed model: the media type selects a **serialization strategy**, and the framework never needs format-specific serializers (no CSV/PDF writer in the framework) — the slice produces the bytes/string, the framework labels them.

| `produces` category | Method returns | Framework does |
|---|---|---|
| `application/json` (default) | a POJO | `jsonMapper.writeAsBytes` (today's behavior) |
| `text/*` (csv, plain, html…) | `String` | `toString()` → bytes, set Content-Type |
| binary (`application/octet-stream`, `application/pdf`, images, Prometheus text…) | `byte[]` (or `String`) | **verbatim passthrough** (NEW), set Content-Type |

Symmetric for `consumes` → body binding:

| `consumes` category | Method parameter | Framework does |
|---|---|---|
| `application/json` (default) | POJO | `fromJson` (today) |
| `text/*` | `String` | `bodyAsString` |
| `application/octet-stream` | `byte[]` | `body()` raw |
| `multipart/form-data` | `MultipartRequest` | `multipartRequest()` |

**Recommendation:** adopt this strategy-by-category model. It keeps the framework format-agnostic (just JSON / text / raw / multipart) and pushes domain serialization (CSV rows, PDF bytes) into the slice where it belongs. **Discuss:** is "verbatim passthrough of `byte[]`/`String`" the right contract for non-JSON/non-text, or do we want a pluggable `BodyWriter`/`BodyReader` SPI now (heavier, more future-proof)?

### D2. Declaration syntax
Inline-table entries (above), `produces`/`consumes` optional, default both = `application/json`. A parallel `[media]` section was rejected (it fragments into `[vN.media]` under versioning). **Recommendation:** inline tables. **Discuss:** field names (`produces`/`consumes` vs `out`/`in`); allow a list for `consumes` (accept multiple) or single only.

### D3. Compile-time type-consistency check
The slice-processor knows each method's param/return Java types. It can **reject at compile time** a route whose media type contradicts the method signature (e.g., `consumes="application/octet-stream"` but the param is a POJO; `produces="text/csv"` but the return is a POJO not `String`). **Recommendation:** yes — add this validation; it turns a class of runtime 500s into build errors. **Discuss:** how strict (hard error vs warning) for the ambiguous middle (`produces=text/*` + return type `String` is clear; + return type POJO — error or fall back to `toString()`?).

### D4. Fixed output vs content negotiation
`produces` = a **fixed** declared response Content-Type, **not** negotiated against the request `Accept` header. (Accept-based negotiation and vendor media-type *versioning* — `application/vnd.aether.x.v2+json` — are explicitly deferred by the #198 spec, §13.4 / out-of-scope.) **Recommendation:** fixed output for rc2. **Discuss:** confirm we're not committing any schema that blocks adding negotiation later.

### D5. `SliceRouter.successToResponse` changes
Today (line ~90): not-text ⇒ JSON, hardcoded. New: branch on the route's declared `produces` category (json / text / raw). Plus a `null`/204 path (exists) and a 500 on serialization failure (exists). **Recommendation:** small, localized change driven by `route.contentType()` (already carried). Low risk.

---

## 2. VERSIONING (#198) — the decisions that interact with media types

### D6. Schema shape = the **one-way door** (must land in rc2)
`[api]` (version-agnostic `prefix`, `requireVersionHeader`) + `[vN.routes]` (per-version route tables, inline-table entries) + `[vN]` (deprecated / sunset / defaultIfMissing). Bare `[routes]` stays valid (back-compat sugar → treated as a single default version). **Recommendation:** lock this schema in rc2; it's the irreversible part once `routes.toml` ships. Media-type fields ride inside the `[vN.routes]` inline tables, so #339 composes cleanly.

### D7. Detection mechanism — path vs header
- **Path mode** (`{prefix}/v{N}/...`): composes with the existing `(method, path)` router for free — version is just a path segment. Lowest-risk, ship in rc2.
- **Header mode** (`API-Version: N`, `defaultIfMissing`, `requireVersionHeader`): needs NEW version extraction + a (version, method, path) route key in `RequestRouter`/`SliceRouter`. Bigger change.

**Recommendation:** ship **path mode** in rc2 (schema supports both; header-mode *wiring* can phase to rc3 without breaking the schema). **Discuss:** is header mode needed for rc2, or is the schema-with-path-impl enough?

### D8. Method binding
Auto-suffix `getV1`/`getV2` by convention + explicit-binding escape hatch (per spec §5). **Recommendation:** follow the spec. **Discuss:** is auto-suffix worth the magic, or require explicit bind keys?

### D9. Cross-version media-type divergence (the #198 × #339 interaction)
Same logical route, different `produces` across versions (`v1` → JSON, `v2` → CSV). With slice-version weighted/canary routing layered on top, a client could get either content type per request. **Recommendation:** treat a route's media type as **part of its per-version contract** (it travels with the version, not the (method,path) key) — so divergence is intentional and bounded by the version, not random. **Discuss:** accept divergence, or forbid media-type changes across versions of the same route?

---

## 3. Testing strategy (needs its own design — flagged in the #198 ticket)
Versioning + media types are test-heavy and the **approach itself needs design** before Phase 1:
- path-mode (and header-mode if in scope) route matrices; default-version + `requireVersionHeader` policy.
- `consumes`/`produces` round-trips incl. **raw `byte[]` and multipart** (the new binding paths).
- cross-version `produces` divergence vs `AppHttpServer` weighted/canary routing.
- back-compat: bare-string entries, flat `[routes]`, default-JSON unchanged.
- compile-time type-consistency rejections (D3) as negative codegen tests.

**Recommendation:** write a short Testing-Strategy section first; gate implementation phases on it.

---

## 4. Phasing / one-way-door guard
- **Must land in rc2 (irreversible):** the `routes.toml` schema shape (D6) + inline-table `consumes`/`produces` (D2) + path-mode (D7) + the serialization model (D1) since it sets the slice contract.
- **May phase to rc3 (non-breaking):** header-mode wiring, automated deprecation/Sunset emission, Accept-based/vendor media-type versioning (§13.4), pluggable BodyReader/Writer SPI (if D1 chooses verbatim now).
- **Sibling one-way-door:** #300 (management-plane version prefix) — decide whether to unify with #198's version concept before the config format freezes.

---

## 5. Open questions to walk one-by-one (the discussion)
1. **D1** — serialization-by-category (verbatim passthrough) vs a BodyReader/Writer SPI now?
2. **D3** — compile-time type-consistency: hard error everywhere, or lenient fallback?
3. **D7** — is header-mode in rc2 scope, or schema-now / wiring-rc3?
4. **D9** — allow or forbid media-type divergence across versions of one route?
5. **D2** — field naming + single vs multiple `consumes`.
6. **#300** — unify management-plane version prefix with #198 now or leave to rc3?
