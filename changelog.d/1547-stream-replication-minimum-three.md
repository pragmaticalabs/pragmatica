### Fixed (2026-09-27 — #1547: stream replication factor defaulted to 1, so a terminally removed owner took its partitions with it)
- **App streams defaulted to `replicas = 1`, and a declared value below 1 was the only one refused.**
  Under terminal removal a dead owner never returns, so at RF=1 losing one node lost every partition it
  owned: the next HRW-ranked node became owner at an empty watermark. The default is now 3, and 3 is the
  minimum: a `[streams.X]` section declaring `replicas` below 3 is refused at deploy under the new rule
  `replicas-below-minimum` (typed `StreamDeclarationError.ReplicasBelowMinimum`, naming the stream, the
  declared value and the minimum), never clamped.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/blueprint/StreamConfigParserTest.java`]
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/validation/StreamResourceValidatorPartitionTest.java`]
- The parser default and `StreamConfig.DEFAULT` agree on 3, and the provisioning config binder resolves an
  absent `replicas` from `StreamConfig.DEFAULT`, so the validated default and the provisioned default are
  one value. `POST /api/v1/streams` mints from the same defaults, so management-created streams are RF=3
  as well. [mechanism: `ProviderBasedConfigService.getDefaultComponentValue` reads `StreamConfig.DEFAULT`]
- With the default factor, an event that reached the replica set before its owner was terminally removed
  is served by the next-ranked replica after a replacement joins under a fresh identity; with the old
  default of 1 the same scenario serves an empty partition.
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamDefaultRfOwnerReplacementTest.java`]
- The factor does not change what an ACK means: at the default `min-sync-replicas` a publish acks on the
  owner's WAL fsync and replication is not awaited, so an event acked inside the replication window is
  still lost with its owner. `min-sync-replicas = replicas` closes that window. When live cores drop below
  3, placement clamps RF to the live core count and the partition runs under-replicated.
  [mechanism: `ReplicaPlacement.replicationFactor` = `clamp(replicas, 1, clusterSize)`]
- System streams keep RF = cluster size. Durable topics (`replicas >= 2`) and durable entities
  (`replication_factor >= 1`) keep their own rules. `test-stream-repl` and `test-stream-multipart` move
  from `replicas = 2` to 3. Docs that promised recovery "until the original owner returns" now state
  what survives terminal removal (guarantees §4, consistency table, known limitations, failure almanac,
  streaming spec §10.5).
