# Standalone Maven repository on Aether and AHSE — upgrade specification

| | |
|---|---|
| Status | **Proposed, v0.2. Design only; not implemented. Planned for 1.0.0-rc5 (epic #1831); the target is pending owner confirmation.** |
| Read point | `release-1.0.0-rc4` at `564d2d3df`, source inspection only. Statements about in-flight work cite the open pull request and say "pending merge". |
| Companion | [Component readiness](standalone-maven-repository-readiness.md): the evidence, the claim-by-claim verification and the upstream dependencies. |
| Depends on | #1570, #1569/#1581, #1777, #1778 (implemented by #1821, pending merge), #1133, #527, #1746, #249 |
| Related specs | [Storage identity and adoption](storage-identity-adoption-spec.md), [Per-key ownership fence](ownership-fence-spec.md) (§7.1 is added by #1820, pending merge), [Durable entity primitive](durable-entity-primitive-spec.md), [Hierarchical storage](future/hierarchical-storage-spec.md) |

This specification upgrades shared capabilities in place and adds repository product behavior around them. MUST denotes a required acceptance property of the proposed design. The mechanisms below are proposals until adopted, and existing owner decisions take precedence over them.

## 1. Fixed requirements and product boundary

These requirements are confirmed by the owner. They are settled and are not reopened as prerequisites of any slice.

1. Full Maven repository functionality, including releases and SNAPSHOTs, hosted publication, upstream retrieval and configurable synchronization.
2. Upstream artifact access can be disabled entirely. A sealed deployment MUST install, start, publish and resolve locally without external services or remote UI assets.
3. Keep AHSE as the storage module. Extend it; do not introduce a competing artifact-specific block engine.
4. Reuse Aether clustering and its desired-node-count provisioning, joining and rebalancing operations.
5. Reuse shared backend operations; build a new repository UI.
6. No consensus journal/WAL. Durable repository records and AHSE reference mutations are application/storage data. Existing consensus/control-KV recovery rules remain unchanged.
7. Preserve Aether's internal slice repository policy, including the no-SNAPSHOT restriction of #1778 (implemented by #1821, pending merge). The standalone repository has its own policy and supports SNAPSHOTs.

Proposed complete product surface: named hosted and proxy repositories plus grouped read endpoints, local credentials/access policies, browsing/search, retention, import/export and backup/restore. Whole-release approval/staging, other package formats and vulnerability scanning are separate extensions. Artifact signatures are retained as resources; mandatory trust verification is a separate configurable policy.

### 1.1 Relation to the existing data planes

The repository catalog sits beside three existing planes. Each gives a different per-operation guarantee, and the catalog's authority cannot be borrowed from one that does not give the guarantee it needs.

| Plane | What a successful write means today | Mechanism | Fit for the repository catalog |
|---|---|---|---|
| DHT (`MemoryStorageEngine`) | Accepted in memory by a write quorum of the key's replicas. A write that loses its quorum to owner-epoch fences is **indeterminate**: it may have been applied (#1820, pending merge; owner ruling on #1777, the Dynamo stance). | Per-key epoch and HLC ordering on each replica; replica copies bypass the node-wide high-water | Not an authority. It holds nothing across a whole-cluster restart (no durable tier), and it offers no compare-and-set, so it cannot serialize P3's conflict decision. It may cache derived data. |
| Consensus KV | Committed by Rabia and applied by every node's state machine. Persisted as state snapshots, not as a separate journal. | Rabia command batches; `RabiaPersistence` saves state snapshots | Fit for low-volume control facts: repository configuration, partition ownership, owner fences. #1821 uses it for the internal store's per-artifact version set and per-file content binding. §3 explains why the standalone catalog does not. |
| Fenced replicated application logs (streams, durable entities) | In durable mode, acknowledged once CF copies, the owner included, hold the record `fsync`-durable ([storage identity spec](storage-identity-adoption-spec.md), "Publish ack"). | Owner fence on append; group-commit `fsync`; `CF − 1` non-self acks; the storage-identity and adoption rules of #1569 | The recommended catalog substrate (§3). |

§1.6 forbids a new consensus journal. It does not forbid consensus KV state: #1821 adds KV keys, which Rabia persists as state snapshots, and introduces no journal. What §3 rules out for the standalone product is routing **every** repository path mutation through consensus KV. The reason is volume: #1821 keeps one content-binding entry per stored file, forever, plus a capped version set per artifact. That is sized for slice publication. A general repository holds orders of magnitude more paths, and timestamped SNAPSHOT builds add paths continuously.

## 2. Upgrade boundaries and compatibility

| Existing area | Upgrade scope |
|---|---|
| `integrations/storage` | Durable named/reference-ID mutations; chunked streaming/spooled content; shared-reference lifetime contract; manifest enumeration needed by backup/GC |
| `aether-storage` and node storage assembly | Durable replica acknowledgement, readiness/adoption integration and optional remote-tier construction |
| `resource/services/artifact-repo` | General repository resource identity, catalog, publication/read/removal processes, Maven metadata and upstream policies |
| HTTP server and repository routes | Streaming adapters, HEAD/conditional/range responses, typed failure mapping and repository authorization |
| Runtime assembly/configuration | Independent repository product profile, namespace policy, sealed mode, persistent volumes, existing scale operations |
| Product API/CLI/UI | Repository management, inventory and recovery workflows |

Keep `ArtifactStore`'s slice-facing operations (deploy, resolve, versions and, after #1821, archive in place of delete) and Aether's `Artifact`/`Version` usable by existing callers. Introduce a general repository API and adapt internal coordinates to an internal repository namespace. Do not widen the slice version grammar or switch its ordering semantics as a side effect of supporting Maven versions.

Migration (owner-approved 2026-10-02, epic #1831). Before GA no migration path is required: the owner's pre-GA ruling (2026-09-16) permits breaking format changes without one, and #1821 already drops compatibility with pre-#1778 internal stores. Once data must cross a format boundary after GA, migration MUST be explicit:

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
| `RepositoryId` | Stable configured namespace; included in all resource, permission, job and cache keys |
| `ResourcePath` | Validated exact relative Maven path; preserves version text, timestamp/build number, classifier, multi-part extension and sidecars |
| `ResourceRecord` | Authoritative current path binding: manifest/digest, length, media type, resource revision, origin and lifecycle state |
| `ContentManifest` | AHSE-owned immutable ordered chunk identities/lengths plus overall digest/length |
| `PublicationIntent` | Durable operation identity, expected path state/revision, candidate content and commit/abort outcome |
| `ReferenceId` | Never-reused identity for one logical retained reference; owned by a publication, backup or other explicit durable consumer |
| `CatalogPosition` | Partition and committed position/revision used for reads, projections, backup and recovery |
| `UpstreamObservation` | Origin identity, fetch/validation time, validators and outcome; includes the exact repository resource namespace |

Path validation MUST reject traversal, ambiguous decoding and non-canonical aliases without silently renaming valid Maven resources. Preserve valid Maven version text, including `1`, `1.0`, `1.0.0-rc.1` and compound qualifiers. Store by exact resource path; coordinate parsing is an additional domain view. G/A/V metadata paths, checksum sidecars and signatures are first-class resources, each with its own path. (Today `1.2.3.Final` and `1.2.3-Final` resolve to one stored key, and the `.asc`/`.sha256` sidecars of different files of one version resolve to one file identity; see the readiness document, §1.)

The catalog is authoritative durable application data. Browse/search indexes are rebuildable projections and cannot authorize publication, overwrite or deletion. Current absence is established by the authoritative partition's read contract, not by a DHT miss or an unparseable record.

Recommended realization: a partitioned fenced catalog backed by existing replicated application logs and AHSE persistence. Keep ownership/configuration in existing control mechanisms (consensus KV), but do not replicate every artifact/catalog mutation through the global consensus KV (§1.1). Reuse the existing durable-entity/log machinery where it satisfies the catalog contract: the fenced, `fsync`-durable entity log substrate and its committed-owner lookup and linearizable read barrier. Do not depend on the workflow/saga façades (#345/#354).

Partition assignment MUST be deterministic and persisted/versioned. All mutations of one exact resource path are serialized by one authoritative partition. Related version/file indexes can be derived; cross-partition atomic Maven release publication is not promised. The implementation design must name the shard function, owner fence, read barrier and commit acknowledgement before this catalog is enabled for production.

## 4. Required invariants

**P1 — successful publication.** A successful PUT means the complete content, its retained reference and its authoritative resource record meet the configured durable acknowledgement policy. In-memory replication alone does not satisfy this policy. A DHT quorum acknowledgement is in-memory replication (§1.1), so it cannot be the acknowledgement for any of the three.

**P2 — visibility.** A read sees one complete published resource revision or a typed failure/absence. It never sees partially uploaded bytes. During a legitimate metadata update, an old or new complete revision is permitted according to the selected read contract. An acknowledged publication followed by an authoritative read must not become an unqualified false 404. A replica that cannot yet answer authoritatively answers "not caught up", never "absent". #1823 (pending merge) gives the general DHT this property for replicas that joined through a ring change; the catalog needs the same property from its own substrate.

**P3 — immutable-path conflict.** For a write-once resource, equal bytes are an idempotent success and different bytes are a conflict. The decision is serialized with publication, not implemented as an unguarded existence check. #1821 (pending merge) meets this for the internal store: a first-committed-digest-wins binding in consensus KV, with the loser refused `409` before it uploads anything.

**P4 — retained content.** No GC/retention action can reclaim content referenced by a published resource, active publication or retained backup. Node-local refcounts cannot authorize deletion from a cluster-shared tier.

**P5 — recovery.** Owner replacement and whole-cluster restart recover the resource catalog and reachable content within the stated volume/replica survival envelope. Surviving bytes without path bindings do not count as repository recovery. Adopt the cold-restart outcome of #1569: each catalog partition is either writable and complete, or flagged and untouched pending an operator, within the stated loss envelope.

**P6 — sealed mode.** Disabled artifact upstreams produce zero outbound fetch, refresh, retry or mirror operations. Enforce the gate immediately before dispatch, including after redirects and job resumption. Local publication and internal peer operations remain enabled.

**P7 — honest failure.** Corruption, temporary unavailability, denied access and genuine absence remain distinct. An uncertain request outcome must not be reported as proof that nothing committed (the same rule as the DHT's `WriteIndeterminate`, and as a saga step with an attempt marker but no result, #1827).

The deployment MUST state its RF/CF, durable storage requirements and supported failures using the cluster's existing `[replication]` settings (#1777 track 1 moves the DHT onto the same settings). Do not infer a cold-restart guarantee from RF alone. Adopt the existing storage-identity/incarnation rules. Configurations unable to meet P1/P5 refuse production writes or are explicitly separate development modes.

## 5. Publish, replace and remove

### 5.1 Publish one resource

1. Parse repository/path and authorize the caller. Resolve effective hosted/proxy/internal policy and reserve bounded transfer capacity.
2. Receive content through a bounded stream or spool. Compute length and digests incrementally. An interrupted transfer creates no published resource.
3. Prepare a durable publication intent containing the candidate manifest, stable operation identity and guarded expected path state. Keep staged content pinned.
4. Retain the candidate through AHSE under an idempotent `ReferenceId`, satisfying the required payload/reference durability before commit. A successful local `put` alone is not a replica durability certificate.
5. At the fenced catalog owner, commit the resource binding or record the conflict/abort. The commit includes the projection/reconciliation work needed to make enumeration converge.
6. Return success only after the durable catalog commit. Cleanup of abandoned staging can follow; cleanup failure cannot invalidate already acknowledged content.

The catalog owner MUST recheck the publication guard at commit even if it checked before receiving bytes. Concurrent identical publishes converge on the published resource; losing candidates release their own references without releasing the winner's.

Crash after retaining content but before commit leaves a recoverable intent, not a timer-authorized deletion. Reconciliation determines the intent's terminal outcome from authoritative state: the catalog can always be asked whether the intent committed, so an unknown outcome is resolved by lookup, never assumed. A missing reply can mean the commit succeeded; a retry must discover and return the actual outcome. A new transfer may use a new intent, but only the losing intent's reference can be released.

### 5.2 Mutable resources

SNAPSHOT metadata and other mutable metadata use guarded revisions and resource-specific merge rules. Timestamped artifact files remain distinct resources. A reader holding an old manifest/read handle must remain safe while a new resource revision replaces the path binding; AHSE release/GC integrates active-read protection or an equivalently proven rule.

Replacement commits the new binding before releasing the previous reference. The release instruction is durably recorded with the catalog transition and executed idempotently. A crash may temporarily over-retain content; it must not prematurely remove it.

### 5.3 Removal and retention

Removal commits an unavailable/tombstoned catalog revision and a durable reference-release instruction. Namespace policy decides archive visibility and retention eligibility. Proxy eviction, hosted deletion and snapshot history pruning are distinct policies. (The internal store's archive-instead-of-delete policy with a 7-day minimum retention, #1778, is internal-store policy; it does not set the standalone product's defaults.)

Reference IDs are never reused. Shared reference state and retries MUST prevent a delayed acquire/release from resurrecting or double-releasing a logically retired reference. Reclamation needs authoritative shared reachability, not a local decrement. Integrate #1133's cluster-wide reference design with AHSE; do not bypass it with direct block deletion.

If the initial implementation cannot prove shared reclamation, it may disable physical GC and report retained capacity honestly during development. This is not completion of the full product's retention milestone.

## 6. AHSE capabilities to add or complete

- Durably recorded reference changes without depending on the next periodic metadata snapshot. Use the existing AHSE append-log seam (`StorageInstance.openLog`/`seal`) for storage/application metadata, never for consensus/KV state.
- Stable idempotent retained-reference operations, with cluster-wide authority when blocks are shared, and durable accounting that can be reconciled after interruption.
- A publication durability result whose meaning includes the required surviving copies of payload and manifests. Specify how replica hosts write forced durable storage and how newly admitted replicas become eligible to acknowledge/read. Today only `LocalDiskTier` reports itself durable, and only for its own node; the DHT tier is not durable however many peers hold a copy.
- Streaming/spooled content ingestion and bounded reads, retaining one chunk/manifest implementation. Today `ArtifactStore` chunks content itself at 64 KiB, while the generic `ContentStore` defaults to 4 MiB chunks. Select a measured policy and avoid maintaining two independent chunk lifecycle algorithms.
- A durable manifest/reference enumeration and checkpoint interface adequate for backup, retention and repair. Truncate logs only after a durable checkpoint covers the references needed to recover them.
- Readiness/adoption integrated with storage identity and incarnation fencing. Missing/corrupt state produces an explicit unavailable/repair state.
- Optional RemoteTier construction, credential configuration, shared-tier GC semantics and restore of metadata as well as payload. This extends the same module; sealed local-disk deployments remain first-class.

Physical-block durability, replicated durability and reference durability MUST be independently tested. Tests that merely read a block from a still-running peer do not establish restart survival.

## 7. Maven behavior

### 7.1 Resource and version coverage

Support ordinary Maven versions without imposing SemVer; timestamped SNAPSHOTs with multiple builds retained; POM-only artifacts, parents/BOMs, plugins, classifiers and multi-part extensions. Preserve file bytes, including signatures. Use standard Maven ordering where generating ordered metadata; do not extend the current partial comparator by anecdotal cases.

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

Concurrent cache misses coalesce. Mutable resources refresh according to policy, using origin validators where available. Cache only genuine absence as a negative result. Upstream authorization/transport errors remain errors. Store origin and validators with each accepted resource revision. Credentials are origin-bound and cannot follow unrelated redirects.

Grouped endpoints select hosted/proxy members by configured precedence. Authorization applies before serving or fetching. Proposed safe default: owned private namespaces do not fall through to public origins. Groups are read endpoints; publication targets a specific writable hosted repository.

Scheduled synchronization uses a declared enumerable scope or manifest, durable cursor/progress, bounded concurrency and idempotent publication. Overlapping runs of the same job must not create competing unbounded work. Disabling upstream cancels pending dispatches and prevents retries/resumed jobs from bypassing the gate.

Sealed import/export preserves paths, metadata, digests and provenance in a versioned manifest with its content closure. Import validates the bundle before publishing each resource through the normal guarded path. It never bypasses namespace policy. Conflict policy is explicit and reported. Prove selected build bundles with fresh-cache offline Maven/Gradle builds, including plugins and parents/BOMs. There is no claim of universal dependency closure from arbitrary project source.

## 9. Inventory, access and operations

An enumerable durable catalog drives paginated browse/search and retention. Search projections expose their revision/lag or a documented bounded-staleness contract. An exact path lookup does not depend on the search index. Recovery can rebuild projections without losing publication facts.

Provide read, publish and repository-administration permissions, optionally scoped to namespaces, and optional anonymous reads. A build publisher MUST NOT require cluster-operator privileges. Reuse security infrastructure but expose a convenient standard-client credential path, with local credentials available in sealed deployments. Audit publication conflicts, administrative changes, imports and retention actions. Unauthenticated publication is never a product posture: the internal store accepts it under `security_mode=NONE` or insecure dev mode, with a warning, and the standalone product must not inherit that (owner-approved 2026-10-02, epic #1831).

The new UI and matching API/CLI cover repository/upstream configuration, browse/search/file inspection, credentials, capacity/health, sync progress, retention previews/results, backup/restore and existing desired-node-count scaling. Show logical, retained, temporary and physical storage separately where available; do not substitute process-local counters for cluster inventory.

Package the runtime and UI assets together with validated persistent-volume requirements. Optional upstream/remote-tier features remain visibly disabled when unconfigured. The repository can use Aether internally without exposing unrelated application-deployment concepts in its main user flow.

## 10. Backup and failure acceptance

A repository backup contains a catalog checkpoint (or checkpoint vector), all referenced manifests/content, effective policy/configuration and the information needed to restore credentials/encryption keys under the documented procedure. It pins its reachable content against GC. Control-KV backup remains a separate part of runtime recovery, not a substitute for repository-data backup. (After #1821, the control-KV backup carries the internal store's version sets and content bindings, but not its bytes or per-file metadata; that split is a further reason the two backups stay distinct.)

Unless a stronger barrier is implemented, a partition-vector backup is a consistent collection of resource revisions and references, not a globally atomic multi-artifact release snapshot. State that distinction. Import/restore verifies hashes, restores catalog authority and required references, and rebuilds derived indexes before declaring readiness.

Acceptance scenarios (the `1.0.0-rc.1` case in C1 and the signature/SHA-256/SHA-512 sidecar case in C3 are owner-approved 2026-10-02, epic #1831):

| ID | Scenario and required outcome |
|---|---|
| C1 | Publish versions `1`, `1.0`, `1.0.0-rc.1`, compound qualifiers, POM-only, plugin and `tar.gz` files; exact round-trip paths/bytes |
| C2 | Two timestamped builds under one SNAPSHOT base coexist; metadata resolves correctly by extension/classifier |
| C3 | Concurrent version/classifier/plugin publications lose no unrelated metadata; checksums describe served revisions; signed and SHA-256/SHA-512 sidecars of every file round-trip |
| C4 | Identical release retry succeeds; conflicting retry/concurrent writer cannot overwrite the winner |
| D1 | Kill publisher before catalog commit and after commit/before response; recovery preserves invariants and retry resolves outcome |
| D2 | Kill/reassign owners and restart all nodes with supported surviving volumes; acknowledged paths and bytes recover |
| D3 | Disk full/fsync failure/replica shortfall cannot produce a false successful publication |
| D4 | Delete one of two resources sharing chunks, including publications through different nodes; the survivor remains readable |
| D5 | Race read, replacement, retention, backup and GC; no reachable content is reclaimed |
| N1 | Upstream unavailable versus absent versus denied produce distinct results and correct cache behavior |
| N2 | Parallel misses coalesce; snapshot/metadata freshness changes are observed under configured policy |
| N3 | Disable upstream with pending sync/retry work; observe zero subsequent artifact-origin requests; local publish/read still work |
| N4 | Export/import a selected build bundle; fresh-cache Maven/Gradle build succeeds in an isolated environment |
| O1 | Add/remove nodes through the existing desired-count operation while publishing/downloading; no acknowledged content disappears |
| O2 | Restore backup to fresh eligible infrastructure; inventory, downloads and authorization match the backup contract |
| L1 | Large files and concurrent clients stay within declared memory/spool/concurrency limits; cancellation releases resources |
| A1 | Standard-client credentials work; cross-namespace reads/writes and unauthorized grouped fetches are refused |

Use actual Maven and Gradle publishing/resolution, not only mocked route tests. Failure tests must cross process/storage boundaries. Record supported client versions in the test matrix. Source inspection alone is not acceptance evidence.

## 11. Implementation slices and exit gates

| Slice | Deliverable | Exit gate |
|---|---|---|
| AR1 — contracts/compatibility | Resource grammar, metadata merge tables, shard/read/commit design, AHSE durability/ref protocol, migration plan, product defaults | Review against P1–P7; no unresolved decision may silently become a guarantee |
| AR2 — durable hosted path | Existing AHSE upgrades plus catalog publication/read, basic credentials, bounded transfer and inventory | C4, D1–D3, L1 on a real cluster |
| AR3 — Maven completeness | General identities, all metadata/SNAPSHOT/sidecar paths, HTTP behavior | C1–C3 with real clients and concurrent publication |
| AR4 — proxy/sealed | Generic proxy, groups, refresh, sync, import/export and upstream gate | N1–N4, A1 |
| AR5 — operational completion | Shared retention/GC, backup/restore, new UI/CLI and product packaging | D4–D5, O1–O2; no mandatory upstream service |
| AR6 — scale/recovery qualification | Workload-specific performance and failure campaign | Published limits and repeatable evidence for durability, bounded memory and scaling |

Each slice builds on an end-to-end working predecessor. AR2 is a useful engineering milestone, not the full repository release. Full functionality requires AR3–AR5. Remote object storage can be an additional deployment profile once wired and validated; it is not an excuse to weaken the local/replicated-disk profile.

Coordinate existing #1570/#1569/#1581/#1777/#1778/#1133/#527/#1746/#249 work instead of duplicating it. Track new Maven-domain, streaming and product requirements separately from fixes to existing internal-store promises. No new global consensus persistence mechanism is part of any slice.

## 12. Decisions still requiring explicit selection

These are the bounded AR1 design inputs. The owner-confirmed requirements in §1 are settled and are not reopened as prerequisites.

- Production durability profile(s), supported volume-loss envelope and required control-backup setup. Inherit the existing replication semantics rather than invent another RF/CF model.
- Catalog partition function/read barrier and the precise distributed AHSE reference authority. This spec fixes their safety obligations but does not claim those implementations already exist.
- Metadata merge details, submission/checksum reconciliation and supported nonstandard client behaviors.
- Default release-conflict, snapshot retention, proxy freshness, namespace precedence and import-conflict policies. Proposed starting release policy: byte-identical retry accepted, differing bytes refused (the policy #1821 applies to the internal store).
- Capacity limits and chunk/transfer concurrency, determined by measurement, not copied from the internal cache.
- Whether AR2 may reuse #1821's consensus-KV content binding as an interim path authority under a stated volume bound, or must wait for the partitioned catalog. §1.1 explains why it cannot be the end state.
- Whether a sealed deployment is a dedicated repository cluster or may share a cluster that also runs slices, and so whether the internal store and the standalone catalog coexist in one cluster.
