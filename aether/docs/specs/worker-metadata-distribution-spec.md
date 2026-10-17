# Worker metadata distribution

Scope: normative target for runtime PR #1390; present-tense requirements do not assert that
`release-1.0.0-rc4` implements them. Baseline observations are explicitly labelled below.

Status: implementation contract for hierarchical-cluster H10/P7; acceptance evidence belongs in the implementation ledger.

Workers are clients of committed core state. They do not synchronize or replay the global Rabia log. Candidate cores remain consensus observers; immutable worker identity selects passive-client consensus mode before network startup. Worker mutation requests retain the explicit forwarding request/result protocol.

## Scope and consumers

A worker requires its own deployment assignments and activation/placement, its community's authority and membership, and metadata for locally assigned artifacts and their expanded blueprint dependencies. Remote NodeArtifact records for those artifacts are required: they populate the endpoint registry. Filtering to only local NodeArtifact records would break cross-node invocation.

Scopes are shared global control, node, community, artifact, and referenced resources. Artifact scopes use artifact base identity where version routing requires multiple active versions. Related blueprints supply resource configuration and security overrides. Stream/topic/task/entity metadata must follow its declared artifact/resource bindings. Core/DHT ownership information is bounded by control placement, not worker count. Control-only orchestration, raw ClusterConfigValue TOML (including source provider settings), provider credentials, audit history, unrelated communities and unrelated workloads are not worker metadata. Worker reporting falls back to its bootstrap cluster name; no blanket provider configuration is required. The key-to-scope switch is exhaustive over AetherKey so newly introduced keys require an explicit distribution decision.

A service with 10,000 relevant replicas can legitimately require 10,000 endpoints. This is a workload cardinality bound, not a claim that every worker view is bounded by community size. Oversized projections produce an explicit failure and operator signal; they are never truncated into a seemingly complete view. Management operations on worker nodes must forward to cores or reject unsupported global views explicitly.

## Consistent cut and repair

Committed KV notifications maintain per-scope indexes. Invalidation is key-specific; unrelated core commits do not invalidate every worker's data. Scope content is encoded canonically and identified by a digest. Immutable encoded scope chunks are shared across workers that need the same content.

A manifest captures immutable scope maps, directory hints, and the committed revision under the same KV commit lock used to apply state. Canonical encoding, digest calculation, cache maintenance, and network delivery occur after releasing the commit lock; cached unchanged scopes reuse their existing descriptors. It identifies a single source incarnation and committed projection cut. The worker stages every required scope from that manifest before installing the merged local projection atomically and replaying the normal KV diff notifications. A worker never activates from a mixture of incomplete manifests.

A client has one in-flight manifest and one chunk request. Chunk responses bind source, manifest, scope hash, offset and total length. Duplicate or reordered responses cannot splice different content. Reconnect, core failover, unknown manifest, hash mismatch or missing cached chunk abandons the staged generation and requests a fresh manifest. Source incarnation binds chunk leases, while the committed revision is the durable global consensus slot frontier. Workers retain their installed revision floor across core failover; a lagging core explicitly refuses the request. Verified content can be reused across server incarnations because its digest identifies immutable bytes.

Clients poll manifests with their installed committed revision floor and compare the returned content hashes locally. Unchanged scopes reuse verified local bytes. This provides gap repair without an unbounded delta journal and closes the subscribe/pull race: every completed manifest is an explicit cut and the next poll observes later changes. There is no global snapshot fallback.

## Projection freshness and routing

Invocation admission starts closed and requires a complete fresh projection as well as core reachability. Freshness uses monotonic elapsed time from the request that produced the last successful manifest, with a 30-second default bound; delayed chunk delivery cannot extend this bound. An unchanged successful manifest refreshes freshness, while errors clear it. Healthy metrics pongs do not refresh metadata freshness. Metadata serving requires an active consensus core; staged observing cores, paused cores and workers cannot issue manifests or chunks. A detected quorum loss closes serving through the engine's Paused state. This is a locally health-gated freshness exchange, not a linearizable read or authority lease: an asymmetric partition that preserves health traffic while blocking consensus may delay newer committed state. Authority-bearing effects still require their independent committed fences; projection freshness alone does not prove exclusive execution.

