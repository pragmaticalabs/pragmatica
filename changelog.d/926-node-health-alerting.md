### Added (2026-09-08 — #926: a node-health alerting path, which did not exist)
- **Node health had no alerting path at all.** `AlertEvent` carried exactly three variants —
  `ThresholdAlert`, `SliceFailureAlert`, `AlertResolved` — produced solely by
  `AlertManager.onAllInstancesFailed` (slice-level) and `checkThreshold` (metric-level). Repo-wide,
  "unhealthy" in `aether/node/src/main` appeared exactly twice, both rendering a status string into an
  HTTP response. A cluster member could be confirmed dead with no alert raised anywhere.
- **New `AlertEvent.NodeHealthAlert`**, raised by `AlertManager.onNodeFailed` from the ungated
  `MembershipFsm` DEAD edge and resolved by `clearNodeHealthAlert` from the transport `PeerJoined`
  handshake. Recovery is bound to the same ungated surface as the failure, so it is exactly as
  reachable as the alert it clears — a raise with no matching clear leaves a permanently red surface,
  which trains an operator to ignore it. [verified:
  `AlertManagerNodeHealthTest#nodeFailure_raisesCriticalAlert`, `#rejoin_resolvesTheAlert`,
  `#clearingUnknownNode_isNoOp`]
- **The guarantee: at-most-one active alert per failed node, per observing node.** The map key is
  derived from the failed node alone, so re-observing the same death replaces rather than accumulates.
  That is what lets the raise sit on an edge which fires on every node's FSM without needing a dedup
  token — and a token would be the wrong instrument here, since any token whose scope matches the
  counted unit costs at least quorum, and the incident behind #926 ran below quorum. There is no
  cross-node duplication to reconcile at all: each node keeps its own map, and the alert is a local
  judgment about a remote peer. [verified:
  `AlertManagerNodeHealthTest#repeatedObservationOfSameDeath_isIdempotent`,
  `#distinctFailures_raiseDistinctAlerts`]
- **Alert state is per-node local memory, deliberately.** That is precisely what makes it safe from the
  gating #926 is about: raising it and reading it back need no leader, no quorum, no replica and no
  partition ownership — unlike the cluster-events stream, whose read path prefers a possibly-dead
  remote replica. The trade is the honest one: the alert is visible on the observing node's own
  `/api/alerts` and is not replicated, so it does not survive that node's restart.
  [mechanism: `ConcurrentHashMap` in `AlertManager`, no KV write, no consensus commit on either path]
- **Surfaced on the existing `/api/alerts` view**, discriminated by `source="node_health"`, with
  `nodeId` naming the failed node. No new Management API endpoint, so the REST/CLI/docs/dashboard quad
  does not apply — this is a new alert *kind* on an endpoint that already exists. An alert raised but
  never rendered would not be operator-visible, which would reproduce #926's own defect one layer up.
  [verified: `AlertManagerNodeHealthTest#alertReachesTheOperatorFacingAlertsView`,
  `#resolvedAlert_leavesTheOperatorFacingView`]
- **Nothing forwards this alert anywhere, and the earlier draft of this entry said otherwise.**
  `AlertForwarder` renders alerts to webhook JSON and this change teaches it the new variant — the
  sealed `AlertEvent` switch is exhaustive, so it would not compile otherwise. But **`AlertForwarder`
  is never constructed in production**: `alertForwarder(` has exactly one hit repo-wide, its own factory
  declaration (`AlertForwarder.java:53`); control `alertManager(` in the same search space returns 3.
  No webhook fires, for this alert kind or any other. The renderer *handles* the variant; nothing
  *sends* one, and those are different claims. Wiring the forwarder is not in this change's scope.
  [mechanism: `AlertForwarder.appendNodeHealthFields` renders `"type":"NODE_FAILED"` with `nodeId`,
  `observedBy` and `reason` **if and when** a forwarder is ever constructed — not a claim that anything
  is delivered today]
