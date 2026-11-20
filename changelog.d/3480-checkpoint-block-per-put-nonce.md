### Changed (2026-11-20 — #3480: per-put nonce (CKPTID02) in the checkpoint block header)
- **Re-saving identical checkpoint content no longer yields the block id an earlier put holds.** The block header is now `CKPTID02`
  with a 16-byte random nonce per put, so a re-put after a crash gets a new id and an orphan sweep of the earlier put cannot delete
  the block the committed pointer names. Ids are distinct with overwhelming probability (128 random bits; birthday bound near 2^64
  puts), not by construction. `CKPTID01` blocks stay readable. [mechanism: CheckpointFrame#frame draws the nonce from one SecureRandom per JVM on every call]
- A restart's save at the committed claim writes nothing when this node holds the committed block on a node-private durable tier
  (new `StorageInstance#holdsPrivateDurableCopy`; its default answers `false`, which re-saves). A new owner, holding only a shared
  or cache copy, still writes a durable block of its own. The skip counts in the driver's `writes`.
  [mechanism: StreamEntityLogSubstrate#saveCheckpoint checks the claim and the private durable tiers before the put]
- Known residual (#3482): a handover with no advance leaks the block the new owner displaced at an equal claim; nothing sweeps it
  yet. [mechanism: the equal-claim deferral is in one node's memory; pinned by an enabled tripwire in EntityCheckpointReclaimTest]
