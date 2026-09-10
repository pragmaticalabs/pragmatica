### Fixed (2026-09-10 — #936: the autoscaler could not scale down after its first scale-up, and said nothing about it)
- **`ControlLoopContext.applyScaling` wrote `newInstances` into both `targetInstances` and
  `minInstances`** of the durable `SliceTargetValue`. The Put fed back through
  `ControlLoop.onSliceTargetPut` — the sole feeder of the autoscaler's own model — so the operator's
  floor was replaced by the count the slice had just scaled to. `computeRequestedInstances` floors a
  scale-down at that minimum, so from the first scale-up onwards `max(minInstances, instances -
  reduceBy)` evaluated to `instances` for **any** `reduceBy`. A cluster scaled up under load and
  stayed there for the life of the deployment.
- **It failed silently.** The caller returns `Option.none()` when the new count equals the current
  one, so there was no command, no event and no log; the decision snapshot still carried the cycle's
  `HELD`/`NONE` baseline. An autoscaler declining to act at a floor was indistinguishable from one
  with nothing to do.
- **Fixed at the root shared with #698 and #937, not at the argument.** The three defects were one
  missing discipline across the rebuilding producers: a producer rebuilding a nine-component record from
  the seven-field projection it happened to hold, so every component outside the projection took a
  factory default on every scaling Put. `ControlLoop.registerBlueprint` now takes the observed
  `SliceTargetValue` instead of seven exploded fields, the autoscaler's registration map holds that
  value, and `applyScaling` emits `observed.withInstances(newInstances)`. The autoscaler decides one
  number and writes one number; no component can be dropped by omission, including components added
  to the record later.
- **The same instance is written to the in-memory registration and to the Put**, so the mirror and
  the durable record cannot disagree — and their disagreement was the defect. `ClusterController.Blueprint`
  is now derived on read rather than stored beside the value it comes from.
- **New `ScalingDecisionRecord.Guard.MIN_INSTANCES`**, the lower-bound counterpart of
  `MAX_INSTANCES`. A floored scale-down now logs and records `HELD`/`MIN_INSTANCES` on the #425
  management surface, carrying the **pre-floor** count as `requestedInstances` so an operator can see
  what was wanted. Only a genuinely floored request is reported: a change asking for the count the
  slice already runs was never going to move anything.
- **Construction-site audit (#936 acceptance 4, #937 acceptance 4).** Six
  `SliceTargetValue.sliceTargetValue(...)` overloads, five of which hardcode `placement`. Search
  space: every `*.java` under the repo root outside `target/`, restricted to `src/main`; the six
  factory declarations themselves are the positive control that the pattern matches. **Production
  callers after this fix — five in total:**

  | overload | callers | where |
  |---|---|---|
  | `(Version, int)` | 2 | `RollbackManager`, `DeploymentManagerImpl` — fallbacks for a slice with no existing value; both main paths already use `with*` |
  | `(Version, int, int)` | 1 | `AbTestManager`'s fallback, **introduced by this fix** for the same reason |
  | `(Version, int, int, String)` | 1 | `SliceRoutes` — the only overload that can express a placement |
  | 7-arg | 1 | `ClusterDeploymentState.handleAppBlueprintChange` — **not fixed here, and not a first write**: see the limitation below |
  | `(Version, int, Option<BlueprintId>)` | 0 | — |
  | `(Version, int, int, Option<BlueprintId>)` | 0 | — |

  Before this fix the 7-arg overload had **3** callers: the two rebuilding producers
  (`ControlLoopContext`, `AbTestManager`) are exactly the ones this change removes. Javadoc on the
  creation factories now states that a producer rebuilding an observed value must use the `with*`
  methods instead.
- **A THIRD producer of this defect exists and is NOT fixed here.**
  `ClusterDeploymentState.handleAppBlueprintChange` is the handler for **every**
  `AppBlueprintPutReceived` — publish *and* republish, not first deploys only. It reads no existing
  value and rebuilds a whole `SliceTargetValue` through the 7-arg factory, so it **resets
  `placement` to `CORE_ONLY` on every blueprint republish**, with owner and bounds intact — #937's
  exact signature at a site neither ticket names. `ResolvedSlice` genuinely carries no placement, so
  the overload is not the whole story; the missing step is reading the existing value first.
  The intersection is unavoidable rather than merely possible: `POST /scale` is the only production
  writer of a non-default placement and it **refuses slices that are not blueprint-owned**, so every
  slice that can carry an operator placement is one this path rewrites. Pre-existing, outside
  #936/#937's scope, and left alone deliberately — **recorded so the next reader looks here rather
  than reading this row as an all-clear.** Needs its own ticket.
  - **Not changed, and stated because the audit found it:** `sliceTargetValue(version, instances)`
    and `SliceRoutes`' first write both set `minInstances = instances`, so a slice created through
    them has floor == target from birth. That is a creation-time policy decision ("what you deployed
    is the floor"), not the runtime ratchet this ticket reports, and changing it would alter
    deployed cluster sizes. Left alone deliberately.
- **This fix UNMASKS a pre-existing A/B gap, and that is a consequence of it rather than an unrelated
  note.** `AbTestManager` pins `minInstances = 1` on all three lifecycle writes (`createTest`,
  `promoteWinner`, `restoreBaseline`) and **nothing restores the operator's floor when the test
  concludes**. The ratchet used to hide this: `minInstances` was pushed back up to the running count
  on the next scale, so a clobbered floor was masked — wrongly, but masked. With the ratchet removed
  the floor genuinely stays at 1, and an operator's `minInstances = 5` survives any A/B test as an
  effective floor of 1. Behaviour is **unchanged by this branch** (the pre-fix code passed literal
  `1, 1` to the factory); what changes is that the consequence is now reachable. Not fixed here — the
  canary write itself is correct, since a variant must run at one instance and leaving the floor at 5
  would let the autoscaler scale the canary back up; the gap is the **missing restore on conclusion**.
  Needs its own ticket.
- [verified: `ControlLoopScaleDownFloorTest` (`aether/aether-control`) — drives a real scale-up, feeds
  the autoscaler's own Put back through the production feeder as consensus does, then asks for a
  scale-down and asserts the count actually falls. The feedback step is load-bearing: `applyScaling`
  wrote the correct floor to the in-memory model and the wrong one to the durable record, so a test
  that stops at the emitted value passes against the defect]
- [verified: opposite polarity pinned in the same class — a genuine operator floor still refuses a
  scale-down through it, so an implementation that simply dropped `minInstances` does not pass]
