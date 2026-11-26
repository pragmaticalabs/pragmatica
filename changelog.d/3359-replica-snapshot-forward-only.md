### Fixed (2026-11-24 — #3359: a replica snapshot install had no order rule)
- **A replica installed a snapshot older than the content it already held.** When the chosen core lagged the replica (`AHEAD`, or an
  `INCARNATION_MISMATCH` from a core that had not applied the newest incarnation yet), the replica discarded its position and took a snapshot that
  the same lagging core served: the store's revision (or even its incarnation) moved down, every newer key regressed and subscribers were told the
  difference as ordinary puts and removes (a worker's goal removed, then re-added by the catch-up; twin #2776).
- One rule, held in `ReplicaApplier.admits` and evaluated once, on the snapshot's FIRST chunk: within one lineage a replica's content never moves down
  in `(incarnation, revision)`. An older incarnation of the same lineage, or the same incarnation below the applied revision, is refused before any
  further byte is pulled: nothing is restored, nothing is notified. Between lineages there is no order (a fresh re-genesis counts from 1 again), and one
  incarnation number with two ids is unordered: both install. The equal-tag check in `ReplicaApplier.install` is gone.
- On a refusal `ReplicaFeedClient` counts it (so the `everyUplinkAvoided` backoff grows to its 15 s cap, with a jitter seeded per attempt), avoids
  that source (so a caught-up uplink is asked next) and, for a snapshot longer than one chunk, sends one `ReplicaSnapshotRequest` at `offset == total`;
  `ReplicaFeedServer` then frees that transfer's serve slot at once and answers nothing. If that one request is lost, the slot is held until
  `transferIdleMillis`, as for any client that stops pulling. While both chosen uplinks stay behind, the replica keeps its state and is not ready; it
  becomes ready once one of them catches up.
- Limit: an incarnation that never reached the backup is invisible to the restore floor, so a restore BELOW it in the same lineage is refused by a
  replica that followed it until that replica restarts. `[design intent — unverified]`
  `[mechanism: one order check in ReplicaApplier.admits at the first chunk; exercised in-JVM on a virtual clock, not multi-node, by ReplicaApplierTest.admits_*, ReplicaFeedClientTest$OrderRule, $RefusalAtTheFirstChunk, $EveryUplinkBehind, $LaggingSource, $LeavingTheLaggingSource, BusyBackoffTest.delay_differsAcrossAttempts_atTheCap]`
