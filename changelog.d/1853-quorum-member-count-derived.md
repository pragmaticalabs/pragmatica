### Fixed (2026-10-03 — #1853: QuorumLossDetector member count was push-only and missed the voter install, so a bootstrap race left quorum=false forever and the node unarmed)

- **The detector's member count was PUSHED by whoever noticed a change, and nobody noticed the voter install.**
  `AetherNode.propagateMemberCount` computed `strictCoreMembers ∩ voters ∩ coreObservedMembers` from a `voters`
  snapshot and stored the result in the detector. It ran on three triggers only: the presence-sampler edge, the
  FSM death edge and the FSM Member-boundary edge. When the last sampler edge landed before genesis installed the
  voters, the count was computed against an empty electorate (0) and never again (rc4 cloud run 9: `/api/v1/health`
  quorum=false, "0 / required 3", on 4 of 5 nodes for 17 minutes with consensus active; the bootstrap failed with
  "Quorum not established: 5/3 nodes healthy"). **Safety consequence:** the detector's arm latch only sets on an
  evaluation that sees a quorate count, so a node stuck at 0 never armed and would not have self-fenced on a later
  real quorum loss.
- **The count is now derived from current state on every read.** `QuorumLossDetector` takes a `memberCountSupplier`
  and `onMemberCountChanged(int)` is replaced by the value-less edge input `reevaluate()`. `AetherNode.derivedMemberCount`
  is one allocation-free pass over the FSM (`MembershipFsm.strictCoreObservedVoterCount`) against the voter
  configuration read once per evaluation; health, status, the snapshot and both firing checks all read it live, so a
  missed trigger can no longer leave a stale value. `QuorumLossSnapshot.from` reads the count once so its fields
  cannot disagree. Reads are observation-only: they never arm the detector or open a drain window.
- **Every input change now re-evaluates the detector.** The arm latch and below-threshold window are transitions,
  so they still need an evaluation at each input change. Added the two that had no trigger: a voter-configuration
  install (genesis, §4 command, sync adoption — also the threshold's source) and a member's reachability latch
  flipping true (`MembershipFsm.onReachabilityLatched`, a new once-per-member edge staged with the monitor
  released, like the transition sink).
  `[verified: aether/node/src/test/java/org/pragmatica/aether/node/AetherNodeQuorumCountTriggersTest.java]`
  covers the race (sampler edge before voter install, then install: quorum true and armed), the later self-fence in
  that ordering, and one isolated test per input (voter install, reachability latch, Member boundary, sampler edge,
  death), each asserting the detector is not armed before the trigger. These are in-JVM unit tests over the real
  FSM and detector, not a multi-node run.
- Read cost: the replaced push made two member walks and built two sets per trigger; the derivation is one pass
  with no allocation and takes member monitors one at a time. `[unverified: no timing was measured]`.
- Recovery action: none after upgrading. On the unfixed build the stuck count healed only if a later presence edge or
  Member-boundary crossing happened, for example a peer flap. `[mechanism: those were the only triggers]`
