### Fixed (2026-11-27 — #3439: the surplus drain selected nodes the drain choke point refuses forever)
- **`LeaderReconciler`'s surplus selection could pick a voter seed whose certified handoff was still missing**, which `ClusterTopologyManager#drainNode` refuses
  for as long as the handoff is missing; the refusal was dropped at dispatch, so no other surplus member was retired in its place. Selection and enforcement
  now answer from one predicate, `ClusterTopologyManager#drainRefusal`: the choke point refuses exactly when it is present, and the selector skips every
  candidate for which it is present.
- A refusal is no longer dropped: it is logged once per transition and readable as `LeaderReconciler#drainRefusalsView`, including one that reaches dispatch.
  The deferred surplus re-evaluates on its follow-up and drains once the handoff appears.
