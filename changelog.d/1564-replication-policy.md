### Changed (2026-09-29 — #1564: one replication policy for streams, durable topics and durable entities)
- **BREAKING: streams, durable topics and durable entities declare `replication_factor` (RF) and
  `confirmation_factor` (CF) — the same two keys in every section (owner ruling, know 267792392).** RF is the
  number of copies of each partition, the owner included; CF is how many of them, the owner included, hold a
  write before it is acknowledged (the owner appends, then awaits `CF − 1` distinct non-self acks). Valid iff
  `1 ≤ CF ≤ RF`. The old keys are removed without aliases (pre-GA): `[streams.X]` `replicas` /
  `min-sync-replicas`, topic `replicas` / `min_sync_replicas`, and the entity's derived `min(2, RF)` CF. A
  stream section still using an old key is refused as unknown, naming its replacement; topic and entity
  sections are strict and refuse any key they do not declare.
  [mechanism: pinned by `StreamConfigParserTest`, `TopicConfigTest`, `DurableEntityConfigTest`]
- **BREAKING default: a stream that declares no factor now acknowledges at CF 2, not on the owner alone.** The
  old `min-sync-replicas` default was 0, so a publish acked on the owner's fsync; with CF 2 a publish needs one
  registered peer and fails `NOT_ENOUGH_REPLICAS` without one. Management-API-created streams take the same
  defaults, so the FIRST publish to a stream that publish creates is refused `NOT_ENOUGH_REPLICAS` (typed,
  `Cause.Transient`, nothing appended) until a peer registers; a retry then succeeds.
  [mechanism: `ReplicationDeclaration.resolve` fills an undeclared CF from the cluster default; pinned by
  `FreshStreamFirstPublishTest`]
- **Cluster-wide defaults live in the committed cluster TOML** (owner ruling, know 596bdfd07(2)): an optional
  `[replication]` section (`replication_factor`, default 3, at least 3; `confirmation_factor`, default 2) and
  `[replication.cluster_events] confirmation_factor` (default 1, the `system:cluster-events` stream, pending the
  owner's acked-but-lost decision). Invalid values or unknown keys refuse the cluster-config apply, and so does a
  `cluster_events` CF above the desired core count (that stream's RF) — on `cluster apply` and on a core scale that
  would drop below it. An undeclared CF resolves to `min(default CF, RF)`.
  [mechanism: pinned by `ReplicationDefaultsParserTest`, `ClusterReplicationTest`, `ClusterConfigRoutesApplyTest`,
  `ClusterTopologyManagerDesiredCountCasTest`]
- **A `system:cluster-events` registration the replication policy refuses is terminal and loud**, not retried at
  DEBUG forever: the leader's registrar logs an ERROR and raises the CRITICAL alert
  `system-stream-registration-refused`; a newly committed cluster config re-arms it.
  [mechanism: pinned by `SystemStreamRegistrarTest`]
- **Refused, typed, at deploy and at activation:** `1 ≤ CF ≤ RF` violated (a negative CF used to pass the stream
  parser and behave as 0); an RF below 3 that came from a default — only a resource's own declaration may go below
  3; an RF above the cluster's desired core count. The engine itself checks only `1 ≤ CF ≤ RF`. Deploy validation
  reports the rules `replication-policy-invalid` and `replication-exceeds-core-count`, replacing
  `replication-invalid` and `replicas-below-minimum`.
  [mechanism: pinned by `StreamResourceValidatorPartitionTest`, `ReplicationPreflightTest`, `StreamSectionBindingTest`]
- **Refused, typed, at activation only:** a live resource redeclared with different factors
  (`ChangedOnLiveResource` — before, a durable topic silently kept its committed factors). The blueprint still
  publishes; the slice fails to activate, naming the committed and the declared factors. The comparison is against
  the committed value the node has applied, not only its local entry, so a node that never materialized the stream
  refuses instead of publishing its own factors over the committed ones.
  [mechanism: pinned by `DeclaredStreamPolicyTest`, `DurableTopicSubstrateTest` (topic and dead-letter stream)]
- **A node that supplies no replication context refuses, typed, for every kind**: `ReplicationContextUnavailable` on
  `StreamDeclarationError`, `TopicConfigError` and `EntityProvisioningError`, instead of an untyped cause for streams
  and topics. `system:cluster-events`' local partition, created at node construction, now carries the committed
  `[replication.cluster_events]` factors instead of a hardcoded CF 1.
  [mechanism: pinned by `StreamSectionBindingTest`, `PublisherFactoryTest`, `DurableEntityFactoryTest`]
- **Durable topics: decision 1's fixed CF == RF is superseded** — CF < RF is accepted, lossless through #1555's
  promotion gate; the dead-letter stream inherits both factors.
- **Deploy warnings now reach the operator.** An explicitly declared RF below 3 (LOUD), CF == RF and CF == 1 are
  warned at declaration: a WARN log, and a new `warnings` array on the blueprint publish/deploy response
  (`{field, rule, message}`), printed by the CLI after a TABLE publish. Deploy-time stream validation warnings
  were computed before and reached no operator. The cluster event for the loud warnings is wired by whichever of
  #1564 and #1617 merges second.
  [mechanism: pinned by `BlueprintDeployStatusTest` (deploy response) and `StreamSectionBindingTest` (activation WARN)]
- The guarantee table per kind and operation is in `guarantees.md` §4a; `guarantees.md` no longer claims an
  unreachable entity "RF=3 default".
