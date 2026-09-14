### Fixed (2026-09-14 — #689: a provisioned node whose role label never arrives was silently classified CORE, and nothing reported the mismatch)
- **A node's role is a self-asserted label** (`AETHER_ROLE` → `aether-role` → `NodeInfo` `role`), and
  `MemberDescriptor.isCoreRole(role) = !"worker".equals(role)` counts a blank or unknown label as core. That
  default is deliberate and is NOT changed here (acting on an unresolved view is the dangerous direction for
  the core tier; blank→worker would trade a silent non-fence for a spurious fence on a merely-late label).
  What was missing is any signal: an intended worker whose label was lost — env not threaded, user-data
  render dropped it, image booted without it — joined the core set on every peer, and every community-tier
  mechanism gated on "positively not a core" (the #590 core-absence fence first) was suppressed on it with
  nothing anywhere saying why. Measured as `armed=true … fenced=false` and misdiagnosed as a broken fence
  [mechanism: `MembershipDeltaProjector.processJoined` routes a blank-role edge to the CORE channel;
  the core-absence suppressor `AetherNode` wires into `CoreAbsenceDetector` is
  `cores.isEmpty() || cores.contains(config.self())`].
- **Leader-side detection, from the two halves the leader already holds.** `ClusterTopologyManagerRecord`
  keeps the `intendedRole` of every node it provisions (`provisionReplacement`, recorded only on a
  `Dispatched` outcome) and, when that node is first observed — on the core channel (`NodeJoined`, label
  read back from the `TopologyObserver`) or on the worker channel (`WorkerJoinDecision`, now routed to the
  CTM as well as the deployment manager) — compares intent with the advertised label. A disagreement is
  logged at WARN naming node id, intended role, advertised role (`''` marked `(absent)`) and the resulting
  classification, and recorded in a leader-scoped ledger. The intent is consumed on first observation; a
  node with no intent on record (bootstrap, an earlier leader's provision, a hand-started node) is never
  reported — absence of intent is not a mismatch
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRoleMismatchTest.java`
  — `provisionedWorker_joiningWithNoRoleLabel_warnsNamingNodeIntendedAndAdvertised` and
  `unprovisionedNode_joiningWithNoRoleLabel_doesNotWarn` are a pair over one log capture with mutually
  exclusive expectations; `provisionedCore_joiningOnTheWorkerChannel_warnsAndIsListed` covers the other
  direction; `provisionedCore_advertisingCore_doesNotWarn` is the agreeing control. Removing the core-channel
  check reddens the first and the ledger test; never recording the intent reddens all three positive tests].
- **Operator surface without log access.** New `GET /api/v1/cluster/topology/role-mismatches`
  (`ManagementRoute.CLUSTER_ROLE_MISMATCHES`, LEADER-routed, an exact VIEWER row in
  `ManagementRoutePermissions` via the GET rule — never the prefix fallback) returns the ledger; an entry is
  dropped when the node departs (`NodeRemoved`/`NodeDecommissioned`), since a relaunch arrives under a fresh
  id. Documented in `aether/docs/reference/management-api.md` and the versioning table
  [verified: `ledger_listsTheMismatch_andDropsItWhenTheNodeIsRemoved`; `ManagementRouteCoverageTest` and
  `ManagementRoutePermissionsTest` green with the new constant].
- **Node-side, at boot.** `Main.collectNodeLabels` now WARNs when `AETHER_ROLE` is unset, stating the
  explicit default ("this node advertises no role label and every peer will classify it as CORE") and how a
  worker must be launched
  [verified: `aether/node/src/test/java/org/pragmatica/aether/MainNodeRoleAbsentWarnTest.java` — absent
  role WARNs, present role does not, same capture; reverting the hunk reddens the first].
- **Pinned unchanged:** blank ≡ `core` and only `worker` is excluded
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/fsm/MemberDescriptorRoleClassificationTest.java`].
- **Bounds, stated.** The ledger is in-memory and leader-scoped, like the intents (a new leader starts with
  none). A core-intended node that joined labelled `worker` departs on the worker-leave channel, which the
  CTM does not receive, so its entry stays until the leader changes `[design intent — unverified: no
  multi-node run; every pin is an in-process CTM over a stub lifecycle manager and a real TopologyObserver]`.