The verified membership directory installs current core routing eligibility before replaying projected leader notifications. Worker DHT clients use this core ring, never the scoped community health membership. The endpoint address directory is separate: it enables required cross-community calls without treating remote endpoints as locally observed membership or health.

## Resource bounds

[limit: worker-metadata-envelope] Defaults are 64 KiB chunks, 8 MiB per scope, 128 MiB cache,
16 MiB/s per-core serving bandwidth, 16,384 manifests, 512 scopes per worker, 30-second manifest
TTL and one-second polling (`WorkerMetadataLimits.DEFAULT`). Encoded scope caches have an aggregate byte budget, individual scope byte limit and deterministic eviction. Outstanding manifests are bounded and expired. Eviction never preserves an invalid completeness claim: affected clients receive a restart response. A per-core outbound byte budget throttles both manifests and chunks; request-driven chunk delivery does not queue whole snapshots for reconnecting workers. Empty or unchanged replies do not imply missing data.

These bounds constrain memory and burst fanout. Shared scope encoding avoids encoding the same endpoint set once per worker. Performance evidence must report workload metadata size, relevant endpoint cardinality, mutation rate, reconnect concurrency, cache budget, transport connections, heap and convergence lag; a synthetic 100-by-100 layout alone is not a 10K active-worker scalability claim.

## Acceptance

- Worker startup sends no consensus synchronization request and applies no global Decision.
- A manifest taken concurrently with a multi-key committed transaction is entirely before or after that transaction.
- Source incarnation change, stale response, duplicate chunk, gap, hash mismatch and eviction restart safely.
- Artifact dependency additions/removals update endpoint/resource/security scope membership without losing local deployment behavior.
- Unrelated worker or community changes do not retransmit unaffected scope bodies.
- Many workers sharing an artifact reuse one cached encoding; byte and manifest limits hold during mass reconnect.
- Oversized scope, unauthorized request and unsupported global management read fail visibly.
- Projection installation preserves existing deployment, invocation, stream, pub-sub, entity and authority consumers through normal KV notification semantics.

## Storage boundary and retired subsystem

The worker transport adapter uses the verified core directory for DHT replica placement. The #1390 removal must preserve all active storage paths: the former WorkerDHTNetwork/DHTRelayMessage subsystem had no production assembly caller or inbound relay handler. WorkerBootstrap, DecisionRelay, FollowerHeartbeat/FollowerHealthTracker and the parallel WorkerDeploymentManager/MutationForwarder/WorkerMutation path are assessed as dormant on the rc4 baseline and are to be removed by #1390. Normal NodeDeploymentManager plus ForwardingClusterNode and the scoped metadata channel are the worker execution/mutation paths.

[limit: core-hosted-dht] Community-sharded DHT and governor-mediated metadata relay from the older worker-membership proposal are not delivered here. Core-hosted DHT capacity, core metadata egress and cross-community endpoint connection cardinality must be measured independently of community count. [unverified: 10k-application-storage-throughput] No 10K-node application/storage throughput claim is made.

Worker data connections are additional to the two selected control/metrics uplinks. Because DHT replica placement can select any installed core and the transport has no demand-dial gateway, workers maintain connections to every verified core DHT peer (up to K cores). Core-initiated health polling remains bounded to cores/governors; the extra connections do not expand probe audiences. At N workers this can require O(NK) data connections and up to N inbound worker connections per core. A 10K-worker deployment therefore requires explicit core connection/memory/IOPS sizing; the two-uplink control policy is not a total-connection bound.

## Operator visibility

Serving cores report rejected manifests/chunks to the existing cluster-event stream using NodeLifecycleChanged with the reporting node, affected worker, reason, and suppressed-repeat count. Workers also report local transfer/installation failure best-effort, independently of leader ownership. Reporting is bounded node-wide to one event per 30 seconds with constant memory; concurrent failures do not allocate per-worker throttling state. Logs provide fallback evidence when the system stream is unavailable. A core-side report is important during initial projection failure, when the affected worker may not yet know the event-stream catalog. Event publication is observability, not a prerequisite for refusing an unsafe/incomplete projection.
