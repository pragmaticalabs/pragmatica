### Fixed (2026-09-14 — #906: lost-update race in `WorkerDeploymentManager` assignment recompute)
- **`computeAndApplyAssignment`'s "assignment count only" branch was a read-then-put**: it read the
  deployment record, decided no deploy/undeploy was needed, and then `put` a copy of that stale record
  with the new instance count. A state transition landing in between — `updateDeploymentState`'s
  `computeIfPresent(withState)` driven by the slice load/activate promise chain — was silently
  overwritten, leaving the worker's record at `LOADING` while the slice was `ACTIVE`, so a later
  scale-to-zero found `needsUndeploy` false and never tore the slice down.
  [mechanism: `onMembershipChange` → `computeAndApplyAssignment` reads `deployments.get`, transition
  runs `deployments.computeIfPresent`, recompute writes `deployments.put` of the pre-transition value]
- The branch now updates the instance count with `computeIfPresent`, so the write composes with a
  concurrent transition instead of replacing it.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/worker/deployment/WorkerDeploymentManagerTest.java`
  — a latched map parks the recompute thread between its read and its write, the transition lands,
  and the record must still read `ACTIVE`]
- The deployments map is now injectable through a factory overload used only by that probe; the
  production overloads are unchanged.
- The deploy and undeploy branches keep their read-then-act shape: the deploy branch is only entered
  when no record exists (nothing sets `IDLE`), so no `computeIfPresent` can race it, and the undeploy
  branch removes the record regardless of which state it has reached.
  [design intent — unverified]
