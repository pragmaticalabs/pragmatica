# Catalog Slice — media types + API versioning showcase

This is **one** deployable slice that runs **two API versions at the same time** (`v1`
deprecated, `v2` current) and serves **multiple media types** (JSON, `text/csv`,
`application/octet-stream`). It is living documentation for two rc2 platform features:

- **#339 media types** — `produces` / `consumes` inline-table routes in `routes.toml`.
- **#198 API versioning** — `[api]` / `[vN.routes]` / `[vN]` blocks; one artifact, the client
  picks the version.

It is intentionally **resource-free**: catalog data is seeded in-memory (the nested `SEED` list in
`Catalog`). A real
slice would persist via `@PgSql` / KV and emit `@Notify` events; that is dropped here so the
example stays focused on routing, media types and versioning. `ItemV2` is the storage superset —
`v1` responses are projected from it (single source of truth, no dual store).

> **Single-file slice:** everything the slice needs at runtime — request/response records, the
> `CatalogError` failures, the seed data and the implementation — is nested inside the `Catalog`
> `@Slice` interface. The slice packager walks only the `@Slice` type's class nest when building
> the deploy envelope, so a sibling top-level helper would be silently omitted and fail with
> `NoClassDefFoundError` on the node (build + unit tests run against the full classpath and would
> not catch it).

## Two kinds of "versioning" — don't confuse them

- **API versioning (this example):** ONE artifact declares `v1` + `v2`; the **client** chooses
  which version to call. Both shapes are live simultaneously.
- **Deployment versioning (`url-shortener` / `url-shortener-v2`):** TWO artifacts the **cluster**
  swaps during a rolling deploy. Only one is live at a time.

## Routes

`[api] prefix = "/api/catalog"`

| Version | Key | Method | Produces / Consumes | Maps to |
|---------|-----|--------|---------------------|---------|
| v1 (deprecated) | `list` | `GET /items` | JSON | `listV1` |
| v1 (deprecated) | `get` | `GET /items/{id:Long}` | JSON | `getV1` |
| v2 (current) | `list` | `GET /items` | JSON | `listV2` |
| v2 (current) | `get` | `GET /items/{id:Long}` | JSON | `getV2` |
| v2 (current) | `exportCsv` | `GET /items.csv` | produces `text/csv` | `exportCsvV2` |
| v2 (current) | `image` | `GET /items/{id:Long}/image` | produces `application/octet-stream` | `imageV2` |
| v2 (current) | `importCsv` | `POST /import` | consumes `text/csv` | `importCsvV2` |

`v1` items carry `id, name, priceCents`. `v2` items add `currency` and `tags`.

The bind-key + `V{N}` rule is what maps a `routes.toml` key to a Java method: key `get` under
`[v2.routes]` → method `getV2`.

## Path mode vs header mode

`aether.toml` flips the whole cluster between two version-detection modes with a single line:

```toml
[app-http]
api_versioning_detection = "path"     # or "header"
api_version_header = "API-Version"
```

**Path mode** — the version is a path segment:

```bash
curl http://localhost:8070/api/catalog/v1/items
curl http://localhost:8070/api/catalog/v2/items
```

**Header mode** — the version is a request header (the path drops the `vN` segment; the header value is the bare version number):

```bash
curl -H 'API-Version: 1' http://localhost:8070/api/catalog/items
curl -H 'API-Version: 2' http://localhost:8070/api/catalog/items
```

Because `[v2] defaultIfMissing = true`, a request with no version resolves to `v2`.

## Deprecation

`v1` is declared `deprecated = true` with `sunset = "2026-12-31"`. Responses to `v1` routes carry:

- `Deprecation: true`
- `Sunset: <date>`
- `Link: <successor>; rel="successor-version"`

`GET /api/versions` (on the **management** port, `5150` — not the app port) lists the declared versions and their deprecation status.

## curl examples (path mode, app port 8070)

```bash
# v1 — basic shape (deprecated; note the Deprecation/Sunset headers)
curl -i http://localhost:8070/api/catalog/v1/items
curl    http://localhost:8070/api/catalog/v1/items/1

# v2 — rich shape (currency + tags)
curl http://localhost:8070/api/catalog/v2/items
curl http://localhost:8070/api/catalog/v2/items/1

# v2 — CSV export (produces text/csv)
curl http://localhost:8070/api/catalog/v2/items.csv

# v2 — binary passthrough (produces application/octet-stream)
curl http://localhost:8070/api/catalog/v2/items/1/image -o img.bin

# v2 — CSV import (consumes text/csv)
printf 'id,name,priceCents,currency,tags\n7,Desk Lamp,2500,USD,lighting|office\n' > items.csv
curl -XPOST -H 'Content-Type: text/csv' --data-binary @items.csv \
     http://localhost:8070/api/catalog/v2/import

# Declared versions (management port 5150, not the app port)
curl http://localhost:5150/api/versions
```

## Build and run

```bash
# Build the slice (from repo root)
env -u HCLOUD_TOKEN mvn -pl examples/catalog install -DskipTests

# Run the unit tests
env -u HCLOUD_TOKEN mvn -pl examples/catalog test

# Run on a local 5-node Forge cluster (builds the slice + Forge jar, deploys the blueprint)
./run-forge.sh              # add --skip-build to start without rebuilding
```

`run-forge.sh` deploys blueprint coordinate `org.pragmatica.aether.example:catalog:<version>:blueprint`
onto a single-JVM 5-node cluster (see `forge.toml`) and prints the curl commands above.

## See also

See the full feature reference: `aether/docs/slice-developers/api-versioning-and-media-types.md`.
