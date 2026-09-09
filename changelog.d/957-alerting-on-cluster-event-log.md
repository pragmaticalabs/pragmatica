### Changed (2026-09-09 — #957 / #969: threshold alerting moved onto the cluster event log)

- **Threshold alerts were node-local volatile state, and the feature catalog claimed they were
  KV-Store persisted.** Only *thresholds* were ever persisted. Alerts lived in a
  `ConcurrentHashMap` plus a 100-entry `LinkedBlockingDeque` on whichever node happened to evaluate
  them — lost on restart, invisible to every other node. Row 39 has been regraded `Partial` and the
  false persistence claim removed.

- **The finding that decided the design: threshold alerts are DERIVED FACTS, not node-local
  observations.** `ClusterSyncCollector.allMetrics()` returns *every* node's metrics on *every*
  node, so every node evaluates the same input and reaches the same conclusion. That is exactly the
  shape `ClusterEventAggregator`'s owner-gate exists for, so alerts now emit as `ClusterEvent` and
  deduplication comes free — no new stream, no per-node partitions, no routing hop.

- **New sealed variants `ClusterEvent.ThresholdBreached` and `ThresholdCleared`**, pinned at
  `SystemTags` 290/291. Two, not one: an append-only log cannot represent absence, so the clear edge
  must be its own event. They append rather than slotting alphabetically into the 256–289 block —
  the alphabetical order there is incidental, the numbers are the wire contract.

- **Active alerts are a maintained view derived from live metrics — deliberately NOT a fold over the
  log.** Stream retention is `RetentionMode.ANY` (10,000 events **OR** 16 MB **OR** 24 h, whichever
  floor is hit first), so a breach older than the age floor has had its `ThresholdBreached` evicted
  *while still firing*; a fold would report it clear. Re-deriving from current metric values cannot
  make that mistake, and it repopulates within one ~1s evaluation tick after a restart or an
  ownership change, with no log replay. History — which does not need to be live — is a bounded
  projection over the log, and is now **cluster-wide** for the first time: `/api/v1/alerts/history`
  previously returned only what the receiving node had itself observed.

- **Evaluation still runs on every node; only publication is owner-gated.** Moving evaluation to the
  owner would save ~8 double-comparisons per second on a 5-node cluster and cost correctness: the
  edge-triggering state that makes a sustained breach fire *once* lives in the evaluator, so an
  incoming owner would start empty and re-fire every active alert on each ownership change. Because
  every node evaluates continuously, an ownership change now emits nothing.

- **Hysteresis (#969).** A breach clears below its severity's clear point: a CRITICAL alert at
  `max(critical * (1 - margin), warning)`, a WARNING alert at plain `warning * (1 - margin)` with no
  clamp. At the shipped `cpu.usage` 0.7/0.9 and a 5% margin that is 0.855 and **0.665** — a WARNING
  alert does not clear at 0.7. `margin` is configurable via `hysteresis_margin`. Applied to the CLEAR and
  DOWNGRADE edges only — raising stays undamped (damping it would delay first detection) and an
  escalation from WARNING to CRITICAL is never held back. **The clamp, not the margin's value, is
  what prevents a ladder inversion**: without it a CRITICAL alert on a threshold pair closer together
  than the margin could clear beneath its own WARNING threshold and immediately re-raise,
  manufacturing the flapping the margin exists to damp. With the clamp that is impossible for any
  operator configuration, which is what makes 5% a tunable default rather than a correctness
  constant.

- **New `[alerts]` config section.** `AlertConfig` existed but was referenced in `src/main` by
  `AlertForwarder` alone, was not a component of `AetherConfig`, and no `.toml` in the repo carried
  an `[alert...]` section — so the hysteresis margin had nowhere to live and webhook delivery was
  unreachable by any configuration.
  ```toml
  [alerts]
  enabled = true
  hysteresis_margin = 0.05

  [alerts.webhook]          # entirely optional — delivery stays opt-in
  enabled = true
  urls = ["https://example.internal/alerts"]
  retry_count = 3
  timeout = "5s"
  ```
  Plumbing this makes webhook delivery *configurable*; it does not enable it. Absent `[alerts.webhook]`
  the default `WebhookConfig` is DISABLED with no URLs and `AlertForwarder.forward()` early-returns.

- **`AlertConfig.WebhookConfig.check()` was fully written and called by nothing** — a repo-wide grep
  for `check()` returned seven hits, of which the only alert-related one was the declaration itself.
  `AlertConfig.check()` now composes it and `Main` calls that at boot, which is #957's fail-closed
  clause: a webhook enabled with no URLs, or a margin outside `[0.0, 1.0)`, aborts startup naming the
  field instead of producing a node that accepts alerts and silently drops them.

- **The owner-gate's two different drops are now distinguished, and the lost one is counted.**
  `ownerCheck` returns false both when another node owns partition 0 — the steady state on N-1 nodes,
  where the event *is* published — and when ownership cannot be determined at all, where no node
  publishes and the event is dropped without queue or retry. A single boolean cannot tell them apart,
  so WARNing on both would emit a line per event per non-owner per tick and report correct operation
  as a fault. A separate `ownershipResolvable` supplier splits them: the first stays DEBUG, the
  second WARNs and increments `ClusterEventAggregator.ownerlessDrops()`, so the audit-log gap has a
  size an operator can read rather than infer.

### Removed (2026-09-09 — #957)

- **`POST /api/v1/alerts/clear`, and with it `aether alerts clear`, the Forge proxy route, the
  dashboard "Clear All" button and the `ALERTS_CLEAR` route enum.** Every other alerting surface was
  re-pointed rather than removed, because the defect was *where they read from*, not that they
  existed. That rule governs queries; clear was a mutation of state that no longer exists — and it
  was measurably already a no-op. `activeAlertsAsList` re-adds every stream `AlertInjected` whose id
  is absent from the local map, so clearing the map emptied the dedup set and the alerts returned on
  the next read, 2 seconds later at the dashboard's poll rate. It only appeared to work inside the
  bootstrap window, when the stream read returns empty — **it worked when nothing was wrong.**
  Consequence stated plainly: an operator has no supported way to dismiss an injected alert. That was
  true before this change too; removal makes it visible instead of apparently handled. Doing it
  properly needs a tombstone event and is tracked separately rather than smuggled in under "clear".
- The 100-entry `alertHistory` deque and its cap, and `alertHistoryAsJson()`. On a flapping metric
  the cap degraded history to a **~50-second** sliding window.

### Fixed (2026-09-09 — #957)

- `ClusterEventCodecTest.allClosedVariants()` is a hand-maintained list that nothing checked against
  the sealed hierarchy, so a new variant escaped codec round-trip coverage by simply not being added
  — the suite stayed green and reported nothing, because a list that is too short still round-trips
  everything in it. A new `allClosedVariants_coversEveryPermittedSubclass` guard now asserts it
  covers all 35 permitted subtypes, mirroring the guard `KVStoreSerializerTest` already applies to
  `AetherKey`.
