### Fixed (2026-09-28 — #1601: new workers were seeded with dead core voters)
- **`ClusterTopologyManager.provisionWorkers` seeded every new worker's `PEERS` from the installed voter
  set**, which carries no health. A dead voter stayed in a new worker's seed list until membership pruned
  its address on the DEAD edge — indefinitely when the voter is black-holed rather than gone (#1563).
- New workers are now seeded with membership's counted core members (MEMBER + SUSPECT) narrowed to the
  installed voters, the same live set the core auto-heal path already used. With membership liveness
  unwired, the seed falls back to the existing liveness-filtered cold path, unchanged.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerWorkerReconcileTest.java`
  — unit-level: a dead voter the observer still has an address for is absent from the provisioned `PEERS`]
- Worker bootstrap already tolerates one dead core among its seeds: a worker whose `PEERS` name three live
  cores plus one address with nothing listening joins, becomes ready and is counted MEMBER by the leader
  (one 3-core Ember run). So the fix removes a stale peer from the seed, not a bootstrap hang.
  [verified: `aether/ember/src/test/java/org/pragmatica/aether/ember/EmberWorkerDeadSeedTest.java`]
