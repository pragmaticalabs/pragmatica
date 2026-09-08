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
- **Forwarded to configured webhooks** like every other alert kind, as
  `"type":"NODE_FAILED"` carrying `nodeId`, `observedBy` and `reason`. This is the one alerting surface
  that leaves the cluster entirely, and therefore the one least affected by the failure being reported:
  a webhook POST needs no leader, no quorum and no healthy peer.
  [mechanism: `AlertForwarder.appendNodeHealthFields`; the sealed `AlertEvent` switch is exhaustive, so
  the compiler refuses any future variant that forgets this path]
- All production hunks above were mutation-probed: each was reverted alone, its named test confirmed
  red, and the file restored.
- **Confirmed firing on a real cluster.** On a 3-node cluster with the leader killed, the surviving
  node — never the leader, and at 1-of-3 unable to become one — logged
  `ERROR o.p.a.a.AlertManager.onNodeFailed() - CRITICAL: node n926-1 confirmed failed (observed by
  n926-3) — cluster membership degraded`, twice, once per confirmed departure. The alert path is
  reached with no leader and no quorum, which is the whole point of raising it from the ungated DEAD
  edge. [verified: multi-node run with failure injection on the internal test host]