- **An operator drain raises no alert; everything else still does.** `AlertManager.noteMembershipTransition`
  is fed the FSM transition *cause* and marks `DrainRequested` — the operator/controller drain command,
  the one cause nothing in SWIM's failure detection raises. `onNodeFailed` consumes the mark.
  **`SwimDeparted` is deliberately NOT treated as graceful, and briefly was — a blocking regression
  caught in review.** `MembershipFsm.onSwimDeparted`'s docstring calls it a graceful departure, but its
  producer contradicts that: `SwimProtocol.emitFaultyEdgePair` delivers `FaultyObserved` and
  `DepartedObserved` **as a pair at the FAULTY edge**, because "FAULTY IS confirmed death (canonical
  SWIM) … The death broadcast therefore fires AT the FAULTY edge" — deliberately, to cut `NODE_FAILED`
  latency inside the 60s SLO. `DepartedObserved` routes to `onSwimDeparted` (`AetherNode:4968`), which
  dispatches `SwimDeparted`. **It is SWIM's death broadcast and the primary crash path**: while it was
  in the graceful set, `kill -9` raised no CRITICAL alert at all. A comment was trusted over the code
  that raises the event.
  **Consequence, stated rather than smoothed over:** a non-operator-driven graceful shutdown is
  indistinguishable from a crash at this layer, so a rolling restart still raises CRITICAL per surviving
  observer. **That noise is the accepted trade — noisy beats silent on a failure-detection surface**,
  which is the same one-directional bias below. Quieting it needs a signal SWIM does not carry (a drain
  registry or an explicit shutdown announcement) and is deferred, not solved.
  **The bias is one-directional and deliberate: an UNMARKED departure always alerts**, so a mark that
  is missed or dropped costs a spurious CRITICAL and never a silent one — suppressing a real failure
  re-creates the defect this ticket removes, which is exactly what the `SwimDeparted` regression did.
  The mark is consumed on read, so a node that drains, rejoins and later crashes still alerts.
  [verified: `AlertManagerNodeHealthTest#swimDeparted_stillAlerts_becauseItIsSwimsDeathBroadcast` (the
  regression pin), `#operatorDrain_raisesNoAlert`, `#abruptDeparture_stillRaisesCriticalAlert`,
  `#unmarkedDeparture_alerts_soAMissedMarkIsNeverSilent`,
  `#gracefulMarkIsConsumed_soALaterCrashStillAlerts`]
  [mechanism: ordering holds because `MemberTracking.dispatch` runs
  `synchronized (transitionGuard) { applyEvent(event).forEach(Runnable::run) }` — every dispatch for a
  member is serialised on that guard and runs its staged fan-out synchronously before returning, so an
  earlier dispatch's transition record precedes a later dispatch's DEAD hooks. **An earlier draft
  claimed they share one `emissions` list; they do not** — they are separate dispatches with separate
  lists, and the guard is what actually orders them]
  [unverified: the rolling-restart noise this leaves behind is reasoned, not measured — no run
  quantifies how many spurious CRITICALs a restart produces]
- **The active-alert map is bounded, because the id-exact clear cannot resolve a replaced node.** CTM
  auto-heal mints a *fresh random id* for a replacement rather than reusing the departed one, so
  `clearNodeHealthAlert` — keyed on the rejoining id — can never match it. Under sustained replacement
  churn every replaced node would otherwise add a permanent entry, growing heap and the `/api/alerts`
  payload without limit. The map is now capped at 64 with oldest-first eviction, mirroring the existing
  `MAX_ALERT_HISTORY` bound on `alertHistory`. **Stated plainly rather than dressed up: a replaced
  node's alert ages out under churn; it is not resolved.**
  [verified: `AlertManagerNodeHealthTest#alertMapIsBounded_underReplacementChurn`]
  [unverified: a CTM-replaced node's alert is never RESOLVED — only bounded. Properly resolving it
  needs an identity linking a replacement to the node it replaced, which does not exist today. The
  bound is tested; the resolution is absent by design and stated, not fixed]
  [unverified: nothing forwards any node-health alert off the node — `AlertForwarder` is never
  constructed in production (`alertForwarder(` = 1 hit repo-wide, its own factory; control
  `alertManager(` = 3). The renderer handles the variant; nothing sends it]
- **One departure reaches both surfaces, and that is now pinned as a pair.** An adversarial probe
  deleted the alert call from the `AetherNode` boot lambda and all 1217 tests stayed green — each side
  was pinned in isolation, nothing pinned the composition. The pair now lives in
  `NodeDepartureNotifier`, driven against a real aggregator and a real alert manager, so deleting
  either call turns tests red. [verified:
  `NodeDepartureNotifierTest#oneDeparture_reachesBothSurfaces_withNoLeaderAndNoOwnership`,
  `#confirmedDeparture_reachesTheAlertSurface`, `#announcedDeparture_reachesTheStreamButRaisesNoAlert`]
- All production hunks above were mutation-probed: each was reverted alone, its named test confirmed
  red, and the file restored.
- **Confirmed firing on a real cluster.** On a 3-node cluster with the leader killed, the surviving
  node — never the leader, and at 1-of-3 unable to become one — logged
  `ERROR o.p.a.a.AlertManager.onNodeFailed() - CRITICAL: node n926-1 confirmed failed (observed by
  n926-3) — cluster membership degraded`, twice, once per confirmed departure. The alert path is
  reached with no leader and no quorum, which is the whole point of raising it from the ungated DEAD
  edge. [verified: multi-node run with failure injection on the internal test host]
