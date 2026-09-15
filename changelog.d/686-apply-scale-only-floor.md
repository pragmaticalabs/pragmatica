### Changed (2026-09-14 — #686: `cluster apply` is documented as scale-only in rc4 in both references; the client-side wave rollout is registered as known-unwired)
- CTO ruling (b): plain `aether cluster apply` / `POST /api/v1/cluster/config` perform scale-only plans via the leader
  (a fenced desired-count write the leader's reconciler actuates); non-scale changes — sources, roles, runtime, source
  fields, cluster-level fields — are not applicable through `apply` in rc4, and a rollout of them needs a new cluster.
  `cli.md` and `management-api.md` now say exactly this (the CLI page previously pointed at `--resume`/`--rollback`
  as if it were a first-time path; the API page said nothing).
- `cli.md` also states what `--resume`/`--rollback` do in rc4: both load the cluster's apply state and abort without it,
  and the only writer of that state is the unwired client-side rollout, so no rc4 command creates it — absent a state
  file from a pre-rc4 CLI both report `No apply state found for cluster '<name>'.` and `WaveExecutor` is unreachable
  end to end [verified: `ApplyState.save` occurs exactly twice in `*/src/main/java/*`, both inside
  `ApplyOrchestrator.executeAndPersist`/`markStateFailed`, below the unwired `apply`; positive control `ApplyState.load`
  = 2 hits in the same space].
- Why not wire `ApplyOrchestrator.apply`/`WaveExecutor`: they actuate through the CLI's `ComputeProvider` from the
  operator's machine while the leader actuates from KV — two actuation authorities over one fleet — and their only
  evidence is their own unit tests. They are registered as known-unwired in the dead-surface gate so a silent wire
  reddens and a silent deletion fails to compile; the resume/rollback reachability of `WaveExecutor.execute` is the
  control.
- **Both `apply` overloads are registered, not just the three-argument one.** `BytecodeReachability` matches the
  descriptor the call site names and does not record edges inside the declaring class, so registering
  `apply(desired, stored, skipConfirmation)` alone left `apply(desired, stored)` — the overload `ApplyOrchestratorTest`
  itself uses — a silent door to the same rollout
  [verified: `aether/dead-surface-gate/src/test/java/org/pragmatica/aether/deadsurface/KnownUnwiredSurfacesTest.java`,
  enabled; wiring `ApplyOrchestrator.apply(desired, stored)` into `ClusterApplyCommand.resumeApply` was GREEN before
  this change and reddens after it, and wiring the three-argument overload reddens in both].
- A set-equality assertion pins the registration against a future overload: the hand-written entries must equal every
  `apply` overload declared on `ApplyOrchestrator`, so adding one reddens the gate instead of silently escaping it
  [verified: removing the two-argument entry reddens with the missing descriptor printed, with nothing wired].
- [unverified: no `aether cluster apply` was run against a live node — the scale-only and `No apply state found`
  statements are traced to `ClusterConfigApplier.classify`, `ClusterConfigRoutes.executeDiff` and `ApplyOrchestrator`
  by reading, not by execution]
- The server-side wave design with one actuation authority is drafted as a ticket in the fix report.
