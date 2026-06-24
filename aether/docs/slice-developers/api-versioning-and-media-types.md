# API Versioning & Media Types

Declare request/response content types per route, and evolve a slice's HTTP API across versions without breaking existing clients — all from `routes.toml`, with compile-time validation.

This guide covers two related features:

- **Media types** (#339) — say what a route consumes and produces (`text/csv`, `application/octet-stream`, …), with a strict compile-time check that the declared category matches your method's Java types.
- **API versioning** (#198) — expose multiple versions of a slice side by side (`v1`, `v2`, …), select the served version by URL path or request header, and run a deprecation/sunset lifecycle that clients can observe.

Both are additive and backward-compatible: an existing slice with a flat `[routes]` block and bare-string routes keeps working unchanged (JSON in, JSON out, unversioned).

## Contents

- [Declaring media types](#declaring-media-types)
- [Versioning a slice](#versioning-a-slice)
- [Detection mode (operator)](#detection-mode-operator)
- [Deprecation lifecycle](#deprecation-lifecycle)
- [Migrating an existing slice](#migrating-an-existing-slice)
- [See Also](#see-also)

## Declaring media types

By default every route consumes JSON and produces JSON. A bare-string route entry needs nothing extra:

```toml
[routes]
create  = "POST /"
getById = "GET /{id:Long}"
search  = "GET /search?query&limit:Integer&offset:Integer"
```

To declare a non-JSON content type, switch the entry from a bare string to an **inline table** with a `route` field plus `produces` and/or `consumes`:

```toml
[routes]
exportCsv  = { route = "GET /export/{id:Long}", produces = "text/csv" }
download   = { route = "GET /download/{id:Long}", produces = "application/octet-stream" }
uploadText = { route = "POST /upload-text", consumes = "text/plain" }
uploadForm = { route = "POST /upload-form", consumes = "multipart/form-data" }
```

> The bare-string and inline-table forms can be freely mixed in the same `[routes]` block. The four inline-table entries above are taken verbatim from the `com.example.testslice` test fixture.

- `produces` sets the response `Content-Type` and how the return value is serialized.
- `consumes` sets the expected request `Content-Type` and how the request body is bound to your method parameter.
- Omitting either field defaults that direction to JSON, so `{ route = "...", produces = "text/csv" }` still consumes JSON.

### Recognized content types

Common media types resolve to a built-in `CommonContentType` constant (the slice-processor emits the constant directly). Matching is on the bare media type — case-insensitive, with any `; charset=...` parameter stripped — so `text/csv` and `text/csv; charset=UTF-8` resolve identically. The recognized set:

| Media type | Category |
|------------|----------|
| `text/plain` | TEXT |
| `text/html` | HTML |
| `text/css` | TEXT |
| `text/javascript` | TEXT |
| `text/csv` | TEXT |
| `text/event-stream` | TEXT |
| `application/json` | JSON |
| `application/problem+json` | JSON |
| `application/xml` | XML |
| `application/yaml` | TEXT |
| `application/octet-stream` | BINARY |
| `application/pdf` | BINARY |
| `application/x-www-form-urlencoded` | FORM_URLENCODED |
| `multipart/form-data` | MULTIPART |
| `image/png`, `image/jpeg`, `image/gif`, `image/webp` | BINARY |
| `image/svg+xml` | XML |

### Escape hatch — any media type

A media type that is not in the table above is still allowed. The processor infers a content category from the type's prefix (`text/*` → TEXT, `*+json` → JSON, `*+xml` → XML, `application/*` / `image/*` / `audio/*` / `video/*` → BINARY) and emits it through `ContentType.contentType(header, category)`:

```toml
[routes]
metrics = { route = "GET /metrics", produces = "text/plain; version=0.0.4" }   # Prometheus exposition
vendor  = { route = "GET /thing/{id:Long}", produces = "application/vnd.acme.thing+json" }
```

If a category cannot be inferred from the prefix, the build fails with `Cannot infer content category for media type: <value>` — declare a recognized type or a prefix the inference understands.

### Strict compile-time type rule

The declared media category must match the Java type your method uses. This is checked **at build time** by the slice-processor, not at runtime:

| Category | `produces` (return type) | `consumes` (parameter type) |
|----------|--------------------------|------------------------------|
| `JSON` | any type | any type |
| `TEXT`, `HTML`, `XML` | `String` or `byte[]` | `String` |
| `BINARY` | `byte[]` or `String` | `byte[]` |
| `MULTIPART` | not allowed (input-only) | `MultipartRequest` |
| `FORM_URLENCODED` | not allowed (input-only) | any type (no check) |

So declaring `produces = "text/csv"` on a method that returns a record is a **build error**:

```
Slice method 'exportCsv': produces category TEXT requires return type String (or byte[]), but method returns ExportResult
```

The matching method must return `String` (or `byte[]`):

```java
@Slice
public interface ReportService {
    // produces = "text/csv"  ->  must return String or byte[]
    Promise<String> exportCsv(ExportRequest request);

    // produces = "application/octet-stream"  ->  byte[] returned verbatim
    Promise<byte[]> download(DownloadRequest request);

    // consumes = "text/plain"  ->  parameter must be String
    Promise<UploadResult> uploadText(String body);
}
```

`MULTIPART` and `FORM_URLENCODED` are input-only — using them in `produces` is a build error (`produces media type with category MULTIPART is input-only and cannot be used for responses`).

### Back-compatibility

JSON is the default for both directions, so any pre-existing slice that never declared a media type keeps producing and consuming JSON with no change. Adding a `produces`/`consumes` to one route does not affect the others.

## Versioning a slice

An unversioned slice uses a flat `[routes]` block. A **versioned** slice replaces it with an `[api]` section plus one `[vN.routes]` block per version. Each version may also carry an optional `[vN]` metadata block.

This is the `com.example.versionedslice` fixture, verbatim:

```toml
[api]
prefix = "/api/orders"
requireVersionHeader = false

[v1.routes]
get = "GET /{id:Long}"

[v1]
deprecated = true
sunset = "2026-12-31"

[v2.routes]
get = "GET /{id:Long}"
upsert = "PUT /{id:Long}"

[v2]
defaultIfMissing = true

[errors]
default = 500
HTTP_404 = ["*NotFound*"]
```

Schema:

- **`[api]`** — `prefix` is the version-agnostic base path (e.g. `/api/orders`). `requireVersionHeader` is a header-mode flag (covered below); it defaults to `false`.
- **`[vN.routes]`** — the routes for version `N`. The path is written **without** any version segment; the runtime composes `/vN/` when mounting (path mode) or selects `N` from a header (header mode).
- **`[vN]`** — optional per-version lifecycle metadata: `deprecated` (bool), `sunset` (ISO date), `defaultIfMissing` (bool).

### Method binding — auto-suffix and explicit override

A bind key under `[vN.routes]` resolves to a slice method by appending the version: key `get` under `[v2.routes]` binds to method **`getV2`**, and `get` under `[v1.routes]` binds to **`getV1`**. The two versions are distinct Java methods on the interface:

```java
@Slice
public interface OrderService {
    Promise<GetResponse> getV1(GetRequest request);

    Promise<GetResponse> getV2(GetRequest request);
    Promise<UpsertResponse> upsertV2(UpsertRequest request);
}
```

To bind a key to a differently named method, use the inline-table form with an explicit `method` field (it overrides the `V{N}` suffix):

```toml
[v2.routes]
get = { route = "GET /{id:Long}", method = "fetchOrderV2" }
```

### Per-version media types

The inline-table `produces`/`consumes` fields work inside `[vN.routes]` exactly as in a flat block, so different versions can diverge in content type:

```toml
[v1.routes]
export = { route = "GET /export/{id:Long}", produces = "application/json" }

[v2.routes]
export = { route = "GET /export/{id:Long}", produces = "text/csv" }
```

Here `exportV1` returns JSON and `exportV2` returns CSV (so `exportV2` must return `String` or `byte[]` per the strict rule above).

### Back-compatibility and the schema-mixing error

A flat `[routes]` slice (no `[api]` section) stays **unversioned** — nothing changes for existing slices.

A slice must be *either* unversioned *or* versioned, never both. Mixing a flat `[routes]` block with `[vN.routes]` blocks is a build error:

```
Route configuration mixes a flat [routes] block with versioned [vN.routes] blocks. A slice must be either unversioned ([routes] + prefix) or versioned ([vN.routes] + [api] prefix), not both.
```

## Detection mode (operator)

How the served version is chosen from an incoming request is a **deploy-time, cluster-level** decision set in the node's `aether.toml`, under `[app-http]`. It is not part of the slice's `routes.toml`. (A per-slice override is a documented follow-up; cluster-level is the current scope.)

```toml
[app-http]
api_versioning_detection = "path"     # "path" (default) or "header"
api_version_header = "API-Version"     # header name used in header mode; default "API-Version"
```

- **`api_versioning_detection`** — `"path"` (also accepts `url`/`uri`) or `"header"` (also accepts `headers`). Defaults to **path** when unset or unrecognized.
- **`api_version_header`** — the request header carrying the version in header mode. Defaults to **`API-Version`**.

### Path mode (default)

The version travels in the URL. A versioned slice with `prefix = "/api/orders"` mounts each version at `{prefix}/v{N}/{path}`:

```
GET /api/orders/v1/42
GET /api/orders/v2/42
```

### Header mode

Routes mount at the bare `{prefix}/{path}` and the version comes from the request header:

```
GET /api/orders/42
API-Version: 2
```

The version-selection policy when running in header mode:

| Situation | Result |
|-----------|--------|
| Header present, names an available version | that version is served |
| Header present, names an unknown version | request rejected (unknown version) |
| Header absent, `requireVersionHeader = true` | **HTTP 400** — `Missing required version header: API-Version` |
| Header absent, a version declares `defaultIfMissing = true` | that default version is served |
| Header absent, no `defaultIfMissing` | the **highest** available version is served (latest-wins) |

Set `requireVersionHeader = true` in the slice's `[api]` block to force clients to be explicit:

```toml
[api]
prefix = "/api/strict"
requireVersionHeader = true

[v1.routes]
get = "GET /{id:Long}"

[v2.routes]
get = "GET /{id:Long}"
```

(Taken verbatim from the `strict` e2e fixture. In header mode a request to `/api/strict/42` with no `API-Version` header returns 400.)

## Deprecation lifecycle

Mark a version deprecated, and optionally give it a sunset date, in its `[vN]` block:

```toml
[v1]
deprecated = true
sunset = "2026-12-31"

[v2]
defaultIfMissing = true
```

When a request is served by a deprecated or sunsetting version, the runtime adds response headers so clients can react without you changing the response body:

- **`Deprecation: true`** — emitted when the served version is marked `deprecated`.
- **`Sunset: <RFC 1123 date>`** — emitted when the served version has a `sunset` date (e.g. `Sunset: Thu, 31 Dec 2026 00:00:00 GMT`). A bare `yyyy-MM-dd` is interpreted as start-of-day UTC.
- **`Link: <{prefix}/v{L}/{path}>; rel="successor-version"`** — points at the highest non-deprecated version `L` greater than the one served, when one exists.

### Monitoring deprecated usage

Three Micrometer counters let operators watch version usage and the deprecation funnel:

| Metric | Tags | Incremented when |
|--------|------|------------------|
| `http.requests.versioned` | `slice`, `version`, `method`, `status` | every request served by a versioned slice |
| `api.versioning.deprecated.requests` | `slice`, `version` | a request is served by a **deprecated** version |
| `api.versioning.missing.header` | `slice` | a header-mode request arrives with no version header |

`api.versioning.deprecated.requests` trending toward zero is the signal that a version is safe to retire; a non-trivial `api.versioning.missing.header` rate before flipping on `requireVersionHeader` warns you which clients still need to start sending the header.

### Inspecting deployed versions

To see the versioned slices a node currently hosts and their lifecycle state, query the introspection endpoint or its CLI wrapper:

```bash
aether versions
```

```json
{
  "slices": [
    {
      "slice": "org.example:orders:1.0.0",
      "apiPrefix": "/api/orders",
      "requireVersionHeader": false,
      "defaultVersion": 2,
      "versions": [
        { "version": 1, "deprecated": true, "sunset": "2026-12-31", "defaultIfMissing": false },
        { "version": 2, "deprecated": false, "defaultIfMissing": true }
      ]
    }
  ]
}
```

This is the same data available over HTTP at [`GET /api/versions`](../reference/management-api.md#get-apiversions); see also the [`versions` CLI command](../reference/cli.md#versions). The `defaultVersion`/`sunset` fields are omitted when not applicable.

## Migrating an existing slice

### Promoting an unversioned slice to versioned

1. Make sure each method that will participate in versioning has a version suffix, or add an explicit `method` override. Renaming `get` → `getV1` is the typical first step.
2. Replace the flat `[routes]` block with `[api]` + `[vN.routes]`:

   Before:
   ```toml
   prefix = "/api/orders"

   [routes]
   get = "GET /{id:Long}"
   ```

   After:
   ```toml
   [api]
   prefix = "/api/orders"

   [v1.routes]
   get = "GET /{id:Long}"
   ```
3. Add new versions as `[v2.routes]`, `[v3.routes]`, … and mark older ones `deprecated`/`sunset` as they age out.

### The URL-break trade-off

Promoting to a versioned slice in **path mode** changes the served URLs, because the version segment is now mounted automatically. If you previously published `/api/v1/orders/42` by baking `v1` into a manual `prefix`, the auto-mount form is `/api/orders/v1/42` — the version segment moves from before the resource to after the prefix. This is a deliberate, breaking URL change for any client with hard-coded paths.

Options to manage the break:

- **Coordinate the cutover** — treat it as a normal breaking API change, announce the new URL shape, and migrate clients.
- **Use header mode** — in header mode routes mount at the bare `{prefix}/{path}`, so the on-the-wire path does not gain a `/vN/` segment; the version moves entirely into the `API-Version` header. This avoids the path break at the cost of requiring clients to send the header.

Pick the detection mode before you publish v2, since switching modes later is itself a URL/contract change.

## See Also

- [Resource Reference](resource-reference.md) — all Aether resource types and route DSL
- [Slice Patterns](slice-patterns.md) — structural patterns, error modeling, routing
- [Management API — `GET /api/versions`](../reference/management-api.md#get-apiversions) — version introspection endpoint
- [CLI Reference — `versions`](../reference/cli.md#versions) — CLI wrapper for version introspection
