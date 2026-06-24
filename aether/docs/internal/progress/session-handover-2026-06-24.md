# Session Handover — 2026-06-24

**Branch:** `release-1.0.0-rc2` · **HEAD `017e2cb05`** · **pushed**; candidate tag `v1.0.0-rc2-candidate` moved to `18d557498` (force-pushed → CI rebuilds candidate); **2 docs PRs merged** (#340 security-subsystem spec, #341 placement-aware-hydration spec). Massive session: the **HTTP stack unification + #339 media types + #198 API versioning epic landed end-to-end** (10 commits), each phase independently re-verified in-JVM. Remaining = the live-cluster tail (S19/S20 harness fix + cloud 15/15).

---

## ⚡ TL;DR
- User decision (2026-06-23): **full #198 + #339 in rc2**, on a **unified HTTP foundation**, **foundation-first**, gated sub-phases. Design doc: [`http-stack-unification-design.md`](http-stack-unification-design.md) + [`media-type-versioning-design-discussion.md`](media-type-versioning-design-discussion.md). Epic memory: `[[HTTP stack unification + #339/#198 epic]]`.
- **Phase A (foundation) DONE** — new `integrations/net/http-types` module unifies the HTTP value types + one category-driven `ResponseSerializer` (binary passthrough) + `HttpRequest` base; the two duplicated HTTP vocabularies + `server.RequestContext` collapsed.
- **Phase B (#339) DONE** — `produces`/`consumes` inline-table media types; codegen emits `.as(produces)` + body binding per `consumes`; strict compile-time type check; **binary passthrough proven**.
- **Phase C (#198) DONE** — `[api]`/`[vN.routes]`/`[vN]` schema + parser + validation; **path AND header mode** (deploy-either-way per §6.4); deprecation lifecycle headers; 3 metrics; `GET /api/versions` endpoint (REST+CLI+docs triad); slice-dev guide.
- **Envelope `1001→1004`** across the epic; `SliceManifest.SUPPORTED_ENVELOPE_VERSIONS = {1000..1004}`.
- **All verified in-JVM** (full-reactor compile ×N independent + unit + Forge behavioral). **No cloud run yet.**

---

## ✅ Commits (oldest→newest, all on `release-1.0.0-rc2`)
| Commit | What |
|---|---|
| `43df095bb` | docs: HTTP stack unification design |
| `76a2a6b91` | **Phase A1+A1e** — unify HTTP value types + response serialization into `http-types` |
| `798b202fd` | **Phase A2** — unify request-context into `HttpRequest` base + `routing.RequestContext` extension |
| `b18bf284f` | fix — `byte[]/byte/short/char` are built-in codec types (binary RPC passthrough) |
| `130d5c1ee` | **Phase B (#339)** — slice routes support `produces`/`consumes` media types |
| `192b75d82` | **C1+C2 (#198)** — schema, parser, validation, path-mode routing |
| `9943bc88c` | **C3a** — carry API version as route metadata + per-slice version registry (deploy-either-way seam) |
| `380b12940` | **C3b** — header-mode versioning + deploy-either-way detection config |
| `3f15a6664` | **C4** — deprecation lifecycle headers + versioning metrics + `/api/versions` endpoint |
| `d1b748826` | **C5** — slice-developer guide (versioning + media types) |

---

## 🏗️ What was built (architecture)

**Foundation (`integrations/net/http-types`, deps `core` only, Netty-free):** unified `ContentType`/`ContentCategory{JSON,TEXT,HTML,XML,BINARY,FORM_URLENCODED,MULTIPART}`/`CommonContentType` (rich constants incl. YAML) + `HttpMethod`/`HttpStatus` (superset) + `HttpError`/`ProblemDetail` + `JsonCodec` (byte[] seam) + `ResponseSerializer` (category dispatch, BINARY verbatim) + `HttpRequest` base + `Headers`/`QueryParams`. `http.server.RequestContext` deleted; `routing.RequestContext extends HttpRequest` (base+extension forced by a Route→http-routing dependency cycle). The 2 `@Codec` DTOs (`HttpRequestContext`/`HttpResponseData`) intentionally KEPT (serializable slice payloads). http-client's `HttpError`→`HttpClientError` (FQCN de-collision). 3 scattered serialization sites unified through `ResponseSerializer`.

**#339:** inline-table `{ route=…, produces=…, consumes=…, security=… }` (bare string/array still valid → JSON in/out); `RouteSourceGenerator` emits `.as(produces)` + `withStringBody/withByteBody/withMultipartBody` per `consumes`; D3 strict check via unit-testable `MediaTypeTypeChecker`.

**#198 (deploy-either-way):** routes carry `version()` metadata + un-versioned path; `RouteSource.versionRegistry()`→`SliceVersionRegistry`. Detection mode (`api_versioning_detection` = path|header, `api_version_header`) is a **deploy-time, cluster-level** config; composition happens in `HttpRoutePublisher` (fed to BOTH `SliceRouter` and the wire `RouteMetadataExtractor` so they agree). Header mode selects version via the pure `VersionSelector` (§7 policy). Deprecation `VersionResponseHeaders` (Deprecation/Sunset/Link). Metrics: `http.requests.versioned`, `api.versioning.deprecated.requests`, `api.versioning.missing.header`. `GET /api/versions` + `aether versions`.

**Key design calls:** D1 unify-serialization (category model already existed); D2 produces/consumes single-each; D3 strict; D8 `getV{N}` auto-suffix + explicit `method`; D9 media travels with version; **header mode B-choice** = full deploy-either-way (user picked B over defer-to-rc3). Per-slice detection-mode override = documented follow-up.

---

## 🧪 Verification (all in-JVM, no cloud)
- Full-reactor `env -u HCLOUD_TOKEN mvn -q install -DskipTests` → SUCCESS (re-confirmed independently after every phase).
- Unit highlights: `ResponseSerializerTest`, `MediaTypeTypeCheckerTest` 16, `VersionSchemaValidatorTest` 15, `VersionSelectorTest` 11, `VersionResponseHeadersTest` 11; slice-processor 223; http-routing ~280; node 609.
- Forge behavioral (the real HTTP path): `SliceMediaTypeTest` 2/2 (binary verbatim + text/csv), `SliceVersioningTest` 3/3 (path), `SliceVersioningHeaderModeTest` 6/6 (header), `SliceVersionLifecycleTest` 3/3 (deprecation headers + `/api/versions`).

---

## 🎯 NEXT-SESSION (the live-cluster tail — needs real clusters)
1. **Push + candidate tag: DONE this session** — `release-1.0.0-rc2` pushed (`017e2cb05`), `v1.0.0-rc2-candidate` moved + force-pushed (CI rebuilds the candidate image). Also merged 2 docs-only PRs (#340 security-subsystem spec → `aether/docs/specs/security-subsystem-spec.md`; #341 placement-aware-hydration spec → `aether/docs/specs/placement-aware-stream-hydration-spec.md`) and annotated the relevant tickets (#265, #319, #269, #206, #253) to reflect the specs landed. (Their CI was red on the stale pre-push base + the known forge cold-start flake — docs-only can't cause build/test failures; the pushed branch is in-JVM-verified.)
2. **S19/S20 self-drain harness fix** (the one remaining cloud failure from 2026-06-23) — `suites/02-chaos/test-self-drain-quorum-loss.sh`: detect self-drain via `/api/cluster/membership` (not `/api/events` which needs quorum, nor VM-resolves which is the wrong layer). Then diagnose S20 recovery timeout on a LIVE cluster before patching. See [[project_selfdrain_suspect_edge_fix]] + #210.
3. **Cloud 15/15** (runs LOCALLY: Mac has Java 25 + `HCLOUD_TOKEN` + pg-env) — container + JVM, cluster-A + cluster-B. This is also the epic's REAL regression gate (the 00-smoke/06-deployment/etc. suites exercise slice deployment + HTTP serving, which the foundation rewrote). Discipline: `--skip-teardown` + cluster-scoped reap from the FIRST run, separate A/B, `hcloud` for state, preserve test-PG. See [[project_cloud_acceptance_reaper_discipline]]. Test-PG VM `aether-test-pg`/88.198.147.80 left running.

## 📌 Discipline / follow-ups
- **Build safety:** `mvn install` (not just `verify`) fires `HetznerCloudIT` with `HCLOUD_TOKEN` set → ALWAYS `env -u HCLOUD_TOKEN` + `-DskipTests`. Forge tests run under failsafe → `integration-test -Dit.test=…`, NEVER `verify`. See [[feedback_build_runner_only_buildsh]].
- Minor doc follow-up: surface the 3 versioning metrics in `aether/docs/reference/management-api.md` (currently only in code + the new guide).
- aether/** = BSL-1.1; integrations/** = Apache-2.0. Single-line commits, no trailers.
