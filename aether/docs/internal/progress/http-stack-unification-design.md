# Design — HTTP Stack Unification (Foundation for #339 + #198)

**Status:** APPROVED (design walk 2026-06-23). Implementation in gated sub-phases.
**Scope owner:** this is the *foundation* epic that #339 (media types) and #198 (API versioning) build on. Companion doc: [`media-type-versioning-design-discussion.md`](media-type-versioning-design-discussion.md).

---

## 1. Goal & non-goals

**Goal.** Collapse the duplicated, hand-bridged HTTP type stack into ONE shared, production-ready foundation so that response serialization is unified (category-driven, with binary passthrough), the `ContentType` vocabulary is rich + extensible, and there is a single `RequestContext`/response model. After this lands we do not return to this seam.

**Non-goals (this epic).** No new HTTP features beyond what unification requires. Accept-based content negotiation, vendor media-type versioning, BodyReader/Writer SPI — all remain out of scope (deferred per the companion doc). Slice authorization/security semantics are unchanged (security stays an aether concern, surfaced via an accessor).

---

## 2. Phasing (each step is build-gated; no big-bang)

| Phase | What | Gate |
|---|---|---|
| **A1** | Value types + serializer → new `http-types` module; migrate imports. Mechanical, low semantic risk. | full build + http/routing unit tests |
| **A2** | Collapse the 4 request contexts → 1 transport-agnostic interface + 1 Netty impl; unify response side; migrate ~20 consumers. | build + in-JVM Forge/Ember + container cluster-A |
| **B** | #339 — `consumes`/`produces` in `routes.toml` → codegen, on the unified base. | in-JVM Forge proof |
| **C** | #198 — versioning (schema, path+header, binding, deprecation, observability). | in-JVM + cloud |
| **(final)** | Full 15-suite Hetzner cloud acceptance, container + JVM. | release gate |

---

## 3. Current state (the duplication being removed)

Two independent sibling modules (`integrations/http-routing` and `integrations/net/http-server`, zero cross-imports) each define a parallel HTTP vocabulary, bridged by hand in the aether adapters.

**Duplicated value types:** `HttpMethod`, `HttpStatus`, `ContentType`, `ContentCategory` (`{PLAIN_TEXT,JSON,BINARY,HTML}` vs `{TEXT,JSON,BINARY,XML}`), `RequestContext`. `net/http-client` adds a 3rd partial set (`HttpError`, `ContentType`).

**Request-context abstractions (4):** `http.routing.RequestContext` (+`RequestContextImpl`, Netty-coupled; 3 consumers) · `http.server.RequestContext` (9) · aether `HttpRequestContext` (11 — what slices/handlers code against) · `SliceRequestContext` (2, bridges aether→routing).

**Response abstractions (2):** `ResponseWriter` (byte[] sink) · `HttpResponseData` (aether value object).

