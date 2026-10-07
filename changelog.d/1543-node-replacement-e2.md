### Added (2026-10-07 — #1543 part E2: worker replacement, EXTERNAL mode, community-safe reduction, and the REST surface)
- **A worker is replaced with no voter swap.** `JOINING` goes straight to `CANARY` for a worker, a failed worker canary rolls back
  directly (there is no seat to give back), and the replacement is provisioned with the worker role through a source-explicit
  `ClusterTopologyManager.provisionReplacement(..., SourceName)`. "Caught up" for a worker is ON_DUTY in the ready view (no core
  admission applies).
- **Community reduction honours the pairing.** `CommunityPlacementReconciler.protectReplacements(...)`: a live pairing's original
  (until it retires) and its replacement are never the node a community reduction removes. Without it the surge worker reads as an
  excess member and the reduction removes whichever sorts first, which can be the replacement itself.
- **EXTERNAL mode.** `POST /api/v1/nodes/replace/{id}` with `{"replacement": "<fresh id>"}`: the operator names a fresh id and starts
  that node itself; the leader provisions nothing and runs the same phases. For a core the admission intent (the capacity reservation
  `CoreAdmission` admits a core by) is committed in the SAME transaction as the record. An id that is already a member, paired or
  reserved is refused (409). Without `replacement` the leader provisions the node (CTM mode).
- **Routes** (OPERATOR, route target LEADER): `POST /api/v1/nodes/replace/{id}`, `GET /api/v1/nodes/replacements`,
  `POST /api/v1/nodes/replacements/settle/{id}` (`{"outcome": "keep-new" | "roll-back"}`, for `FAILED_KEPT_BOTH`). Refusals answer
  404 (unknown node), 400 (unsupported role, bad outcome) and 409 (one at a time, id in use, nothing to settle, concurrent change),
  never 500. Documented in `management-api.md`.
- **The "in-flight node replacement" INFO line, logged on every reconcile pass, is now DEBUG.**
- [verified: `NodeReplacementReconcilerTest` worker rows; `CommunityPlacementReconcilerTest` (+3: unpaired extra member is reduced,
  a paired original and replacement are both protected, the protected replacement is never the one removed; probes reddened each);
  `NodeReplacementRoutesTest` (6; probes: chosen-id ignored, settle outcome swapped, refusal status changed → each red);
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
