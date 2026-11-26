### Fixed (2026-11-25 — #3720: a DHT put is stamped from one read of the ownership record; a zero-stamp refusal is told apart from a deposed writer)
- **One put is stamped from ONE read of the ownership record.** `OwnerEpochSource` answered the incarnation, the term and the
  counter from three separate store reads, so a record applied between them sent an epoch no owner ever held. The three
  accessors are replaced by one `currentEpoch()` returning `OwnerEpochSource.OwnerEpoch` (primitives only, so the BSL `Epoch`
  type still never enters `integrations/dht`); `DistributedDHTClient.freshStamp()` reads it once.
- **A refusal of a floor (zero) stamp is `DHTError.OwnershipNotApplied`, not `WriteIndeterminate`.** A node that had not applied
  the committed ownership record stamps `0:0:0`, and a replica that had applied it refuses that. The writer is early, not
  deposed: the coordinator knows its own stamp, so it hands the caller the new transient cause (no wire change). The rollback of
  the coordinator's own accept (#1818) is decided on the original cause and is unchanged; a put stamped with a real, older
  epoch keeps `WriteIndeterminate`. `ArtifactStore` retries the new cause like `WriteIndeterminate`.
- **The boot tests wait for the applied ownership record.** `AetherNodePromotionBootTest` and `PromotionBootFixture` (the
  stall and drain boot tests) wait, after ACTIVE, for the `owner-epoch` readiness component that `/health/ready` already
  uses, before writing to storage (`OwnerEpochApplied`). Users of `LoneNodeGenesis` that do not write to the DHT after ACTIVE
  are unchanged.
