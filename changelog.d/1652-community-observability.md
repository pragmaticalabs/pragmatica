### Added (2026-09-28 — #1652: community state read route and community lifecycle cluster events)
- **A worker community's state can now be read instead of inferred from node lists and logs.**
  `GET /api/v1/cluster/communities` and `GET /api/v1/cluster/communities/{id}` (served by the leader) report
  each community's lifecycle state (`FORMING` / `ACTIVE` / `DEGRADED` / `DISSOLVED`), target size, role,
  mint and dissolve instants, governor, roster and community term, joined from the two committed records.
  A half with no committed record is `null`; an unknown id is a 404. CLI: `aether cluster communities [<id>]`.
  [verified: Ember on cloudbb-2 at af036c27e, 1/1 — `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/CommunityObservabilityForgeTest.java`]
- **`liveMembers` is the leader's instantaneous view, not committed state**: roster members still directed to
  the community that the leader has not observed absent. It is the same count the per-community FSM compares
  against the viability floor (one shared computation, `CommunityLiveMembers`), and it is `null` where the
  serving node cannot observe it (not the leader, liveness unwired, no roster) rather than `0`.
  [verified: Ember on cloudbb-2 at af036c27e, 1/1 — `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/CommunityObservabilityForgeTest.java` —
  it drops below the floor after a real worker kill]
- **Four new cluster events** (wire tags 1740–1743): `COMMUNITY_MINTED`, `COMMUNITY_STATE_CHANGED` (one event
  per edge, `from`/`to` in `details`; WARNING only for the edge into `DEGRADED`), `COMMUNITY_MEMBER_JOINED` and
  `COMMUNITY_MEMBER_LEFT` (roster changes: assignment, not liveness; a force-killed worker did not produce MEMBER_LEFT within 180 s of the kill, one Ember run; see #1717. [unverified: no live MEMBER_LEFT trigger is demonstrated; the roster diff is pinned at unit level]). They are derived from the committed
  `CommunityValue` / governor-roster writes, so every node observes them and only the cluster-events owner publishes them
  (the owner-gated delivery contract, `guarantees.md` row 14b: at-least-once across an ownership handover).
  [verified: Ember on cloudbb-2 at af036c27e, 1/1 — `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/CommunityObservabilityForgeTest.java` —
  minted, exactly one member joined per admitted worker, FORMING→ACTIVE and ACTIVE→DEGRADED on a live cluster]
- The `→ DISSOLVED` edge for placement-policy retirement is not drivable in an in-JVM cluster; it is pinned by
  committing the retirement write exactly as `CommunityPlacementReconciler.markDissolved` does, through the real
  applier and the node codec. [mechanism: the applier's `ValuePut` for the retirement write;
  `aether/node/src/test/java/org/pragmatica/aether/api/CommunityLifecycleAggregationTest.java`]
- **What the core cannot record.** A worker that loses the core fences itself locally and writes nothing, so
  the core never shows that community `DISSOLVED`; it shows `liveMembers` dropping and the `ACTIVE → DEGRADED`
  edge. The worker's own fence is visible only on that worker, in the `coreAbsence` field of its LOCAL
  `GET /api/v1/cluster/membership`. `DISSOLVING` is not a state that occurs today (#1656).
