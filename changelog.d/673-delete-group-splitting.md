### Removed (2026-09-14 — #673: the never-wired worker group-splitting chain is deleted; `[worker] max_group_size` is refused at parse)
- **Archaeology, so the deletion is deliberate rather than tidy.** `GroupMembershipTracker` was constructed once in
  `AetherNode.activateWorkerMode` into a local never referenced again; `updateMember`/`removeMember` had zero
  non-test callers; `GroupAssignment.computeGroups` ran only from its own test. Communities are minted one per
  source (`ClusterDeploymentState.WORKER_COMMUNITY_SUFFIX = "-w-0"`). The 2026-06-10 audit recorded the chain
  unwired; the 2026-08-28 owner note made it a wire-or-delete decision; the 2026-09-14 ruling is DELETE — wiring it
  means per-community governor election and spokesman assignment, post-GA work.
- Deleted: `aether/node/.../worker/group/` (`GroupAssignment`, `GroupMembershipTracker`, `WorkerGroupId`), the
  `AetherNode` construction, `GroupAssignmentTest`; `WorkerConfig.maxGroupSize` and `DEFAULT_MAX_GROUP_SIZE` and the
  factory overloads' parameter. Repo-wide residual grep (Java, docs, TOML, scripts, dashboard sources; `target/`
  excluded; control: a live worker key matched) is clean apart from the corrected docs below.
- **`max_group_size` is REFUSED at parse**, PF-style (the #675 PF-26 shape): a present key fails `WorkerConfigLoader`
  with a message naming #673 and "one per source" — not WARN-and-ignore, because an inert key that stays accepted is
  the defect class this ticket names, and pre-GA an honest break beats a lie. An absent key parses as before
  [verified: `aether/aether-config/src/test/java/org/pragmatica/aether/config/WorkerConfigLoaderMaxGroupSizeRemovedTest.java`
  — values 1, 3 and 100 all refused with the message, absent parses; red at the base (3 parsed), red again when the
  refusal is disabled. Replaces `WorkerConfigLoaderMaxGroupSizeTest`, whose "3 is kept" pin described a knob with
  no effect].
- Docs: `configuration.md` row and example, `feature-catalog.md` rows 97 (Removed) and 99 (Complete, scoped),
  `architecture/05-worker-pools.md` "Group Formation" rewritten to the one-per-source fact,
  `specs/docker-scaling-test-spec.md` marked ARCHIVED with the reason.
- Kept: `CommunitySizing.targetSize` — stamped onto the wire `NodeRoleValue` through the CDM context; a wire-shape
  change, drafted as its own ticket in the fix report.
