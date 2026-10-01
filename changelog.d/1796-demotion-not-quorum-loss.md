### Fixed (2026-10-01 — #1790: a voter demoted out of a live quorum was quiesced as if quorum were lost)

- **A node removed from the electorate while the cluster kept quorum answered 503 for every request it
  would have forwarded.** `RabiaEngine` moves such a node `Active -> Observing`; `Observing` is not
  `isActive()`, so `ConsensusPassive` was emitted, `AppHttpServer` raised `QuorumDisappeared`, and
  `AppHttpState` went `Quiesced` for the whole demote-to-drain window. An observer keeps applying
  committed decisions, so its route registry is current and the quiesce only refused requests it could serve.
- **Demotion is now published as a distinct cause.** `RabiaEngine` tracks three published senses
  (ACTIVE / OBSERVING / PASSIVE) and emits `ConsensusDemoted` on `Active -> Observing` only;
  `ConsensusBridge` turns it into `ClusterStateNotification.demotion()` — still `State.PASSIVE`, with
  `demoted() == true`. `isActive()` is unchanged and every other consumer reads `state()`, so they see
  `PASSIVE` as before. `AppHttpServer` alone reads `demoted()` and keeps routing up; the drain's admission
  gate stays the only designed refusal.
- **A genuine quorum loss still quiesces, including one that follows a demotion.** `Observing -> Paused`
  (or `Syncing`/`Stopped`) now emits a plain `ConsensusPassive`; it was silent before, which would have left
  a demoted-then-partitioned node serving a stale route view. Side effect: other PASSIVE consumers can see a
  second PASSIVE after the demotion one.
- **The quiesced 503 is distinguishable from the startup 503.** "Node quiesced: no quorum" versus the
  unchanged "Node starting, routes not yet synchronized". The old literal appears nowhere else in the repo
  (searched the whole tracked tree excluding `.m2-local`, `target`, `.git`: harness, tests, docs).
- **Not covered:** the hosting-victim shape of #1790 — a node that hosts the slice is exempt from the quiesce
  through the local fast path, so its 503s are a separate, still untraced cause.
- **Pinned** by `RabiaDemotionNotificationTest` (demotion not quorum loss; quorum loss as voter; quorum loss
  while observing) and `AppHttpServerDemotionRoutingTest` (demotion forwards; genuine loss 503s with the new
  text; loss after demotion quiesces); each reddens under a single-hunk mutation.
