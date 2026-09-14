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
  `Dispatched` outcome) and, on EVERY join observation of that id — the core channel (`NodeJoined`) or the
  worker channel (`WorkerJoinDecision`, now routed to the CTM as well as the deployment manager) — compares
  intent with the role membership holds for the node. A disagreement is logged at WARN naming node id,
  intended role, advertised role (`''` marked `(absent)`) and the resulting classification (derived from the
  same `MemberDescriptor.isCoreRole` the projector classifies by), and recorded in a leader-scoped ledger; an
  agreement clears any entry (INFO). A node with no intent on record (bootstrap, an earlier leader's
  provision, a hand-started node) is never reported — absence of intent is not a mismatch
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRoleMismatchTest.java`
  — `provisionedWorker_joiningWithNoRoleLabel_warnsNamingNodeIntendedAndAdvertised` and
  `unprovisionedNode_joiningWithNoRoleLabel_doesNotWarn` are a pair over one log capture with mutually
  exclusive expectations; `provisionedCore_joiningOnTheWorkerChannel_warnsAndIsListed` covers the other
  direction; `provisionedCore_advertisingCore_doesNotWarn` is the agreeing control].
- **The advertised role is what membership classified by, not the observer's first sighting** (verify-1120
  SF-1). `TopologyObserver.addNode` is `putIfAbsent`: a node the leader learns by gossip before its direct
  ANNOUNCE is stored label-less forever, while the FSM merges the later labelled announce under its
  blank-downgrade guard and classifies `core`. Round 1 read the observer and would have WARNed a
  correctly-labelled core as `advertised ''`, contradicting the classification it reported. The comparison
  now reads `MembershipFsm.memberDescriptor(id).role()` through a new `MembershipLiveness.advertisedRole`
  projection (`AetherNode.drainGraceLiveness` wires it; `none()` for an id the FSM does not track — then no
  comparison, never a fabricated blank)
  [verified: `gossipFirstSighting_ofACorrectlyLabelledCore_doesNotWarn` asserts the observer kept the label-less
  sighting as its premise and expects 0 WARNs; `noAdvertisedRoleKnownToMembership_isNotCompared`;
  `DrainGraceLivenessSeamTest.drainGraceLiveness_advertisedRole_readsTheFsmDescriptor` pins the real seam].
  `[known: the JOINED edge fires on the SWIM healthy streak (`UP_HYSTERESIS`), so a label that arrives only
  AFTER the node reached MEMBER is not re-compared until the node rejoins — the FSM classified that join blank
  too, so the WARN is true of the classification; the ledger clears on the next join of the id]`
- **The intent is retained, so a restart in place is re-compared** (verify-1120 BLOCKING-1). Round 1 consumed
  the intent on first sight and dropped the ledger entry on `NodeRemoved`, on the premise that a relaunch
  arrives under a fresh id — true of a CTM replacement, false of a crash/OOM/operator restart, which rejoins
  under the SAME id (a replacement boots from a rendered config carrying `context.nodeId()`); from the
  second boot on the mislabelled node's route read clean and the WARN never re-fired. Now the intent lives
  for the id until `NodeDecommissioned` — the CTM's own forget point, the node retired for good — and every
  rejoin is compared: still mislabelled → re-WARNed and still listed; correctly relabelled → entry cleared. A
  `NodeRemoved` keeps both intent and entry (a restart may follow)
  [verified: `restartInPlace_ofAMislabelledNode_keepsTheEntry_andReWarnsOnRejoin` (the reviewer's probe B
  inverted: ledger non-empty after rejoin, 2 WARNs), `rejoin_nowCorrectlyLabelled_clearsTheEntry`,
  `decommission_forgetsTheIntent_soALaterJoinUnderThatIdIsNotCompared`].
  `[known: a departed node that the CTM actually reaped (instance terminated, never to rejoin) stays listed on
  the minting leader until it is decommissioned or leadership changes — a stale-but-true entry, bounded by
  the number of provisions; NodeRemoved is not distinguishable from a restart at the moment it arrives]`
- **Leader flap** (verify-1120 SF-2). A deposed CTM drops decisions at its `active` gate, so a node that
  restarted while this node was not leading was never re-compared and its entry could go stale on
  re-activation. `activate()` now re-derives the ledger for every retained intent whose node the FSM still
  tracks: still mismatched → re-reported (the WARN fires again on this activation), now agreeing → cleared
  [verified: `reactivation_reDerivesTheLedger_fromWhatMembershipHoldsNow` — two provisioned nodes, one
  relabelled while deposed with its rejoin dropped at the gate (control), re-activation clears exactly it].
  `[known: intents and ledger are in-memory and leader-scoped; a DIFFERENT node taking leadership starts with
  none, so a mismatch logged by the previous leader is neither listed nor re-WARNed until the node rejoins
  under a leader that provisioned it — which, for an id minted by the old leader, is never. The durable
  intent source that would close this (the provider's `aether-role` label inventory the worker reconcile
  already reads) is a follow-up, not this PR]`
  `[unverified: the failover case is not driven end-to-end; the pin is the same-node re-activation in-process]`
- **Operator surface without log access, REST → CLI → docs.** `GET /api/v1/cluster/topology/role-mismatches`
  (`ManagementRoute.CLUSTER_ROLE_MISMATCHES`, LEADER-routed, an exact VIEWER row in
  `ManagementRoutePermissions` via the GET rule — never the prefix fallback) returns the ledger; new CLI
  subcommand `aether cluster topology role-mismatches` (`ClusterTopologyCommand.RoleMismatchesCommand`,
  read-only, table over `mismatches` or `--format json`); documented in `aether/docs/reference/management-api.md`,
  `aether/docs/reference/cli.md` and the versioning table
  [verified: `ManagementRouteCoverageTest` and `ManagementRoutePermissionsTest` green with the new constant;
  `cli-docs-gate` green with the documented invocation counted].
- **The worker-channel wiring is pinned** (verify-1120 SF-3). `AetherNode.collectRouteEntries` routes
  `WorkerJoinDecision` to `clusterTopologyManager::onWorkerJoin`; deleting the entry left 0 node tests red
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/WorkerJoinCtmWiringBootTest.java` — a booted
  single-node cluster's own FSM is fed a `worker` descriptor and one ALIVE sample, the projector's
  `WorkerJoined` DEBUG is the control that the decision hit the router, and the CTM's "observed on
  WorkerJoinDecision" DEBUG for the same id is emitted only inside `onWorkerJoin`].
- **Node-side, at boot.** `Main.collectNodeLabels` now WARNs when `AETHER_ROLE` is unset, stating the
  explicit default ("this node advertises no role label and every peer will classify it as CORE") and how a
  worker must be launched
  [verified: `aether/node/src/test/java/org/pragmatica/aether/MainNodeRoleAbsentWarnTest.java` — absent
  role WARNs, present role does not, same capture; reverting the hunk reddens the first].
- **Pinned unchanged:** blank ≡ `core` and only `worker` is excluded
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/fsm/MemberDescriptorRoleClassificationTest.java`].
- **Bounds, stated.** A core-intended node that joined labelled `worker` departs on the worker-leave channel,
  which the CTM does not receive; its entry stays until it rejoins, is decommissioned, or the leader changes —
  the same retention as every other departed entry above. Bootstrap nodes are undetectable by construction (no
  intent is minted for them). Intents for a dispatched node that never joins are never pruned (bounded by
  provisions). `[unverified: no multi-node run; every pin is an in-process CTM over a stub lifecycle manager,
  a real TopologyObserver and a test-side FSM projection, plus one single-node boot for the wiring]`
