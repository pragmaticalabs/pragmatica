### Removed (2026-09-14 — #673: the never-wired worker group-splitting chain is deleted; `[worker] max_group_size` is refused at parse)
- **Archaeology, so the deletion is deliberate rather than tidy.** `GroupMembershipTracker` was constructed once in
  `AetherNode.activateWorkerMode` into a local never referenced again; `updateMember`/`removeMember` had zero
  non-test callers; `GroupAssignment.computeGroups` ran only from its own test. Communities are minted one per
  source (`ClusterDeploymentState.WORKER_COMMUNITY_SUFFIX = "-w-0"`). The 2026-06-10 audit recorded the chain
  unwired; the 2026-08-28 owner note made it a wire-or-delete decision; the 2026-09-14 ruling is DELETE — wiring it
  means per-community governor election and spokesman assignment, post-GA work.
- Deleted: `aether/node/.../worker/group/` (`GroupAssignment`, `GroupMembershipTracker`, `WorkerGroupId`), the
  `AetherNode` construction, `GroupAssignmentTest`; `WorkerConfig.maxGroupSize` and `DEFAULT_MAX_GROUP_SIZE` and the
  factory overloads' parameter.
- **`max_group_size` is REFUSED at parse**, PF-style (the #675 PF-26 shape): a present key fails `WorkerConfigLoader`
  with a message naming #673 and "one per source" — not WARN-and-ignore, because an inert key that stays accepted is
  the defect class this ticket names, and pre-GA an honest break beats a lie. An absent key parses as before
  [verified: `WorkerConfigLoaderMaxGroupSizeRemovedTest` — values 1, 3 and 100 all refused with the message, absent
  parses; red when `refuseRemovedKeys` is reverted. Replaces `WorkerConfigLoaderMaxGroupSizeTest`, whose "3 is kept"
  pin described a knob with no effect].
- **The refusal triggers on PRESENCE, not on type** (`WorkerConfigLoader.refuseRemovedKeys` reads the key via
  `getString`, not `getInt`). `TomlDocument.getInt` yields `none()` for a boolean, float, array or unparseable
  string, so the first cut of this refusal would have SILENTLY ACCEPTED `max_group_size = 3.5` while the docs said
  the key is refused — a key documented as refused but still accepted is worse than either state alone
  [verified: `WorkerConfigLoaderMaxGroupSizeRemovedTest.loadFromString_nonIntegerMaxGroupSize_isStillRefused` —
  `3.5`, `true`, `"100"`, `"unlimited"` and `[1, 2]` all refused; all five parse successfully against `getInt`].
- **`[worker]` config is now read by NO production code, and the catalog says so.** #673 removed the last reader of
  `WorkerConfig.groupName` (the deleted `GroupMembershipTracker` construction); `zone`, `cluster_port`, `swim_port`,
  the heartbeats, `advertise_address` and `metrics_aggregation_interval_ms` had no reader before it either. Tree-wide
  `.workerConfig(` occurs once — the loader's own constructor call [verified: `git grep` over 5,032 tracked files;
  control `.storageConfig(` = 14 files]. Catalog row 99 therefore stays **Partial** and states this, rather than
  being graded up at the commit that removes its last reader.
- Docs: `configuration.md` `max_group_size` row and example; its `group_name`/`zone` rows and the paragraph below
  them, which claimed NodeId-derived zones (removed in #592) and precedence "for group computation" (deleted here) —
  both false, and invisible to a residual grep built from the deleted vocabulary, since the sentence names none of
  it. `[worker] zone` is now distinguished from the live `AETHER_ZONE` env var, which the same doc conflated with it.
  `ClusterIdentityEnv`'s `AETHER_ZONE` rationale cited `GroupAssignment` as its consumer; corrected, and the entry
  kept — the SWIM `zone` label still has two live readers, `ClusterTopologyManagerRecord` and `ClusterTopologyRoutes`.
  `architecture/05-worker-pools.md` "Group Formation" rewritten to the one-per-source fact;
  `specs/docker-scaling-test-spec.md` marked ARCHIVED with the reason.
- Catalog rows 97 and 99 keep their status labels with the existing **"left as found pending the catalog legend
  ruling"** note, as the six rows #943 left do. An earlier cut of this change graded row 97 `Removed`, a status the
  document's own legend does not define — `scripts/catalog-stats.sh --check` refuses it, and admitting it to the
  legend is the deferred ruling, not this PR's to make [verified: `catalog-stats.sh --check` — "ok, 227 feature rows
  across 5 statuses match the generated block", exit 0; it exited 1 naming row 97 before the fix].
- Released `CHANGELOG.md`'s rc3 entry cites `WorkerConfigLoaderMaxGroupSizeTest`, deleted here. Left UNTOUCHED, and
  not because it is harmless: `scripts/changelog-check.sh` fails any PR that edits `CHANGELOG.md` without the
  `release-prep` label, so annotating it in place would have reddened the `fragment` gate to tidy a citation. The
  entry is true as shipped at rc3; this fragment names both the old test and its replacement, so the annotation
  reaches `CHANGELOG.md` at release prep through the sanctioned path.
- Kept: `CommunitySizing.targetSize` — stamped onto the wire `NodeRoleValue` through the CDM context; a wire-shape
  change, drafted as its own ticket in the fix report.
- [unverified: no cluster run — neither the deleted chain nor the `[worker]` keys were reachable at runtime by
  construction, which is an argument, not an observation].
