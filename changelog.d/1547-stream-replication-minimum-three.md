### Fixed (2026-09-27 — #1547: stream replication factor defaulted to 1, so a terminally removed owner took its partitions with it)
- **App streams defaulted to `replicas = 1`, and a declared value below 1 was the only one refused.**
  Under terminal removal a dead owner never returns, so at RF=1 losing one node lost every partition it
  owned: the next HRW-ranked node became owner at an empty watermark. The default is now 3, and 3 is the
  minimum: a `[streams.X]` section declaring `replicas` below 3 is refused as a binding at blueprint publish
  under the new rule `replicas-below-minimum` (per #1336 the blueprint still publishes with the alias under
  `rejected`; a slice using the alias then fails to load with `UnboundStreamAlias`, an unused alias is
  silently unbound) (typed `StreamDeclarationError.ReplicasBelowMinimum`, naming the stream, the
  declared value and the minimum), never clamped.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/blueprint/StreamConfigParserTest.java`]
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/validation/StreamResourceValidatorPartitionTest.java`]
- The parser default and `StreamConfig.DEFAULT` agree on 3, and the provisioning config binder resolves an
  absent `replicas` from `StreamConfig.DEFAULT`, so the validated default and the provisioned default are
  one value. `POST /api/v1/streams` mints from the same defaults, so management-created streams are RF=3
  as well. [mechanism: `ProviderBasedConfigService.getDefaultComponentValue` reads `StreamConfig.DEFAULT`]
- With the default factor, an event that reached the replica set before its owner was terminally removed
  is still held by two survivors; with the old default of 1 no survivor holds it.
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamDefaultRfOwnerReplacementTest.java`]
- **Not fixed here: serving that data after the owner's loss (#1550).** At every RF, including the rc4 tip's RF=2
  fixture in `StreamOwnerFailoverTest`, the survivors keep resolving the killed node as owner and no node
  serves the partition. The new test asserts that stall as a tripwire and keeps its serving assertions
  disabled until the stall is fixed.
- The factor does not change what an ACK means: at the default `min-sync-replicas` a publish acks on the
  owner's WAL fsync and replication is not awaited, so an event acked inside the replication window is
  still lost with its owner. `min-sync-replicas = replicas` closes that window — except that in a
  `[streams.X]` section the dashed key never reaches the runtime binder, which reads `min_sync_replicas`
  and falls back to 0 (#1549, not fixed here). When live cores drop below
  3, placement clamps RF to the live core count and the partition runs under-replicated.
  [mechanism: `ReplicaPlacement.replicationFactor` = `clamp(replicas, 1, clusterSize)`]
- The same minimum now applies to every app-class stream: durable topics default to `replicas = 3` and
  refuse fewer (`min_sync_replicas == replicas` still required), durable entities refuse
  `replication_factor` below 3, and `StreamPartitionManager.createStream` refuses an app stream below 3
  whichever path minted its config (`StreamError.ReplicasBelowMinimum`), checked on every config commit
  path including the republish of an uncommitted entry. System streams keep
  RF = cluster size. `test-durable-topic` moves to `replicas = min_sync_replicas = 3`.
- **Availability cost, stated so it is not a surprise:** durable topics now need all three replicas for
  every publish (`replicas = min_sync_replicas = 3`). On a 3-node cluster, losing ANY core fails EVERY
  durable-topic publish with `NOT_ENOUGH_REPLICAS` until the core is replaced; before, at RF = min-sync = 2,
  only partitions whose replica set held the lost node failed. [mechanism: `ensureReplicaFloor` refuses
  below min-sync − 1 available peers]
  [verified: `aether/resource/api/src/test/java/org/pragmatica/aether/resource/TopicConfigTest.java`]
  [verified: `aether/resource/durable-entity/src/test/java/org/pragmatica/aether/resource/entity/DurableEntityConfigTest.java`]
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionCapTest.java`] `test-stream-repl` and `test-stream-multipart` move
  from `replicas = 2` to 3. Docs that promised recovery "until the original owner returns" now state
  what survives terminal removal (guarantees §4, consistency table, known limitations, failure almanac,
  streaming spec §10.5).
