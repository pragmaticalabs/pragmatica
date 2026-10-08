### Added (2026-10-07 — #1543 part E2: worker replacement, EXTERNAL mode, community-safe reduction, and the REST surface)
- **A worker is replaced with no voter swap.** `JOINING` goes straight to `CANARY` for a worker, a failed worker canary rolls back
  directly (there is no seat to give back), and the replacement is provisioned with the worker role through a source-explicit
  `ClusterTopologyManager.provisionReplacement(..., SourceName)`. "Caught up" for a worker is ON_DUTY in the ready view (no core
  admission applies).
- **Community reduction honours the pairing.** `CommunityPlacementReconciler.protectReplacements(...)`: a live pairing's original
  (until it retires) and its replacement are never the node a community reduction removes. Without it the surge worker reads as an
  excess member and the reduction removes whichever sorts first, which can be the replacement itself.
- **EXTERNAL mode.** `POST /api/v1/nodes/replace/{id}` with `{"replacement": "<fresh id>"}`: the operator names a fresh id and starts
  that node itself; the leader provisions nothing and runs the same phases. The admission intent (the capacity reservation
  `CoreAdmission` admits a core by) is committed in the SAME transaction as the record. An id that is already a member, paired or
  reserved is refused (409). Without `replacement` the leader provisions the node (CTM mode).
- **Routes** (OPERATOR, route target LEADER): `POST /api/v1/nodes/replace/{id}`, `GET /api/v1/nodes/replacements`,
  `POST /api/v1/nodes/replacements/settle/{id}` (`{"outcome": "keep-new" | "roll-back"}`, for `FAILED_KEPT_BOTH`). Refusals answer
  404 (unknown node), 400 (unsupported role, bad outcome, unparsable id) and 409 (one at a time, id in use, genesis-voter id, fleet full, source required, not the leader, nothing to settle, concurrent change),
  never 500. Documented in `management-api.md`.
- **Worker paths of the v-2008 rechecks:** a worker replacement lost before the old worker is drained is rolled back (there is no seat to swap back), settling a kept-both worker pair as "roll back" only gives the replacement up, and the leader reconciler counts only CORE pairings' replacements as core capacity (`coreSurgeReplacements`).
- **EXTERNAL capacity is counted, bounded and settled.** The admission intent now exists for workers as well as cores (a worker is admitted
  by `AetherNode.workerAdmissionAllowed` on a reservation of its role). It is counted in the fleet ledger in the same transaction as the
  record when the ledger exists and its inventory is complete; otherwise it carries an `external-uncounted` marker and returns no slot.
  An EXTERNAL replacement that would exceed the fleet limit is refused (409 `FleetFull`), as is one whose node has no provisioning source
  while the ledger counts (409 `SourceRequired`). When the record reaches `ROLLED_BACK` a counted reservation is released, and at `DONE`
  it becomes the observed reservation of the live node, so retiring the node later returns the slot and drops the id from admission.
  Also refused with 409: a core replacement whose chosen id was a genesis voter (`FormerVoterIdentity`).
- **A replacement is DONE only after the old node's instance is confirmed terminated at the provider.** Found by the cold-boot safety test: after
  retirement the CTM's reap of the departed node was refused ("Cannot terminate without a committed capacity source binding": a node CTM did not
  provision has a reservation only once a listing has observed it), not retried, and the record still reached DONE: in a cloud a leaked, billing VM.
  `ClusterTopologyManager.reapRetired(node, source)` lists the instance (which commits the missing observed reservation), terminates it if listed,
  re-lists, and succeeds only on an empty or all-stopped listing; a failed listing, an instance still listed or a status the provider cannot state is a
  failure carrying the instance and the cause, never "gone". It honours the retirement refusal (a voter is not reaped) and is idempotent, so a new
  leader repeats it. `RETIRING_OLD -> DONE` requires it; at the phase deadline with the termination unconfirmed the record is `FAILED_KEPT_BOTH`
  ("old node retired but its instance is not confirmed terminated at the provider (<cause>); terminate it, then settle keep-new"), and the
  `node-replacement-failed-kept-both` event carries that reason. A rollback is `ROLLED_BACK` only once the replacement's instance is confirmed gone;
  after the retiring budget it too keeps both, naming the cause. The general retirement paths (scale-down, departure) still use the log-and-drop reap: #2062.
- **A node that arrived on an uncounted reservation keeps it** (no counting ledger): it is that node's only admission intent, needed again if a worker
  loses its community assignment. A rollback, where the node never arrived, still drops it.
- **Operator events** are derived from the committed record on every node and raised only by the cluster-events owner, so exactly one
  start / completion / failure event (and its recovery pair) is raised per transition; the wiring of that owner gate and of the ready
  view the worker path reads are pinned by boot tests.
- **The "in-flight node replacement" INFO line, logged on every reconcile pass, is now DEBUG.**
- [verified: `NodeReplacementReconcilerTest` worker rows; `CommunityPlacementReconcilerTest` (+3: unpaired extra member is reduced,
  a paired original and replacement are both protected, the protected replacement is never the one removed; probes reddened each);
  `NodeReplacementRoutesTest` (9; probes: chosen-id ignored, settle outcome swapped, refusal status changed → each red);
  `NodeReplacementWiringAdmissionTest` (2; the committed intent is checked against the real `CoreAdmission` predicate with a
  no-intent control; probes: intent dropped, worker given an intent, CTM given an intent, intent role wrong → each red);
  `EmberNodeReplacementTest`: a worker replaced in a 3-core cluster (electorate sampled on every node == the same 3 cores, no
  `SWAPPING` phase, old worker gone, replacement advertises the worker role; probe: worker provisioned as core → red) and an
  EXTERNAL-mode core (the leader starts nothing, the operator starts the chosen id, the phases complete with 3 voters on every sample).]
- [unverified: an Ember scenario with a worker INSIDE an explicit community — the harness cannot express a community policy, so the
  reduction protection is pinned at the reconciler (unit) level only; Ember's in-process admission never consults the reservation, so the
  EXTERNAL-mode core admission intent is pinned by `NodeReplacementWiringAdmissionTest`, not by Ember; stream ISR hand-off is not part
  of the retirement gate; the rolled-back/retired node is terminated by `ctm.drainNode(REPLACED)` until #1111's give-up terminate lands;
  Ember is in-JVM.]
