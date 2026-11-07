### Changed (2026-11-07 — #2761: FSM2 F2c, community parity with the legacy engine and the D.6 warning recoveries)
- **The FSM2 cluster engine sized derived communities from the node's own settings**, so a failover to a differently configured
  leader could change the target or the floor of an existing community. It now reads the `[community_policy]` of the COMMITTED
  cluster config (parsed once per committed config), falling back to the node-level sizing when the config carries none:
  a derived community is minted at the committed `max_size` (R-CF.16), the community state machine uses the committed
  `viability_floor` (R-CF.18), and with growth on a worker takes the community `CommunityGrowthPolicy` picks over the source's
  derived communities, counting in-flight assignments so two joins in one drain never both take the last slot; a refusal is
  reported as `community-growth-blocked` and closed by `community-growth-resumed`, the legacy codes (R-CF.17).
  `[mechanism: unit tests Rc5ParityTripwireTest (parity arms enabled) and CommunityGrowthLedgerTest; not exercised multi-node]`
- **23 FSM2 warning codes had no recovery**, so a raised warning stayed open for ever. Per the owner's D.6 ruling (spec 9.4,
  know `7cc9e79f61`): 8 one-shot codes are INFO; `departure-inferred-reversed`, `schema-hold-cleared` and
  `slice-unresponsive-expired` are INFO recoveries of their openers; 10 new INFO recoveries close the remaining conditions
  (`deployment-engine-matched`, `departure-inferred-confirmed`, `departure-breaker-cleared`, `placement-valid`, `join-matched`,
  `retry-exhausted-settled`, `schema-status-readable`, `stray-instance-removed`, `worker-absence-judgeable`,
  `worker-community-policy-matched`). Each recovery is also raised, with `subject-gone` in its text, when its subject leaves
  the FSM's model. `invalid-placement` is now raised once per episode instead of on every reconcile pass.
  `[mechanism: one raise, recovery and subject-gone unit test per row; OperatorWarningCodeTest pins the ruled table]`
- Limit: the open sets are leader-local. A warning raised by a deposed leader is closed by its successor only for the
  recoveries read from committed state (rows 6/7, 18/19). `deployment-engine-matched` with `node-refused-itself` is raised
  just before the node exits and may not reach the cluster event log. `[design intent — unverified]`
