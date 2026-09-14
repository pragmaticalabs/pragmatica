### Changed (2026-09-14 — #686: `cluster apply` is documented as scale-only in rc4 in both references; the client-side wave rollout is registered as known-unwired)
- CTO ruling (b): plain `aether cluster apply` / `POST /api/v1/cluster/config` perform scale-only plans via the leader
  (a fenced desired-count write the leader's reconciler actuates); non-scale changes — sources, roles, runtime, source
  fields, cluster-level fields — are not applicable through `apply` in rc4, and a rollout of them needs a new cluster.
  `cli.md` and `management-api.md` now say exactly this (the CLI page previously pointed at `--resume`/`--rollback`
  as if it were a first-time path; the API page said nothing).
- Why not wire `ApplyOrchestrator.apply`/`WaveExecutor`: they actuate through the CLI's `ComputeProvider` from the
  operator's machine while the leader actuates from KV — two actuation authorities over one fleet — and their only
  evidence is their own unit tests. They are registered as known-unwired in the dead-surface gate so a silent wire
  reddens (`assertFalse(reachable(ApplyOrchestrator.apply))`) and a silent deletion fails to compile; the resume/
  rollback reachability of `WaveExecutor.execute` is the control
  [verified: `aether/dead-surface-gate/src/test/java/org/pragmatica/aether/deadsurface/KnownUnwiredSurfacesTest.java`,
  enabled; wiring the fresh apply into `ClusterApplyCommand` reddens it]. The server-side wave design with one
  actuation authority is drafted as a ticket in the fix report.
