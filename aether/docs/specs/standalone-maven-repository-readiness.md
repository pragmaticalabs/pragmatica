# Standalone Maven repository — component readiness

| | |
|---|---|
| Status | **Assessment for a proposed rc5 product; nothing here is implemented.** The design is in the [upgrade specification](standalone-maven-repository-spec.md). |
| Read point | `release-1.0.0-rc4` at `564d2d3df`. Source inspection, existing-test inspection and tracker review only; no builds or tests were run. Every code claim is checked in the [verification table](#appendix-claim-verification-at-564d2d3df). |
| In-flight work | Open pull requests are not treated as delivered. Where one changes a gap below, the text names it and says "pending merge": #1821 (#1778, write-once internal store), #1820 (#1818, DHT copies under concurrent departure), #1823 (#1777 track 2, DHT catch-up gate). |

## Decision and scope

The product is feasible using Aether and AHSE, but the current internal artifact service is not yet a general Maven repository. The largest prerequisite is a recoverable publication/catalog contract across content, metadata and references. Protocol generalization is the next major component change.

Confirmed by the owner, and settled: a fully functional Maven repository, including releases and SNAPSHOTs; configurable upstream synchronization that can be disabled completely; reuse of the existing desired-node-count provisioning/join/rebalancing; AHSE retained and extended as one module; a new product UI; no consensus journal/WAL. Implementation is to be tracked under an rc5 epic.

Working interpretation of full Maven functionality: hosted repositories, on-demand upstream proxying, configured mirroring/prefetch, and a grouped read endpoint; standard artifact files and metadata; credentials/access policies; browse/search, retention and recovery. Exact defaults for release replacement, retention, upstream precedence, cache freshness and capacity remain product decisions. Full Maven compatibility does not by itself require other package formats, vulnerability scanning or a release-approval workflow.

## Component assessment

| Component | Reuse | Required preparation |
|---|---|---|
| Aether infrastructure lifecycle | Existing desired-count operation, provisioning, membership and rebalancing | Product assembly/configuration and workload validation; close applicable data-loss issues rather than build another scaling system |
| AHSE | Content-addressed blocks, local forced writes, tiering, chunked content abstraction, lifecycle/GC machinery, append-log seam (`openLog`/`seal`) | Durable references/metadata, authoritative shared-reference handling, bounded streaming APIs and an explicit acknowledged durability contract |
| Artifact publication/catalog | Existing per-file storage and integrity checks as implementation material; #1821's write-once binding and grow-only version set as precedents | Repository namespaces, exact Maven paths, durable concurrent publication and enumeration; internal slice-store policy cannot define this product |
| Maven HTTP | Handler and route framework | General version/path identity, complete metadata/SNAPSHOT handling, sidecars, HEAD and consistent failure responses |
| Upstream retrieval | HTTP client, credentials/checksum machinery and artifact-cache experience | Server-side generic resource fetching, mutable metadata refresh, request coalescing, negative-cache rules, configured sync and sealed policy |
| Access control | Existing security contexts, TLS, API-key and JWT machinery | Build-client-friendly credentials, repository/path-scoped permissions, publisher role independent of cluster operator |
| Recovery/retention | AHSE snapshots, application logs, storage adoption and control-state backup components | Consistent repository backup/export, restore of the entire catalog/content closure, safe cleanup and capacity accounting |
| Product operations | Backend lifecycle/metrics capabilities | New API/CLI/UI for repository tasks; packaging and bootstrap that work offline |

## 1. Separate general Maven identity from Aether slice identity

The current handler parses paths through `org.pragmatica.aether.artifact.Version`. It requires three or four dot-separated components with numeric major/minor/patch; a fourth component becomes the qualifier. So a normal Maven version such as `1.0` is rejected, and so is `1.0.0-rc.1`, because its dot splits it into four parts whose third (`0-rc`) is not numeric. The version is also re-rendered rather than kept: the qualifier is always written after a dash, so `1.2.3.Final` and `1.2.3-Final` name the same stored key. `VersionOrder` explicitly documents that it implements only part of Maven ordering. These are reasonable constraints for a slice-version model but not for this repository.

Use repository-owned types for a repository identifier and a validated, exact relative Maven resource path. Derive coordinates where meaningful for browsing and policy, without reconstructing the stored path from Aether's normalized version representation. Metadata files need identities too, even though they are not artifacts. Preserve arbitrary valid Maven version text and multi-part extensions.

The current `extractExtension` keeps only the suffix after the last dot. That loses the identity of extensions such as `tar.gz`. It also merges sidecars: only `.md5` and `.sha1` are recognized as checksums, so `lib-1.0.0.jar.asc` and `lib-1.0.0.pom.asc` both become the unclassified file with extension `asc`, and the same holds for `.sha256` and `.sha512`. At the read point the second such upload answers `already-present` and is discarded. Under #1821 (pending merge) the second, having different bytes, is refused with `409`, so a signed or SHA-256/SHA-512-publishing client cannot complete a deploy to the internal store (Gradle publishes SHA-256 and SHA-512 sidecars by default `[unverified: no real client run]`). Classifier/extension parsing should not become the storage key's only source of truth. Apache's [layout definition](https://maven.apache.org/repositories/layout.html) provides the conformance baseline.

Use a distinct repository namespace in all content references, catalog keys, permissions, caches and upstream configuration. Two hosted repositories may contain different bytes at the same Maven path; content digests may still deduplicate in AHSE according to policy.

## 2. Make publication durable and concurrent

Current publication stores blocks through AHSE, then writes file metadata, versions and file-list keys through the DHT. Production DHT construction still uses `MemoryStorageEngine`, and none of #1820, #1821 or #1823 changes that. AHSE local block writes force the file and directory before success, but only on a node whose disk tier is mounted: the synthesized `artifacts` instance falls back to memory plus DHT when the default disk path is unavailable, and then has no durable tier at all. Durable payload bytes alone cannot reconstruct the coordinate/path mapping either. AHSE's own reference/lifecycle metadata is assembled with `InMemoryMetadataStore` and recovered from periodic snapshots, leaving a separate metadata-durability problem.

The minimum publication operation should have this contract:

| Property | Contract to establish |
|---|---|
| Trigger/input | Authorized PUT of bytes to a repository resource path, with applicable repository policy |
| Successful output | Exact content digest, length and durable catalog revision; repeatable result for an accepted identical retry |
| Failures | Invalid path, denied publication, conflicting immutable content, invalid supplied checksum, capacity exhausted, unavailable required replicas/storage, or unresolved request outcome |
| Ordering | Receive/stage bytes → compute/verify digest → satisfy content durability → guarded catalog publication → acknowledge |
| Concurrency | One explicit serialization/CAS/fencing mechanism controls conflicts and metadata merges; process-local locking is insufficient |
| Recovery | Readers see a complete old or new resource; interrupted uploads are recoverable/reclaimable; a timeout after commit is resolved through an idempotent retry |

The catalog should be durable application data: path-to-manifest mappings, checksums/length, publication revision, origin and retention state. Reuse an existing fenced, replicated application-log/entity substrate where its contract fits; a full saga/workflow engine is not required. AHSE owns block/reference persistence. Define their handoff so a block cannot be reclaimed between durable staging and durable publication. Avoid two independent authoritative catalogs or competing GC implementations.

File visibility and artifact-set visibility are different. Standard Maven deployment uploads multiple resources separately. Each accepted PUT needs its own guarantee; atomic staging/promotion of a whole release is a separate optional product operation.

Concrete gaps at the read point, and what the in-flight work changes:

- **Lost list updates.** `ArtifactStore.rewriteList` documents a get-then-put race that can lose concurrent file/version additions. *Addressed for the internal store's version list by #1821 (pending merge):* the versions of each artifact move into consensus KV as a grow-only set, so concurrent publishes cannot lose one another's version. The per-version file list stays in the DHT. #1821 sequences its rewrites on one node only and documents that two nodes can still overwrite each other; it argues the consequence is bounded, because the file list only dates the version for the archive-retention check.
- **Existence without comparison.** `MavenProtocolHandler.handlePutArtifact` answers `already-present` when metadata exists without comparing the new bytes, and keeps the stored bytes. The check-then-write also has no atomic protection. *Addressed for the internal store by #1821 (pending merge):* identical content (size, MD5, SHA-1, SHA-256) is idempotent and different content is refused with `409`. Concurrent first writes are decided in consensus by a first-committed-digest-wins binding, and the loser uploads nothing.
- **Multi-key updates.** Catalog updates span several keys. Failure between them needs authoritative state plus reconciliation/rebuild behavior, not only retries of individual writes. *Partly addressed by #1821:* an identical retry re-asserts the file and version registrations, so an interrupted first deploy converges if the client retries. Nothing reconciles it otherwise.
- **Corruption read as absence.** `ArtifactStore.metadata` collapses unparseable metadata into absence, and the PUT path uses it as its existence check, so corruption could authorize a replacement write. The GET path already distinguishes the two (`MetadataUnparseable`). *Addressed for deploy by #1821:* deploy reads the raw bytes and refuses unparseable metadata. `metadata()` itself still collapses for its other callers.

#1778, implemented by #1821, is the internal store's correctness work. Reuse its mechanisms where appropriate. Its deliberate rejection of SNAPSHOTs, its archive-instead-of-delete policy and its version cap remain internal-store policy; they are not imposed on the standalone product. Its consensus-KV binding is sized for slice publication; the specification's §1.1 explains why the standalone catalog does not route every path through consensus KV.

## 3. Complete Maven metadata and SNAPSHOT behavior

Current `handlePutParsed` returns success for `maven-metadata.xml` and `.md5`/`.sha1` uploads without retaining their bodies (unchanged by #1821). Metadata GET generates only an artifact-level version list, with `lastUpdated` set to the time of the GET. The metadata path parser treats the last two segments before `maven-metadata.xml` as group and artifact, so group-level (plugin) and version-level (SNAPSHOT) metadata paths are not recognized as such. Timestamped SNAPSHOT filenames are parsed back to the base directory version and classifier/extension, so distinct builds share the same internal file identity. This cannot preserve multiple timestamped builds or implement normal mutable snapshot discovery. (#1821 refuses SNAPSHOT versions in the internal store, which removes the collision there by removing SNAPSHOTs; the standalone product needs the opposite.)

Implement the three metadata contexts, including overlapping group/artifact paths: plugin-prefix discovery, version discovery, and per-base-version snapshot resolution. Timestamped files retain distinct immutable paths; the relevant metadata can advance to newer builds. Concurrent metadata publishers need a defined merge/publication policy. Apache documents these contexts in [Maven Metadata](https://maven.apache.org/repositories/metadata.html).

For hosted content, validate and reconcile uploaded metadata against repository state without acknowledging discarded data. For proxied content, preserve upstream resources and freshness information; grouped repositories require explicit metadata merging and source precedence. Serve metadata checksums from the exact returned representation. Stable modification revisions should drive cache validators and `lastUpdated`, not the current GET time.

Preserve POMs, parent/BOM-only publications, classifiers, plugins, arbitrary packaging files and detached signatures. Support common checksum sidecars including SHA-256/SHA-512, compute authoritative checksums while receiving content, and define how inconsistent supplied sidecars are rejected. Preserve signatures byte-for-byte; signature trust verification is a separate policy.

Use Maven-compatible ordering only where the server actually selects or generates ordered versions. Avoid broadening Aether's slice `Version` merely to accommodate this product. [Maven's version specification](https://maven.apache.org/pom.html#Version_Order_Specification) is the comparison baseline.

## 4. Extend AHSE and HTTP for bounded transfers

The current artifact API accepts and returns whole `byte[]` objects; it splits uploads into additional byte arrays and reassembles downloads. The HTTP pipeline uses `HttpObjectAggregator`. A configured body-size ceiling and eight in-flight storage chunks bound individual operations partially, but do not make memory consumption independent of artifact size or concurrent clients.

Add streaming/spooled upload and streamed download interfaces through the HTTP and AHSE content paths, with bounded buffering, cancellation, incremental checksums, size limits and temporary-content cleanup. Reuse AHSE's chunk/manifest model rather than create a second repository storage engine. Note that there are already two chunkers: `ArtifactStore` splits content itself into 64 KiB chunks, while the generic `ContentStore` defaults to 4 MiB. Decide chunk sizing/batching with measurements: 64 KiB chunks with two forced writes each on a mounted disk tier may impose significant per-file I/O overhead, but this inspection provides no benchmark quantifying it.

Provide HEAD without materializing the whole artifact. Add appropriate content length, cache validators, conditional retrieval and byte-range support for large-file clients. These are product HTTP capabilities; the inspected Maven route handles GET and PUT/POST only. Distinguish absent content from corruption and temporary unavailability on artifact, metadata and checksum paths alike. Current checksum GET collapses every failure to not-found (#1821 adds `410` for an archived file and otherwise keeps the collapse).

Capacity behavior is part of publication correctness: reserve/bound temporary upload space, reject when the selected durability policy cannot be met, and report logical bytes separately from deduplicated physical/replicated bytes. Metrics currently derived from local process counters are not an authoritative repository inventory.

## 5. Add server-side upstream policies

The existing `RemoteRepository` is a slice loader: it resolves a JAR, checks checksums and caches it in the local Maven directory. This is useful code to draw from, but not a general server-side Maven proxy. In particular, it does not provide the complete resource/metadata lifecycle, and its cache does not refresh already-cached coordinates: a cached JAR is only re-checked against its checksum sidecar (#1746).

Required repository behavior:

- Hosted, proxy and grouped read repositories with explicit source precedence and namespace ownership.
- On-demand fetch of the exact requested resource, with bounded transfer, checksum checks, origin identity and publication through the same durable path as uploads.
- Coalesced concurrent misses, bounded retries/timeouts and negative caching only for genuine absence. A temporary upstream failure or authorization failure is not a persistent miss.
- Distinct freshness policies for immutable files and mutable metadata/SNAPSHOT discovery; conditional upstream requests where supported.
- Scheduled selective synchronization with durable progress, retry and overlap control. An arbitrary Maven endpoint need not expose a complete inventory: mirror scope requires a manifest, enumerable source or explicitly selected coordinates.
- Import/export and prefetch bundles suitable for sealed environments. Dependency-closure preparation must account for POM parents, BOMs, build plugins and relevant build profiles; validate bundles with real offline builds rather than promise universal closure from an ad hoc POM walk.
- A global disabled-upstream policy that gates every artifact fetch, refresh, mirror and retry path, including redirects. Local publication and internal cluster traffic remain available. Any remote backup destination is separately and explicitly configured; sealed operation must not require public services.

Keep credentials bound to their configured origin and enforce the same authorization/policy on grouped endpoints. Proposed default: private hosted namespaces do not fall through to public upstreams; confirm exact namespace/precedence defaults before implementation.

## 6. Establish enumeration, retention and full recovery

Listing is a data capability, not a UI-only task. #527 tracks the absent cluster-wide artifact index; the repository-wide listing route answers `501`, and `ArtifactStore` only lists versions of a known coordinate. A durable, enumerable publication catalog can supply both exact lookup and a rebuildable browse/search projection. Choose pagination and consistency deliberately; a UI should not scan every DHT node for every page. Search can lag if disclosed, while acknowledged publication/read guarantees remain explicit.

Deletion at the read point leaves content chunks unreleased because they can be shared across artifacts/nodes; #1821 replaces deletion with archiving and still releases no chunks. Local refcounts are not authoritative for shared DHT storage. #1133 calls for a cluster-wide reference index; integrate that with AHSE reference lifetime rather than adding product-local block deletion. Durable reference IDs and retry-safe mutations must protect shared files, imports, in-flight publications and backups. Retention first removes eligible logical references; physical reclamation follows only when the shared authority proves safety.

Product policy must cover snapshot build history, hosted releases, proxy eviction, archive visibility and quotas separately. Cache eviction must not remove hosted content merely because both deduplicate to the same bytes. A metadata update must not advertise a snapshot build already reclaimed by retention.

Existing control-KV backup and per-instance AHSE snapshots cover different facts. Neither alone is a complete repository backup. (After #1821, the control-KV backup carries the internal store's version sets and content bindings, while its bytes and per-file metadata stay in AHSE and the DHT.) Export/backup needs a catalog revision plus the reachable manifest/block closure, relevant policies/configuration and a documented credential/key restore process. Fence/pin that closure against GC until the backup completes. Restore must verify content hashes and rebuild derived indexes before serving complete results.

Preserve the storage-identity/adoption work in #1569/#1581, including old-writer fencing. The repository acceptance must specifically prove lookup and download after a whole-cluster restart with surviving volumes, not just that block files exist. Surviving total volume loss requires a separate backup/remote-storage envelope. Optional S3-compatible storage belongs in AHSE (#249/#1570); it is not mandatory for a sealed disk-backed first implementation.

## 7. Product assembly, credentials and UI

Reuse Aether infrastructure lifecycle intact: the UI/CLI changes desired node count, and existing provisioning, joining and rebalance perform the operation. Still validate artifact/catalog survival during scale-out, scale-in, overlapping departures and failed rebalance. Existing tickets #420/#1818/#1777/#1775 identify relevant data-plane work; #1820 (concurrent departures) and #1823 (new replicas refuse instead of answering "absent") are pending merge. Both leave stated residuals: a stale write a lagging replica accepted can still spread until #1777 track 3, and a catch-up round with no live serving source completes on a best-effort union. An existing control operation is not itself proof of repository-data durability under every transition.

Create a repository runtime assembly/default profile using the shared components. Keep application-specific Maven behavior out of the clustering and AHSE modules. Ship local UI assets, configuration validation, explicit persistent volume paths and a bootstrap path that works without downloading additional dependencies. Repository payloads and a high-volume catalog should not be pushed through the global consensus control plane merely because it is convenient.

Reuse TLS, identity and key validation, but introduce repository read/publish/admin permissions, namespace scoping, optional anonymous read and an audit trail of publication/policy changes. Under `security_mode` API_KEY or JWT, publication requires an Aether OPERATOR/ADMIN role; under `security_mode=NONE` or insecure dev mode it is accepted unauthenticated, with a warning. The API-key validator expects `X-API-Key`; the JWT validator expects a `Bearer` token. Offer a credential path that works conveniently with normal Maven/Gradle configuration, such as username/token credentials over TLS; do not require publishers to become cluster operators. Maven's [server settings](https://maven.apache.org/settings.html#servers) are the client UX baseline.

Build a new UI: repository configuration; artifact/version/file browsing; checksums/origin; sync state; retention and storage capacity; backup/restore status; credentials; and desired/current/ready node counts with provisioning/rebalance progress. It uses existing backend operations where applicable and has no dependency on the Aether dashboard's layout or navigation.

## Recommended implementation order

1. **Freeze the three boundary contracts:** generic repository resource identity; acknowledged publication/durability and AHSE-reference handoff; hosted/proxy/sealed policies. Define streaming interfaces now to avoid rebuilding endpoints later.
2. **Prove one durable hosted file end to end:** authorized upload, guarded publication, read through another node, identical/conflicting retries, owner failure and cold restart. Implement the minimum AHSE/catalog prerequisites this requires, building on the shared-component work tracked in #1570/#1569/#1133. Build a minimal inventory view from the real catalog.
3. **Complete hosted Maven compatibility:** releases and multiple timestamped snapshots, all metadata levels, sidecars/classifiers, concurrent publishers, real Maven and Gradle clients. This milestone is required before describing the product as a functional Maven repository.
4. **Complete upstream and grouped resolution:** on-demand proxy, refresh/negative-cache semantics, configured synchronization, offline bundle import/export and proof of zero upstream requests when disabled.
5. **Complete operations:** safe retention/GC, consistent backup/restore, new UI/CLI workflows, packaging and operator recovery. Basic authentication, byte limits and persistent-volume requirements enter the first write path, not this late phase.
6. **Validate the selling points:** large/concurrent transfers, metadata-heavy publishing, many small artifacts, node-count changes under load, storage pressure and recovery. Measure throughput and latency separately for payload reads and catalog mutations.

Each stage must leave a working end-to-end path. Do not wait for every unrelated Aether feature or for all of the AHSE roadmap. Remote tiers, advanced search and whole-release staging can follow their actual product need; correct durable publication, snapshots and client compatibility cannot.

## Upstream work to coordinate with

Tracker state checked on 2026-10-02; issue bodies can describe older implementations, and source takes precedence. In particular, #1570's statement that AHSE has no append primitive is stale: `StorageInstance` already exposes `openLog`/`seal`. #1570's metadata journaling item is still open.

| Work | Relevant tickets | Repository dependency |
|---|---|---|
| Durable AHSE metadata / remote wiring | [#1570](https://github.com/pragmaticalabs/pragmatica/issues/1570), [#249](https://github.com/pragmaticalabs/pragmatica/issues/249) | Durable references needed; remote tier conditional on deployment envelope |
| Storage identity / cold restart | [#1569](https://github.com/pragmaticalabs/pragmatica/issues/1569), [#1581](https://github.com/pragmaticalabs/pragmatica/issues/1581) | Repository-specific catalog/content recovery proof |
| DHT survival / admission | [#420](https://github.com/pragmaticalabs/pragmatica/issues/420), [#1818](https://github.com/pragmaticalabs/pragmatica/issues/1818), [#1777](https://github.com/pragmaticalabs/pragmatica/issues/1777), [#1775](https://github.com/pragmaticalabs/pragmatica/issues/1775) | Required to the extent repository authoritative data continues to use these paths. #1820 and #1823 pending merge; #1777 tracks 1 and 3 remain rc5 |
| Internal artifact correctness | [#1778](https://github.com/pragmaticalabs/pragmatica/issues/1778) (#1821 pending merge) | Reuse its atomicity/monotonicity mechanisms; its no-SNAPSHOT, archive and version-cap policies stay internal |
| Enumeration / reclamation | [#527](https://github.com/pragmaticalabs/pragmatica/issues/527), [#1133](https://github.com/pragmaticalabs/pragmatica/issues/1133) | Real catalog and shared reference lifetime; both product-critical |
| Cache / management endpoints | [#1746](https://github.com/pragmaticalabs/pragmatica/issues/1746), [#1102](https://github.com/pragmaticalabs/pragmatica/issues/1102) | Useful upstream repairs; neither replaces full proxy or product API work |

## Source entry points

- [ArtifactStore](../../resource/services/artifact-repo/src/main/java/org/pragmatica/aether/resource/artifact/ArtifactStore.java), [ArtifactFile](../../resource/services/artifact-repo/src/main/java/org/pragmatica/aether/resource/artifact/ArtifactFile.java), [MavenProtocolHandler](../../resource/services/artifact-repo/src/main/java/org/pragmatica/aether/resource/artifact/MavenProtocolHandler.java), [VersionOrder](../../resource/services/artifact-repo/src/main/java/org/pragmatica/aether/resource/artifact/VersionOrder.java), [slice Version](../../slice/src/main/java/org/pragmatica/aether/artifact/Version.java).
- [StorageInstance](../../../integrations/storage/src/main/java/org/pragmatica/storage/StorageInstance.java), [ContentStore](../../../integrations/storage/src/main/java/org/pragmatica/storage/ContentStore.java), [ContentStoreConfig](../../../integrations/storage/src/main/java/org/pragmatica/storage/ContentStoreConfig.java), [MetadataStore](../../../integrations/storage/src/main/java/org/pragmatica/storage/MetadataStore.java), [LocalDiskTier](../../../integrations/storage/src/main/java/org/pragmatica/storage/LocalDiskTier.java), [StorageTier durability contract](../../../integrations/storage/src/main/java/org/pragmatica/storage/StorageTier.java).
- [StorageFactory](../../node/src/main/java/org/pragmatica/aether/node/StorageFactory.java), [DHT tier](../../aether-storage/src/main/java/org/pragmatica/aether/storage/DhtStorageTier.java), [node assembly](../../node/src/main/java/org/pragmatica/aether/node/AetherNode.java), [KV backup](../../node/src/main/java/org/pragmatica/aether/node/backup/KvBackupService.java).
- [HTTP routes](../../node/src/main/java/org/pragmatica/aether/api/routes/MavenProtocolRoutes.java), [HTTP aggregation](../../../integrations/net/http-server/src/main/java/org/pragmatica/http/server/NettyHttpServer.java), [remote slice repository](../../slice/src/main/java/org/pragmatica/aether/slice/repository/maven/RemoteRepository.java), [API-key validation](../../node/src/main/java/org/pragmatica/aether/http/security/ApiKeySecurityValidator.java).

## Appendix: claim verification at `564d2d3df`

Each code claim in this document and in the specification, checked against source at the read point. "Confirmed" means the source says what the text says; "partial" means the text was narrowed or extended; "stale" means the statement was true of an earlier state of the tracker or source and no longer holds. Line numbers are at `564d2d3df`. The last column says what pending pull requests change.

| # | Claim | Verdict | Evidence | Pending change |
|---|---|---|---|---|
| 1 | `Version` needs 3–4 dot components, numeric major/minor/patch; `1.0` rejected | Confirmed, extended | `Version.java:28-32, 41-46, 63-66`; 4th part is the qualifier, rendered after `-` (`:83-87`), and keys use that rendering (`ArtifactStore.java:929-945`), so `1.2.3.Final` ≡ `1.2.3-Final`; `1.0.0-rc.1` rejected | none |
| 2 | `VersionOrder` implements only part of Maven ordering | Confirmed | `VersionOrder.java:13-20` | none |
| 3 | `extractExtension` keeps the last suffix only | Confirmed, extended | `MavenProtocolHandler.java:431-437`; only `.md5`/`.sha1` are checksum paths (`:353-361`); `.asc`/`.sha256`/`.sha512` of different files share one `ArtifactFile` (`ArtifactFile.java:37-41`) | #1821 keeps the parser; the collision becomes `409` |
| 4 | `handlePutParsed` discards metadata and checksum bodies | Confirmed | `MavenProtocolHandler.java:240-249` | unchanged in #1821 |
| 5 | Metadata GET generates only the artifact-level version list | Confirmed, extended | `MavenProtocolHandler.java:194-209, 374-391, 489-514`; `lastUpdated` is GET time (`:493`) | #1821 lists only stored, non-archived versions |
| 6 | Timestamped SNAPSHOT builds share one file identity | Confirmed | `MavenProtocolHandler.java:396-408, 439-487`; key has no timestamp (`ArtifactFile.java:37-45`) | #1821 refuses SNAPSHOT (`400`) |
| 7 | Publication writes blocks via AHSE, then metadata, versions and file list via DHT | Confirmed | `ArtifactStore.java:426-449, 667-681` | #1821: versions and content binding move to consensus KV |
| 8 | Production DHT uses `MemoryStorageEngine` | Confirmed | `AetherNode.java:641` | unchanged in #1820, #1821, #1823 |
| 9 | AHSE local block writes force file and directory | Partial | `LocalDiskTier.java:31-38, 245-246`; only `LocalDiskTier` is durable (`StorageTier.java:26-33`); the `artifacts` instance falls back to memory plus DHT when the disk path is unavailable (`StorageFactory.java:405-410`) | none |
| 10 | AHSE metadata is `InMemoryMetadataStore` plus periodic snapshots | Confirmed | `StorageFactory.java:1329-1335`, `StorageInstance.java:225` | none |
| 11 | `rewriteList` get-then-put race | Confirmed | `ArtifactStore.java:695-706` | #1821: versions fixed via consensus; file list still racy across nodes |
| 12 | `handlePutArtifact` answers already-present without comparing bytes | Confirmed | `MavenProtocolHandler.java:269-282` | #1821: compares, `409` on difference |
| 13 | Unparseable metadata collapses to absence on the publication path | Partial | `ArtifactStore.java:520-523` collapses; GET path distinguishes (`:504-512`) | #1821 deploy refuses it; `metadata()` still collapses |
| 14 | Whole `byte[]` API; `HttpObjectAggregator` | Confirmed | `MavenProtocolHandler.java:31-32`, `ArtifactStore.java:65-66`, `ContentStore.java:13-15`, `NettyHttpServer.java:248` | none |
| 15 | 64 KiB chunks; eight in-flight chunks | Confirmed | `ArtifactStore.java:266, 277` | none |
| 16 | `ContentStore` chunk size differs from `ArtifactStore`'s | Confirmed | `ContentStoreConfig.java:5` (4 MiB) | none |
| 17 | Maven route serves GET and PUT/POST only | Confirmed | `MavenProtocolRoutes.java:147-160` | #1821 adds DELETE (archive) |
| 18 | Checksum GET collapses failures to not-found | Confirmed | `MavenProtocolHandler.java:211-225` | #1821: `410` for archived, else still `404` |
| 19 | Publication requires OPERATOR/ADMIN | Partial | Only under API_KEY/JWT; NONE and dev mode accept unauthenticated (`MavenProtocolRoutes.java:66-77, 190-215`) | none |
| 20 | API-key validator expects `X-API-Key` | Confirmed | `ApiKeySecurityValidator.java:32`; JWT uses `Bearer` (`JwtSecurityValidator.java:30`) | none |
| 21 | AHSE exposes `openLog`/`seal`; #1570's "no append primitive" is stale | Confirmed | `StorageInstance.java:167, 199` | none |
| 22 | `RemoteRepository` is a JAR loader caching in the local Maven repo, never refreshing | Confirmed | `RemoteRepository.java:47, 57-69, 87` | none |
| 23 | No repository-wide listing (#527) | Confirmed | `MavenProtocolHandler.java:105-118` (`501`) | none |
| 24 | Delete leaves chunks unreleased | Confirmed | `ArtifactStore.java:534-538` | #1821 archives; still no release |
| 25 | Metrics come from process-local counters | Confirmed | `ArtifactStore.java:128-133, 339-340` | none |
| 26 | Control-KV backup and AHSE snapshots are distinct facts | Confirmed | `KvBackupService.java`; `StorageFactory.java:1331-1332` | #1821 adds its KV keys to the backup codec |
| 27 | The no-SNAPSHOT restriction is "separately planned" in #1778 | Stale | #1778 moved to rc4 ([owner ruling on #1778, 2026-10-02](https://github.com/pragmaticalabs/pragmatica/issues/1778#issuecomment-5944789887)) and is implemented by #1821 | — |
