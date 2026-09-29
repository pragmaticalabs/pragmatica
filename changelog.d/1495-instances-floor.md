### Changed (2026-09-24 — #1495: blueprint slices must run at least 3 instances)
- **A blueprint slice's `instances` is now at least 3, and an omitted `instances` means 3 (was 1).**
  Placement puts at most one instance of a slice on a node and a drain halts its node without waiting
  for a replacement, so a single-instance slice went dark on one drain or node failure. At 3 or more,
  one drain or failure leaves the slice at two or more instances
  `[mechanism: SliceAllocationEngine places at most one instance of a slice per node]`. Pre-GA: there
  is no migration path; a blueprint declaring 1 or 2 must be raised before it will publish.
- `SliceSpec.sliceSpec` refuses `instances < 3` (0 and negatives included) with the typed cause
  `SliceSpecError.InstancesBelowMinimum`, which names the artifact and the declared count; it replaces
  the old "Instance count must be positive" text. `BlueprintParser` (every publish, validate and
  blueprint-artifact path) defaults an omitted `instances` to 3 and `minAvailable` keeps defaulting to
  `ceil(instances/2)` = 2; `minAvailable > instances` stays refused
  `[verified: aether/slice BlueprintParserTest.InstanceFloorCases, SliceSpecTest — unit level]`.
- A transitive dependency the blueprint does not list now deploys at 3 instances instead of 1, so a
  slice the blueprint depends on is not the one left at a single instance
  `[verified: aether/slice BlueprintExpanderTest.WithDependencies — unit level]`.
- `POST /api/v1/deploy` (and `aether deploy --canary|--blue-green|--rolling`) writes its `instances`
  onto every slice target of the blueprint, so it carries the same floor and default: omitted means 3
  (was 1), fewer than 3 answers `400` with `DeployRouteError.INSTANCES_BELOW_MINIMUM` before the
  deployment manager is reached `[verified: aether/node DeployRouteStatusTest.InstanceFloor — route level]`.
- A blueprint the parser refuses — including `instances` below 3 and malformed TOML — now answers `400`
  instead of `500` on `POST /api/v1/blueprints` and the artifact publish/deploy routes (the #569 class):
  `BlueprintService` wraps the parser's cause in `BlueprintRejected` (`HttpStatusAware`, 400), which
  keeps the typed cause as its `origin` and quotes its message, so the body names the floor
  `[verified: aether/node BlueprintServiceTest.PublishRefusalStatusTests — real route + real service]`.
  `POST /api/v1/blueprints/validate` is unchanged: it answers 200 with `valid: false` by contract.
- **BREAKING (pre-GA, allowed): the floor is a runtime invariant too (owner ruling).**
  - `POST /api/v1/scale` and `aether scale` refuse fewer than 3 instances with `400`
    (`ScaleRouteError.InstancesBelowFloor`, which names the floor), checked before the node is read. A count
    below the slice's own `minAvailable` now also answers `400` (`ScaleRouteError.InstancesBelowMinAvailable`)
    where it used to be an untyped `500`. `[verified: aether/node ScaleRouteFloorTest — route level;
    aether/forge SliceDeploymentTest.scaleSlice_belowTheFloor_isRefusedWhileAtTheFloorIsAccepted — Forge]`
  - The autoscaler CLAMPS a scale-down at 3: its scale-down floor is `max(minAvailable, 3)`, so a decision
    that would cross 3 is held there and recorded `HELD` by the `MIN_INSTANCES` guard. The operator's
    `minAvailable` in the slice target is left as written. `[verified: aether/aether-control
    ControlLoopScaleDownFloorTest.InstanceFloorAtRuntime — unit level]`
  - `SliceSpec` refuses `minAvailable < 2` with the typed `SliceSpecError.MinAvailableBelowFloor`, because
    `minAvailable` is the fewest ACTIVE instances the scale-down and drain guards may leave. A blueprint
    declaring `minAvailable = 1` must be raised before it will publish. `[verified: aether/slice
    SliceSpecTest — unit level]`
  - `[unverified: operator-initiated drain (POST /api/v1/nodes/drain) checks the core disruption budget,
    not a slice's minAvailable; pending a ruling]`