**Scattered serialization (the #339 bug surface):** ~9 independent JSON-serialize sites + 3 byte-identical `isTextContent` copies + 1 manual `toServerContentType` bridge. None consult `category()` for output; **no binary passthrough anywhere except `StaticFileHandler`** — so a `byte[]` return under `application/octet-stream` is JSON-encoded. Confirmed at: `SliceRouter.successToResponse:90`, `ManagementRouter.writeJson:133`, `ForgeApiHandler.sendSuccessResponse:211`; concrete victim `MavenResponse` (carries its own contentType+byte[], discarded by SliceRouter). Raw `NettyResponseWriter`/`Http3ResponseWriter` are pure byte sinks (correct — they make no serialization decision).

---

## 4. Target architecture

### 4.1 New module `integrations/net/http-types`
- Coords: `org.pragmatica-lite:http-types:1.0.0-rc2`, parent `net`.
- **Dependencies: `core` ONLY.** Netty-free by design (see §4.4 — the seam is `byte[]`-based, so no `ByteBuf` leaks into the base). This is what lets every layer depend on it without pulling Netty.
- License: Apache-2.0 (it lives in `integrations/`; abstractions move *down* from aether — never the reverse, so no BSL concern).

### 4.2 Ownership after unification
| Lives in `http-types` | Lives in `net/http-server` | Lives in `http-routing` | Stays in aether |
|---|---|---|---|
| `ContentType`, `ContentCategory`, `CommonContentType`, `HttpMethod`, `HttpStatus`, `HttpError`, `ProblemDetail`, `CodecError`, `JsonCodec` (interface), `ResponseSerializer`, `RequestContext` (interface), `MultipartRequest`, response value type | Netty/HTTP3 servers, `ResponseWriter` impls, the **Netty-backed `RequestContext` impl**, `JsonCodecAdapter` location TBD | `Route`/builders, `RequestRouter`, `RouteSource`, path/query param DSL, `JsonCodecAdapter` (Jackson) | security stack (`SecurityContext`/`Principal`/`Role`), `HttpRequestHandler`, slice wiring |

### 4.3 Layering (deps point downward)
```
core
 └── http-types        (Netty-free: types + serializer + RequestContext interface)
      ├── http-routing  (Route DSL, RequestRouter, Jackson JsonCodecAdapter)
      ├── net/http-server (Netty/HTTP3 servers, ResponseWriter, Netty RequestContext impl)
      └── net/http-client
            └── aether/* (handlers, slice router, node, forge, dashboard)
```

### 4.4 `JsonCodec` seam — `byte[]`-based
Redefine the seam so the base stays Netty-free:
```java
public interface JsonCodec {
    Result<byte[]> serialize(Object value);
    <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token);
}
```
`ByteBuf` handling is confined to the Netty `RequestContext` impl (converts request content → `byte[]` before `deserialize`) and is gone from the interface. `JsonCodecAdapter` (Jackson) implements it; `ResponseWriter` already accepts `byte[]`, so the JSON path is `serialize → write` with no extra copy.

---

## 5. Unified `ContentType` + `ContentCategory`

```java
public enum ContentCategory { JSON, TEXT, HTML, XML, BINARY, FORM_URLENCODED, MULTIPART }

public interface ContentType {
    String headerText();
    ContentCategory category();
    static ContentType contentType(String headerText, ContentCategory category) { ... } // escape hatch
}
```

**Constant set (`CommonContentType`)** — "as many as practically reasonable" + escape hatch for the rest:

| Constant | header | category | serialize as |
|---|---|---|---|
| `TEXT_PLAIN` | `text/plain; charset=UTF-8` | TEXT | String |
| `TEXT_HTML` | `text/html; charset=UTF-8` | HTML | String |
| `TEXT_CSS` | `text/css; charset=UTF-8` | TEXT | String |
| `TEXT_JAVASCRIPT` | `text/javascript; charset=UTF-8` | TEXT | String |
| `TEXT_CSV` | `text/csv; charset=UTF-8` | TEXT | String |
| `TEXT_EVENT_STREAM` | `text/event-stream` | TEXT | String (SSE) |
| `APPLICATION_JSON` | `application/json; charset=UTF-8` | JSON | JsonCodec |
| `APPLICATION_PROBLEM_JSON` | `application/problem+json; charset=UTF-8` | JSON | JsonCodec |
| `APPLICATION_XML` | `application/xml; charset=UTF-8` | XML | String |
| `APPLICATION_YAML` | `application/yaml; charset=UTF-8` | TEXT | String (external contracts) |
| `APPLICATION_OCTET_STREAM` | `application/octet-stream` | BINARY | byte[] verbatim |
| `APPLICATION_PDF` | `application/pdf` | BINARY | byte[] verbatim |
| `APPLICATION_FORM_URLENCODED` | `application/x-www-form-urlencoded` | FORM_URLENCODED | (input) |
| `MULTIPART_FORM_DATA` | `multipart/form-data` | MULTIPART | (input) |
| `IMAGE_PNG` | `image/png` | BINARY | byte[] verbatim |
| `IMAGE_JPEG` | `image/jpeg` | BINARY | byte[] verbatim |
| `IMAGE_GIF` | `image/gif` | BINARY | byte[] verbatim |
| `IMAGE_WEBP` | `image/webp` | BINARY | byte[] verbatim |
| `IMAGE_SVG` | `image/svg+xml` | XML | String (SVG is XML text) |

Anything else (e.g. Prometheus `text/plain; version=0.0.4`, vendor types) → `contentType(headerText, category)` escape hatch.

---

## 6. `ResponseSerializer` contract

One function, every output path delegates to it:
```java
Result<byte[]> serialize(Object value, ContentType ct, JsonCodec json);
```
Dispatch on `ct.category()`:
- `JSON` → `json.serialize(value)`
- `TEXT` / `HTML` / `XML` → `value instanceof String s ? bytes(s) : bytes(value.toString())`
- `BINARY` → `value instanceof byte[] b ? b : value instanceof String s ? bytes(s) : error(typeMismatch)`
- `FORM_URLENCODED` / `MULTIPART` are input categories → not valid `produces`; serializer returns a clear error if used as output.

HTTP-level concerns stay in the adapters (not the serializer): `null`→204, status-code selection, and error→`ProblemDetail` (which itself renders through this serializer via `APPLICATION_PROBLEM_JSON`). The type-mismatch cases above become compile-time errors in Phase B (#339 D3).

---

## 7. Unified `RequestContext` (transport-agnostic — Lock 1)

One interface, NO Netty in it; one Netty-backed impl in `net/http-server`. Collapses all 4 abstractions.
```java
public interface RequestContext {
    HttpMethod method();
    String path();
    String requestId();
    Map<String,String> headers();
    Map<String,List<String>> queryParams();
    List<String> pathParams();
    byte[] bodyBytes();                       // raw (no ByteBuf in the interface)
    String bodyAsString();
    <T> Result<T> fromJson(TypeToken<T> t);   // via injected JsonCodec
    Result<MultipartRequest> multipartRequest();
    Route<?> route();
    Option<Principal> principal();            // security surfaced, not baked in
    // + matchPath/matchQuery default helpers (moved from today's routing.RequestContext)
    HttpHeaders responseHeaders();            // response header accumulation
}
```
- `bodyBytes()`/`bodyAsString()` replace `ByteBuf body()`. The Netty impl holds the `FullHttpRequest` internally.
- `matchPath`/`matchQuery` (today on `http.routing.RequestContext`) ride along as default methods.
- `principal()` returns the authenticated principal if any; the authorization machinery stays in aether.

**Response side:** keep `ResponseWriter` as the `byte[]` sink; unify `HttpResponseData` into a single response value type (status + headers + `byte[]` body) used by both the slice path and direct handlers.

---

## 8. Migration map

### A1 — value types + serializer (build gate)
1. Create `integrations/net/http-types` (pom + add to `integrations/net/pom.xml` modules).
2. Author unified `ContentType`, `ContentCategory`, `CommonContentType`, `HttpMethod`, `HttpStatus`, `HttpError`, `ProblemDetail`, `CodecError`, `JsonCodec` (byte[]), `ResponseSerializer`.
3. Delete the duplicate definitions in `http-routing` + `net/http-server` + `net/http-client`; repoint imports.
4. `JsonCodecAdapter` (Jackson) implements the byte[] seam (stays in `http-routing`).
5. Route every output site through `ResponseSerializer`: `SliceRouter.successToResponse`, `ManagementRouter.writeJson`/`isTextContent`, `ForgeApiHandler.sendSuccessResponse` (drop `toServerContentType`), `AppHttpServer.sendResponse`/`sendProblem`/health. Behavior-preserving for JSON/text; binary newly correct.
   - **Gate:** full `./build.sh` (or focused `mvn install` across integrations + aether/node + forge) + existing http/routing unit tests green.

### A2 — request/response context (build + in-JVM + container-A gate)
6. Author unified `RequestContext` interface (+`MultipartRequest`) in `http-types`; one Netty-backed impl in `net/http-server`.
7. Migrate the ~20 consumers off `http.routing.RequestContext` / `http.server.RequestContext` / aether `HttpRequestContext` / `SliceRequestContext` onto the unified one. Collapse `SliceRequestContext` (it becomes the Netty impl or a thin adapter).
8. Unify the response value type (`HttpResponseData` → shared).
   - **Gate:** build + Forge/Ember in-JVM smoke + container cluster-A (10/10 must hold — proves no regression on the live serialization/routing paths).

---

## 9. Out of scope / deferred
- Accept-based negotiation, vendor `application/vnd.*` versioning, BodyReader/Writer SPI (companion doc).
- `net/http-client` request-body serialization is request-construction, not response serialization — left as-is unless the shared types simplify it for free.
- Management-API version prefix (#300) — separate rc3 item; not part of this foundation.

---

## 10. Decision log
- **D1 (serialization model):** RESOLVED → full unification. The by-category model already exists (`ContentType.category()`); BodyReader/Writer SPI rejected as redundant. One `ResponseSerializer`, binary passthrough added.
- **Scope:** full #198 + #339 in rc2 (user, 2026-06-23).
- **Unification reach:** fold everything — value types + serializer + the 4 request contexts + response — into the foundation; production-ready, done once (user).
- **Lock 1:** unified `RequestContext` is transport-agnostic (no Netty in the interface; `byte[]`/`String` body access).
- **Lock 2:** land Phase A as A1 (value types + serializer) then A2 (request/response contexts), each build-gated.
- **YAML:** `APPLICATION_YAML` constant added (external contracts) — distinct from the project's no-YAML-for-our-config rule.
- **Module:** `integrations/net/http-types`, deps `core` only, Netty-free `byte[]` seam.
- **Package strategy (churn minimization):** unified value types live in package **`org.pragmatica.http`** — the package `net/http-server` already uses. So `net/http-server` consumers' imports are UNCHANGED; only `http-routing`-side value-type imports (`org.pragmatica.http.routing.{ContentType,ContentCategory,HttpMethod,HttpStatus,HttpError,ProblemDetail}` → `org.pragmatica.http.X`) repoint. `Route`/`RequestRouter`/param-DSL stay in `org.pragmatica.http.routing`.
- **Enum value mapping:** `http.routing.ContentCategory.PLAIN_TEXT` → unified `TEXT`; `.HTML`/`.JSON`/`.BINARY` unchanged; `net/http-server.ContentCategory.{TEXT,JSON,XML,BINARY}` unchanged.
