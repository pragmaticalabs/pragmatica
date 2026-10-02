# Aether artifact repository — a full Maven repository replacing the built-in store

| | |
|---|---|
| Status | **Proposed, v0.3. Design only; not implemented.** Revised to the owner decisions of 2026-10-02 recorded on epic #1831. AR1–AR3 are planned for 1.0.0-rc5 ([#1831](https://github.com/pragmaticalabs/pragmatica/issues/1831)); AR4–AR6 are planned for the following rc, before GA ([#1836](https://github.com/pragmaticalabs/pragmatica/issues/1836)). |
| Read point | `release-1.0.0-rc4` at `564d2d3df`, source inspection only. #1820 merged afterwards (`66b8bbb2c`). Statements about other in-flight work cite the open pull request and say "pending merge". |
| Companion | [Component readiness](artifact-repository-readiness.md): the evidence, the claim-by-claim verification and the upstream dependencies. |
| Depends on | #1570, #1569/#1581, #1777, #1778 (implemented by #1821, pending merge), #1133, #527, #1746, #249 |
| Related specs | [Storage identity and adoption](storage-identity-adoption-spec.md), [Per-key ownership fence](ownership-fence-spec.md) (§7.1 is the DHT's per-operation contract), [Durable entity primitive](durable-entity-primitive-spec.md), [Hierarchical storage](future/hierarchical-storage-spec.md) |

This specification replaces the internals of Aether's built-in artifact repository with a full Maven repository and upgrades the shared capabilities it needs in place. MUST denotes a required acceptance property of the design. The mechanisms below are proposals until implemented, and existing owner decisions take precedence over them.

## 1. Fixed requirements and scope

These requirements are owner decisions (2026-10-02, recorded on [#1831](https://github.com/pragmaticalabs/pragmatica/issues/1831)). They are settled and are not reopened as prerequisites of any slice.

1. **Placement.** The repository is a full-featured replacement of the existing built-in artifact repository: an optional part of every Aether cluster, not a separate product. Its repository mode and upstream mode are cluster configuration (§1.2).
2. **Namespaces.** The internal slice store becomes one namespace of the repository and keeps its write-once, no-SNAPSHOT policy (#1778). Hosted and proxy repositories have full Maven behaviour, including releases and timestamped SNAPSHOTs, hosted publication, upstream retrieval and configurable synchronization.
3. **Sealed mode.** Upstream artifact access can be disabled entirely (§1.2). A fully sealed cluster MUST publish and resolve locally without external services or remote UI assets.
4. **Storage.** Keep AHSE as the storage module. Extend it; do not introduce a competing artifact-specific block engine.
5. **Clustering.** The repository runs inside the cluster and scales with it, through the existing desired-node-count provisioning, joining and rebalancing operations.
6. **UI.** A section of the Aether dashboard, not a separate repository UI. It shares the dashboard's authentication, navigation and management API, and is hidden in repository modes 1 and 2 (§1.2).
7. **No consensus journal/WAL.** Durable repository records and AHSE reference mutations are application/storage data. Existing consensus/control-KV recovery rules remain unchanged.
8. **Catalog authority.** From AR2 on, the catalog is the partitioned, fenced catalog on the durable-entity log substrate (§3). The consensus-KV content binding of #1778/#1821 is a mechanism of the current built-in store only; it is not used as an interim catalog authority, and the catalog replaces it.
9. **Durability default.** §4, P1.
10. **Default policies.** §5–§8, each configurable per repository.

Product surface: named hosted and proxy repositories plus grouped read endpoints, the internal slice namespace, local credentials/access policies, browsing/search, retention, import/export and backup/restore. Whole-release approval/staging, other package formats and vulnerability scanning are separate extensions. Artifact signatures are retained as resources; mandatory trust verification is a separate configurable policy.

### 1.1 Relation to the existing data planes

The repository catalog sits beside three existing planes. Each gives a different per-operation guarantee, and the catalog's authority cannot be borrowed from one that does not give the guarantee it needs.

| Plane | What a successful write means today | Mechanism | Role for the repository |
|---|---|---|---|
| DHT (`MemoryStorageEngine`) | Accepted in memory by a write quorum of the key's replicas. A write that loses its quorum to owner-epoch fences is **indeterminate**: it may have been applied ([ownership fence spec](ownership-fence-spec.md) §7.1, #1820; the Dynamo stance ruled on #1777). | Per-key epoch and HLC ordering on each replica; replica copies bypass the node-wide high-water | Not an authority. It holds nothing across a whole-cluster restart (no durable tier), and it offers no compare-and-set, so it cannot serialize P3's conflict decision. It may cache derived data. |
| Consensus KV | Committed by Rabia and applied by every node's state machine. Persisted as state snapshots, not as a separate journal. | Rabia command batches; `RabiaPersistence` saves state snapshots | Low-volume control facts: repository configuration, the repository and upstream modes, partition ownership, owner fences. #1821 also uses it for the current built-in store's version sets and content bindings; that use ends when the catalog replaces the store (§1.8). |
| Fenced replicated application logs (streams, durable entities) | In durable mode, acknowledged once CF copies, the owner included, hold the record `fsync`-durable ([storage identity spec](storage-identity-adoption-spec.md), "Publish ack"). | Owner fence on append; group-commit `fsync`; `CF − 1` non-self acks; the storage-identity and adoption rules of #1569 | The catalog substrate (§3). |

§1.7 forbids a new consensus journal. It does not forbid consensus KV state: #1821 adds KV keys, which Rabia persists as state snapshots, and introduces no journal. The catalog still does not route every path mutation through consensus KV, for volume: #1821 keeps one content-binding entry per stored file forever, which is sized for slice publication, while a Maven repository holds orders of magnitude more paths and timestamped SNAPSHOT builds add paths continuously.

### 1.2 Repository and upstream modes

Two independent cluster settings, each with three modes (owner-decided 2026-10-02, #1831). Configuration key names are an AR1 deliverable.

**Repository mode.**

| Mode | Hosted/proxy/group repositories, their external endpoints, the dashboard section | Internal slice namespace | Where slices load from |
|---|---|---|---|
| 0 — enabled | Enabled | Enabled | The internal namespace |
| 1 — user-facing off | Disabled | Enabled | The internal namespace |
| 2 — external | Disabled | Disabled: the internal repository is off entirely | An externally configured Maven repository |

**Upstream mode** (owner-decided 2026-10-02, #1831, as read by the CTO from the owner's "again 3 modes"; correct this table if that reading is wrong).

| Mode | Repository proxy and sync | Slice loader (`RemoteRepository`) |
|---|---|---|
| 0 — upstream enabled | Allowed | Allowed to its configured remotes |
| 1 — fully sealed | Blocked | Blocked: zero outbound artifact traffic |
| 2 — repository-sealed | Blocked | Allowed to its configured remotes |

How the two combine:

- In repository modes 1 and 2 there are no proxy repositories, so the upstream mode governs only the slice loader.
- In repository mode 2 the configured external repository is an explicitly allowed endpoint in every upstream mode. Under upstream mode 1 it is the only one: the slice loader reaches it and nothing else, so "zero outbound artifact traffic" means zero traffic to any origin other than that endpoint.


## 2. Upgrade boundaries and compatibility

| Existing area | Upgrade scope |
|---|---|
| `integrations/storage` | Durable named/reference-ID mutations; chunked streaming/spooled content; shared-reference lifetime contract; manifest enumeration needed by backup/GC |
| `aether-storage` and node storage assembly | Durable replica acknowledgement, readiness/adoption integration and optional remote-tier construction |
| `resource/services/artifact-repo` | General repository resource identity, catalog, publication/read/removal processes, Maven metadata and upstream policies; the internal slice namespace served from the catalog |
| HTTP server and repository routes | Streaming adapters, HEAD/conditional/range responses, typed failure mapping and repository authorization |
| Cluster configuration | Repository mode and upstream mode (§1.2), the external repository for repository mode 2, repository and namespace definitions, the persistent-volume requirement and the explicit dev mode (§4) |
| Management API, CLI and dashboard | Repository management, inventory and recovery workflows; a repository section in the existing dashboard |

Keep `ArtifactStore`'s slice-facing operations (deploy, resolve, versions and, after #1821, archive in place of delete) and Aether's `Artifact`/`Version` usable by existing callers; behind them, the internal slice namespace is served from the catalog. Do not widen the slice version grammar or switch its ordering semantics as a side effect of supporting Maven versions.

Migration (owner-approved 2026-10-02, epic #1831). Before GA no migration path is required: the owner's pre-GA ruling (2026-09-16) permits breaking format changes without one, and #1821 already drops compatibility with pre-#1778 internal stores. This covers rc5's replacement of the built-in store's internals: a cluster upgraded across it republishes its slices. Once data must cross a format boundary after GA, migration MUST be explicit:

- Introduce a versioned catalog namespace/format.
- Existing entries can be imported by a verified inventory of their exact files. If the old store cannot enumerate them, require a supplied coordinate manifest or republish from the build source.
- Unlabelled AHSE blocks cannot recreate missing coordinates.
- The pre-#1821 internal store collapsed timestamped SNAPSHOT builds onto one file identity, so that history cannot be recovered by a format conversion. #1821 refuses SNAPSHOTs in the internal store, so no new history of this kind accrues there.
- Do not introduce implicit dual writes as migration policy. A cutover must fence old writers or explicitly restrict the old namespace to read-only while verified import runs.
- New-format data must remain inaccessible to an incompatible older binary. Rollback after migration requires a compatible reader or restoration of a documented backup.

## 3. Domain identities and authoritative facts

The following are conceptual contracts, not frozen Java names:

| Type/fact | Meaning and authority |
|---|---|
| `RepositoryId` | Stable configured namespace; included in all resource, permission, job and cache keys. The internal slice store is one `RepositoryId` with its own policy. |
| `ResourcePath` | Validated exact relative Maven path; preserves version text, timestamp/build number, classifier, multi-part extension and sidecars |
| `ResourceRecord` | Authoritative current path binding: manifest/digest, length, media type, resource revision, origin and lifecycle state |
| `ContentManifest` | AHSE-owned immutable ordered chunk identities/lengths plus overall digest/length |
| `PublicationIntent` | Durable operation identity, expected path state/revision, candidate content and commit/abort outcome |
| `ReferenceId` | Never-reused identity for one logical retained reference; owned by a publication, backup or other explicit durable consumer |
| `CatalogPosition` | Partition and committed position/revision used for reads, projections, backup and recovery |
| `UpstreamObservation` | Origin identity, fetch/validation time, validators and outcome; includes the exact repository resource namespace |

Path validation MUST reject traversal, ambiguous decoding and non-canonical aliases without silently renaming valid Maven resources. Preserve valid Maven version text, including `1`, `1.0`, `1.0.0-rc.1` and compound qualifiers. Store by exact resource path; coordinate parsing is an additional domain view. G/A/V metadata paths, checksum sidecars and signatures are first-class resources, each with its own path. (Today `1.2.3.Final` and `1.2.3-Final` resolve to one stored key, and the `.asc`/`.sha256` sidecars of different files of one version resolve to one file identity; see the readiness document, §1.)

The catalog is authoritative durable application data. Browse/search indexes are rebuildable projections and cannot authorize publication, overwrite or deletion. Current absence is established by the authoritative partition's read contract, not by a DHT miss or an unparseable record.

Realization (owner decision 1, #1831): a partitioned, fenced catalog on the durable-entity log substrate, with AHSE holding content. Keep ownership/configuration in existing control mechanisms (consensus KV), but do not replicate every artifact/catalog mutation through the global consensus KV (§1.1). Reuse the fenced, `fsync`-durable entity log substrate and its committed-owner lookup and linearizable read barrier. Do not depend on the workflow/saga façades (#345/#354).

Partition assignment MUST be deterministic and persisted/versioned. All mutations of one exact resource path are serialized by one authoritative partition. Related version/file indexes can be derived; cross-partition atomic Maven release publication is not promised. The AR1 design must name the shard function, owner fence, read barrier and commit acknowledgement before the catalog takes over from the current store.

## 4. Required invariants

**P1 — successful publication.** A successful PUT means the complete content, its retained reference and its authoritative resource record meet the durable acknowledgement policy. In-memory replication alone does not satisfy it; a DHT quorum acknowledgement is in-memory replication (§1.1), so it cannot be the acknowledgement for any of the three.

Default policy (owner decision 3, #1831): a publication succeeds only when the content and the catalog entry are on persistent disk, by forced writes, on CF=2 of RF=3 replicas, inheriting the cluster's `[replication]` settings. Per operation:

| Event | Guarantee | Mechanism |
|---|---|---|
| Loss of any one node or volume | Every acknowledged publication survives, and is readable again once its catalog partition has failed over (reads during the failover may get a retryable refusal, never a false absence) | Two forced-write copies of content and catalog entry at acknowledgement; re-replication restores RF from the survivor |
| Whole-cluster restart, volumes intact | Every acknowledged publication is recovered (P5) | Forced writes; the catalog log and AHSE recover from disk under the #1569 adoption rules |
| A second loss before re-replication restores the copy count | Not guaranteed for publications whose only two copies were on the lost nodes or volumes | — (bounded by the repair time) |
| Loss of every volume | Not guaranteed; recovery is backup/restore (§10) | — |

A cluster without persistent volumes runs the repository only in an explicit dev mode, which acknowledges without this policy and says so. #1777 track 1 moves the DHT onto the same `[replication]` settings.

**P2 — visibility.** A read sees one complete published resource revision or a typed failure/absence. It never sees partially uploaded bytes. During a legitimate metadata update, an old or new complete revision is permitted according to the selected read contract. An acknowledged publication followed by an authoritative read must not become an unqualified false 404. A replica that cannot yet answer authoritatively answers "not caught up", never "absent". #1823 (pending merge) gives the general DHT this property for replicas that joined through a ring change; the catalog needs the same property from its own substrate.

**P3 — immutable-path conflict.** For a write-once resource, equal bytes are an idempotent success and different bytes are a conflict. The decision is serialized with publication, not implemented as an unguarded existence check. #1821 (pending merge) meets this for the current built-in store with a first-committed-digest-wins binding in consensus KV; the catalog meets it by serializing each path at its partition owner.

**P4 — retained content.** No GC/retention action can reclaim content referenced by a published resource, active publication or retained backup. Node-local refcounts cannot authorize deletion from a cluster-shared tier.

**P5 — recovery.** Owner replacement and whole-cluster restart recover the resource catalog and reachable content within the P1 envelope. Surviving bytes without path bindings do not count as repository recovery. Adopt the cold-restart outcome of #1569: each catalog partition is either writable and complete, or flagged and untouched pending an operator.

**P6 — sealed mode.** In upstream mode 1 (fully sealed) the cluster makes zero outbound artifact fetch, refresh, retry or mirror requests, from the repository's proxy and sync and from the slice loader's `RemoteRepository` alike; the only exception is the external repository of repository mode 2 (§1.2). In upstream mode 2 the repository's proxy and sync make zero such requests, while the slice loader may reach its configured remotes. Enforce the gate immediately before dispatch, including after redirects and job resumption. Local publication and internal peer operations remain enabled.

**P7 — honest failure.** Corruption, temporary unavailability, denied access and genuine absence remain distinct. An uncertain request outcome must not be reported as proof that nothing committed (the same rule as the DHT's `WriteIndeterminate`, and as a saga step with an attempt marker but no result, #1827).

Adopt the existing storage-identity/incarnation rules. A cluster unable to meet P1/P5 refuses production writes unless the explicit dev mode is configured.

## 5. Publish, replace and remove

### 5.1 Publish one resource

1. Parse repository/path and authorize the caller. Resolve the effective namespace policy (internal, hosted or proxy) and reserve bounded transfer capacity.
2. Receive content through a bounded stream or spool. Compute length and digests incrementally. An interrupted transfer creates no published resource.
3. Prepare a durable publication intent containing the candidate manifest, stable operation identity and guarded expected path state. Keep staged content pinned.
4. Retain the candidate through AHSE under an idempotent `ReferenceId`, satisfying the required payload/reference durability before commit. A successful local `put` alone is not a replica durability certificate.
5. At the fenced catalog owner, commit the resource binding or record the conflict/abort. The commit includes the projection/reconciliation work needed to make enumeration converge.
6. Return success only after the durable catalog commit. Cleanup of abandoned staging can follow; cleanup failure cannot invalidate already acknowledged content.

The catalog owner MUST recheck the publication guard at commit even if it checked before receiving bytes. Concurrent identical publishes converge on the published resource; losing candidates release their own references without releasing the winner's.

Crash after retaining content but before commit leaves a recoverable intent, not a timer-authorized deletion. Reconciliation determines the intent's terminal outcome from authoritative state: the catalog can always be asked whether the intent committed, so an unknown outcome is resolved by lookup, never assumed. A missing reply can mean the commit succeeded; a retry must discover and return the actual outcome. A new transfer may use a new intent, but only the losing intent's reference can be released.

Default release-conflict policy (owner decision 4): an identical re-put succeeds; different bytes are refused with `409`. The internal slice namespace applies the same rule and additionally refuses SNAPSHOT versions (#1778).

### 5.2 Mutable resources

SNAPSHOT metadata and other mutable metadata use guarded revisions and resource-specific merge rules. Timestamped artifact files remain distinct resources. A reader holding an old manifest/read handle must remain safe while a new resource revision replaces the path binding; AHSE release/GC integrates active-read protection or an equivalently proven rule.

Replacement commits the new binding before releasing the previous reference. The release instruction is durably recorded with the catalog transition and executed idempotently. A crash may temporarily over-retain content; it must not prematurely remove it.

### 5.3 Removal and retention

Removal commits an unavailable/tombstoned catalog revision and a durable reference-release instruction. Namespace policy decides archive visibility and retention eligibility. Proxy eviction, hosted deletion and snapshot history pruning are distinct policies. The internal slice namespace keeps #1778's archive-instead-of-delete policy (7-day minimum retention, configurable).

Default SNAPSHOT retention (owner decision 4; semantics owner-decided 2026-10-02, #1831): a build is kept while EITHER it is among the newest 10 builds of its base version OR it is at most 30 days old. It is removed only when it is BOTH beyond the newest 10 AND older than 30 days.

Reference IDs are never reused. Shared reference state and retries MUST prevent a delayed acquire/release from resurrecting or double-releasing a logically retired reference. Reclamation needs authoritative shared reachability, not a local decrement. Integrate #1133's cluster-wide reference design with AHSE; do not bypass it with direct block deletion.

Until shared reclamation is proven (AR5), physical GC may stay disabled, with retained capacity reported honestly. Logical retention (removing catalog entries) still applies.

## 6. AHSE capabilities to add or complete

- Durably recorded reference changes without depending on the next periodic metadata snapshot. Use the existing AHSE append-log seam (`StorageInstance.openLog`/`seal`) for storage/application metadata, never for consensus/KV state.
- Stable idempotent retained-reference operations, with cluster-wide authority when blocks are shared, and durable accounting that can be reconciled after interruption.
- A publication durability result whose meaning includes the required surviving copies of payload and manifests (P1: forced writes on CF=2 of RF=3). Specify how replica hosts write forced durable storage and how newly admitted replicas become eligible to acknowledge/read. Today only `LocalDiskTier` reports itself durable, and only for its own node; the DHT tier is not durable however many peers hold a copy.
- Streaming/spooled content ingestion and bounded reads, retaining one chunk/manifest implementation. Today `ArtifactStore` chunks content itself at 64 KiB, while the generic `ContentStore` defaults to 4 MiB chunks. Select a measured policy and avoid maintaining two independent chunk lifecycle algorithms.
- A durable manifest/reference enumeration and checkpoint interface adequate for backup, retention and repair. Truncate logs only after a durable checkpoint covers the references needed to recover them.
- Readiness/adoption integrated with storage identity and incarnation fencing. Missing/corrupt state produces an explicit unavailable/repair state.
- Optional RemoteTier construction, credential configuration, shared-tier GC semantics and restore of metadata as well as payload. This extends the same module; sealed local-disk clusters remain first-class.

Physical-block durability, replicated durability and reference durability MUST be independently tested. Tests that merely read a block from a still-running peer do not establish restart survival.

## 7. Maven behavior

### 7.1 Resource and version coverage

Hosted and proxy repositories support ordinary Maven versions without imposing SemVer; timestamped SNAPSHOTs with multiple builds retained; POM-only artifacts, parents/BOMs, plugins, classifiers and multi-part extensions. Preserve file bytes, including signatures. Use standard Maven ordering where generating ordered metadata; do not extend the current partial comparator by anecdotal cases. The internal slice namespace accepts the same paths but refuses SNAPSHOT versions.

### 7.2 Metadata ownership

Support group-level plugin mappings, artifact-level version discovery, and version-level timestamped snapshot discovery, including paths with overlapping metadata roles. Validate parsed XML safely and retain enough input/provenance to explain each accepted update.

Hosted metadata updates are serialized and merged according to their metadata kind so concurrent publishers cannot lose unrelated versions/classifiers/plugin entries. A SNAPSHOT entry may advance only to an available published resource. Deletion/retention reconciles discovery entries. Define the permitted compatibility behavior for interrupted multi-request deployment; an all-files release transaction is not implied.

The exact merge tables and representation of optional XML fields are an AR1 design deliverable. Ordinary last-writer-wins replacement of a version list does not meet acceptance. Proxied metadata preserves upstream semantics; grouped metadata has an explicit, stable source-precedence/merge policy. (#1821's grow-only version set with an archived flag is a working precedent for the artifact-level version list. It covers neither plugin mappings nor SNAPSHOT build discovery.)

Checksums served for metadata MUST describe the exact selected stored/generated revision. Incoming metadata/checksum PUTs cannot be acknowledged and silently discarded. When the server merges a client's metadata into a new representation, retain/validate the submission separately and derive authoritative sidecars from the merged result. Specify treatment of delayed sidecars from previously accepted submissions so concurrent publishers do not corrupt the current checksum. Independent GETs spanning a metadata update need not share a snapshot; validators and client retry behavior must be tested.

### 7.3 HTTP contract

GET and HEAD return the same status/resource headers, with no HEAD body. Support conditional retrieval and byte ranges for stored files, with clear behavior for unsupported/malformed ranges. Content length and digests are available without loading all bytes. Stream large bodies with backpressure; bound disk spooling and concurrent transfers.

Map malformed paths/XML to client errors; authentication and authorization to their appropriate responses; conflicting immutable content to 409; genuinely absent resources to 404; failed preconditions to 412 where used; oversized uploads to 413; temporary storage/upstream failures to retryable service errors. Internal corruption is an integrity failure, not absence. Exact status/body conventions must be consistent across artifacts, metadata and sidecars and validated with standard clients. (The internal store's `410 Gone` for an archived version, #1821, is a precedent for a retired resource that was once present.)

Support common digest sidecars, including MD5/SHA-1 for compatibility and SHA-256/SHA-512. Compute checksums from authoritative bytes; verify supplied values under the resource's publication policy. Hash choice for content addressing remains an AHSE decision.

## 8. Upstream, grouping and offline operation

Each proxy specifies origin, credentials, release/SNAPSHOT policy, freshness, negative-cache policy and resource scope. Fetch exact resource paths; do not use the JAR-only slice resolver as the complete proxy implementation.

Default proxy policy (owner decision 4, configurable per repository):

| Resource or outcome | Default | Mechanism |
|---|---|---|
| Released file | Immutable once cached; never refetched | Stored with its origin and validators |
| Metadata and SNAPSHOT discovery | Served from cache for 30 minutes, then revalidated | Conditional request with the stored origin validators |
| Genuine absence (origin answers not-found) | Cached for 10 minutes | Negative-cache entry with an expiry |
| Errors (transport, timeout, denied, server error) | Never cached | Returned as a typed failure (P7); the next request tries again |

Concurrent cache misses coalesce. Store origin and validators with each accepted resource revision. Credentials are origin-bound and cannot follow unrelated redirects.

Grouped endpoints select members by precedence: hosted before proxy by default (owner decision 4). Owned namespaces never fall through to public origins. Authorization applies before serving or fetching. Groups are read endpoints; publication targets a specific writable hosted repository.

Scheduled synchronization uses a declared enumerable scope or manifest, durable cursor/progress, bounded concurrency and idempotent publication. Overlapping runs of the same job must not create competing unbounded work. Disabling upstream cancels pending dispatches and prevents retries/resumed jobs from bypassing the gate.

Sealed import/export preserves paths, metadata, digests and provenance in a versioned manifest with its content closure. Import validates the bundle before publishing each resource through the normal guarded path. It never bypasses namespace policy. Default import-conflict policy (owner decision 4): a conflicting resource is refused and reported, never overwritten. Prove selected build bundles with fresh-cache offline Maven/Gradle builds, including plugins and parents/BOMs. There is no claim of universal dependency closure from arbitrary project source.

## 9. Inventory, access and operations

An enumerable durable catalog drives paginated browse/search and retention. Search projections expose their revision/lag or a documented bounded-staleness contract. An exact path lookup does not depend on the search index. Recovery can rebuild projections without losing publication facts.

Provide read, publish and repository-administration permissions, optionally scoped to namespaces, and optional anonymous reads. A build publisher MUST NOT require cluster-operator privileges. Reuse the cluster's security infrastructure but expose a convenient standard-client credential path, with local credentials available in sealed clusters. Audit publication conflicts, administrative changes, imports and retention actions. Unauthenticated publication is never a supported posture: the current store accepts it under `security_mode=NONE` or insecure dev mode, with a warning, and the repository must not inherit that (owner-approved 2026-10-02, epic #1831).

The dashboard's repository section and the matching management API/CLI cover repository/upstream configuration, browse/search/file inspection, credentials, capacity/health, sync progress, retention previews/results and backup/restore. Node-count scaling stays with the dashboard's existing cluster views. The section is hidden in repository modes 1 and 2 (§1.2). Show logical, retained, temporary and physical storage separately where available; do not substitute process-local counters for cluster inventory.

The repository ships with the node; it has no separate packaging. Cluster configuration validates the persistent-volume requirement (P1), the repository and upstream modes, and the external repository required by repository mode 2. Optional upstream/remote-tier features remain visibly disabled when unconfigured.

## 10. Backup and failure acceptance

A repository backup contains a catalog checkpoint (or checkpoint vector), all referenced manifests/content, effective policy/configuration and the information needed to restore credentials/encryption keys under the documented procedure. It pins its reachable content against GC. Control-KV backup remains a separate part of runtime recovery, not a substitute for repository-data backup. (After #1821, the control-KV backup carries the current store's version sets and content bindings, but not its bytes or per-file metadata; that split is a further reason the two backups stay distinct.)

Unless a stronger barrier is implemented, a partition-vector backup is a consistent collection of resource revisions and references, not a globally atomic multi-artifact release snapshot. State that distinction. Import/restore verifies hashes, restores catalog authority and required references, and rebuilds derived indexes before declaring readiness.

Acceptance scenarios (the `1.0.0-rc.1` case in C1 and the signature/SHA-256/SHA-512 sidecar case in C3 are owner-approved 2026-10-02, epic #1831):

| ID | Scenario and required outcome |
|---|---|
| C1 | Publish versions `1`, `1.0`, `1.0.0-rc.1`, compound qualifiers, POM-only, plugin and `tar.gz` files to a hosted repository; exact round-trip paths/bytes |
| C2 | Two timestamped builds under one SNAPSHOT base coexist in a hosted repository; metadata resolves correctly by extension/classifier |
| C3 | Concurrent version/classifier/plugin publications lose no unrelated metadata; checksums describe served revisions; signed and SHA-256/SHA-512 sidecars of every file round-trip |
| C4 | Identical release retry succeeds; conflicting retry/concurrent writer gets `409` and cannot overwrite the winner |
| C5 | The internal slice namespace, served from the catalog, refuses a SNAPSHOT and a different-bytes re-put, while slice deployment resolves its artifacts unchanged |
| D1 | Kill publisher before catalog commit and after commit/before response; recovery preserves invariants and retry resolves outcome |
| D2 | Kill/reassign owners and restart all nodes with volumes intact; acknowledged paths and bytes recover |
| D3 | Disk full/fsync failure/replica shortfall (fewer than CF=2 forced copies) cannot produce a false successful publication |
| D4 | Delete one of two resources sharing chunks, including publications through different nodes; the survivor remains readable |
| D5 | Race read, replacement, retention, backup and GC; no reachable content is reclaimed |
| D6 | Destroy one node's volume after acknowledged publications; every one stays readable and RF is restored |
| N1 | Upstream unavailable versus absent versus denied produce distinct results; only absence is cached, for the configured negative-cache period |
| N2 | Parallel misses coalesce; metadata is revalidated after the configured freshness period; cached releases are never refetched |
| N3 | Switch to upstream mode 1 with pending sync/retry work; observe zero subsequent artifact-origin requests, including from the slice loader; local publish/read still work. In upstream mode 2, proxy/sync requests stop while the slice loader still reaches its configured remote |
| N4 | Export/import a selected build bundle; fresh-cache Maven/Gradle build succeeds in an isolated environment; an import conflict is refused and reported |
| O1 | Add/remove nodes through the existing desired-count operation while publishing/downloading; no acknowledged content disappears |
| O2 | Restore backup to fresh eligible infrastructure; inventory, downloads and authorization match the backup contract |
| O3 | Repository mode 1: hosted/proxy/group endpoints and the dashboard section are absent, while slices still deploy and resolve through the internal namespace. Repository mode 2: the internal repository is absent and slices load from the configured external repository, which stays reachable under upstream mode 1 while every other origin is refused |
| L1 | Large files and concurrent clients stay within declared memory/spool/concurrency limits; cancellation releases resources |
| A1 | Standard-client credentials work; cross-namespace reads/writes and unauthorized grouped fetches are refused; the dashboard section uses the dashboard's authentication |

Use actual Maven and Gradle publishing/resolution, not only mocked route tests. Failure tests must cross process/storage boundaries. Record supported client versions in the test matrix. Source inspection alone is not acceptance evidence.

## 11. Implementation slices and exit gates

| Slice | Target | Deliverable | Exit gate |
|---|---|---|---|
| AR1 — contracts/compatibility | rc5, #1831 | Resource grammar, metadata merge tables, shard/read/commit design, AHSE durability/ref protocol, cluster configuration keys (repository mode, upstream mode, external repository, dev mode) | Review against P1–P7; every §12 open item decided or explicitly deferred; no unresolved decision may silently become a guarantee |
| AR2 — durable hosted path | rc5, #1831 | Existing AHSE upgrades plus catalog publication/read, basic credentials, bounded transfer and inventory; the internal slice namespace served from the catalog, retiring #1821's KV binding | C4, C5, D1–D3, D6, L1 on a real cluster |
| AR3 — Maven completeness | rc5, #1831 | General identities, all metadata/SNAPSHOT/sidecar paths, HTTP behavior | C1–C3 with real clients and concurrent publication |
| AR4 — proxy/sealed | next rc, #1836 | Generic proxy, groups, refresh, sync, import/export and the cluster-wide upstream gate | N1–N4, A1 |
| AR5 — operational completion | next rc, #1836 | Shared retention/GC, backup/restore, the dashboard section and CLI | D4–D5, O1–O3; no mandatory upstream service |
| AR6 — scale/recovery qualification | next rc, #1836 | Workload-specific performance and failure campaign | Published limits and repeatable evidence for durability, bounded memory and scaling |

Each slice builds on an end-to-end working predecessor. rc5's AR1–AR3 replace the built-in store's internals: at the end of AR3, every cluster's slice store is the internal namespace of the catalog, and hosted repositories with full Maven behaviour are available. Remote object storage can be an additional deployment profile once wired and validated; it is not an excuse to weaken the local/replicated-disk default.

Coordinate existing #1570/#1569/#1581/#1777/#1778/#1133/#527/#1746/#249 work instead of duplicating it. No new global consensus persistence mechanism is part of any slice.

## 12. Decisions

Decided by the owner on 2026-10-02 ([#1831](https://github.com/pragmaticalabs/pragmatica/issues/1831)): catalog authority (§1.8, §3), placement and namespaces (§1, items 1–2), the repository and upstream modes (§1.2), durability (§4 P1), default policies and the SNAPSHOT retention semantics (§5.1, §5.3, §8), targets (§11) and the UI (§1.6, §9).

Still open, as AR1 inputs:

- Catalog partition function/read barrier and the precise distributed AHSE reference authority. This spec fixes their safety obligations but does not claim those implementations already exist.
- Metadata merge details, submission/checksum reconciliation and supported nonstandard client behaviors.
- Capacity limits and chunk/transfer concurrency, determined by measurement, not copied from the current store.
