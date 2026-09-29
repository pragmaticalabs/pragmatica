### Fixed (2026-09-29 — #1571: refuse boot when CLUSTER_EVENTS_MAX_COUNT exceeds the MAX_RETAINED read window)
- **A `CLUSTER_EVENTS_MAX_COUNT` above the aggregator's 10,000-event read window was accepted**, so the
  stream retained events no read of `system:cluster-events:1.0.0` could return.
- Such a value now refuses the boot with `InvalidLimit`, naming the variable, its value and
  `CLUSTER_EVENTS_MAX_RETAINED`. A count at the window is still accepted.
  `[mechanism: AetherNode.createNode builds the node only through ClusterEventsLimits.clusterEventsLimits(), whose refusal ClusterEventsLimitsTest pins at the unit level; no boot test exercises it]`
