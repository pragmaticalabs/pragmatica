### Fixed (2026-09-14 — #906: lost-update race in `WorkerDeploymentManager` assignment recompute)
- **`computeAndApplyAssignment`'s "assignment count only" branch was a read-then-put**: it read the
  deployment record, decided no deploy/undeploy was needed, and then `put` a copy of that stale record
  with the new instance count. A state transition landing in between — `updateDeploymentState`'s
  `computeIfPresent(withState)` driven by the slice load/activate promise chain — was silently
  overwritten. In a wired manager that leaves the worker's record at `LOADING` while the slice is
  `ACTIVE`, so a later scale-to-zero finds `needsUndeploy` false and never tears the slice down.
  [unverified: the manager is constructed in `AetherNode` but not subscribed to any KV or membership
  event at this head, so no running node reaches this path — #1125]
  [mechanism: `onMembershipChange` → `computeAndApplyAssignment` reads `deployments.get`, transition
  runs `deployments.computeIfPresent`, recompute writes `deployments.put` of the pre-transition value]
- The branch now updates the instance count with `computeIfPresent`, so the write composes with a
  concurrent transition instead of replacing it.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/worker/deployment/WorkerDeploymentManagerTest.java`
  — a latched map parks the recompute thread between its read and its write, the transition lands,
  and the record must still read `ACTIVE`]
- The same hunk closes resurrection-after-removal: the old `put` re-inserted a record read before
  `onDirectiveRemove` tore the slice down, leaving a torn-down slice recorded as `ACTIVE`;
  `computeIfPresent` on the removed key is a no-op.
  [verified: `removalLandingDuringAssignmentRecomputation_isNotResurrected` in the same test class]
- The deployments map is now injectable through a factory overload used only by that probe; the
  production overloads are unchanged.
- The deploy and undeploy branches keep their read-then-act shape: the deploy branch is only entered
  when no record exists (nothing sets `IDLE`), so it cannot race `computeIfPresent`; it can still race
  a concurrent deploy (duplicate load/activate, duplicate state puts) and a concurrent removal (an
  orphan `ACTIVE` record) — #1125. The undeploy branch removes the record regardless of which state
  it has reached. [design intent — unverified]
