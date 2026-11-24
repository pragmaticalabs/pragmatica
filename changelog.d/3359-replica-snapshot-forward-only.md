### Fixed (2026-11-24 — #3359: a replica snapshot install had no forward-only revision guard)
- **A replica installed a snapshot of its own incarnation that was older than what it had already applied.** On `AHEAD` (the chosen core lags the replica) the
  replica discards its position and takes a snapshot, and the same lagging core served one: the store's revision moved down, every newer key regressed and
  subscribers were told the difference as ordinary puts and removes (a worker's goal removed, then re-added by the catch-up; twin #2776).
- `ReplicaApplier.install` now refuses a snapshot whose incarnation tag is the one the store's content belongs to and whose revision is below the applied
  revision: nothing is restored, nothing is notified, the position is unchanged. The followed incarnation survives `discardPosition`, so the guard holds on the
  `AHEAD` path. A new incarnation (a re-genesis or restore) is a different history and still installs below. `ReplicaFeedClient` abandons the transfer and avoids
  that source, so a caught-up core is asked next. Limit: while every reachable core stays behind the replica, the replica keeps its state and retries `[mechanism: ReplicaFeedClient.installFailed, everyUplinkAvoided backoff]`.
  `[verified: ReplicaApplierTest.install_ofALaggingSnapshotOfTheSameIncarnation_isRefused_andChangesNothing, ReplicaFeedClientTest$LaggingSource, ReplicaFeedClientTest$LeavingTheLaggingSource]`
