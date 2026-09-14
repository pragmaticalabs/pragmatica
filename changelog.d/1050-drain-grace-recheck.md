### Fixed (2026-09-13 — #1050, #1062: surplus-drain and departed-node reaps could kill a live node or orphan a billed instance)
- **A surplus trim never terminates a live node.** Before this change, `graceTerminate` reaped a surplus-drained node at
  grace expiry unconditionally. For `OVERPROVISION_*` drains it now keys on the TARGET first:
  - A target that is still LIVE is never reaped, whoever is leader. Liveness is judged by evidence, not membership
    projection: raw SWIM reports it HEALTHY or SUSPECTED, or the leader's transport link to it is connected. A DEPARTING
    target whose DRAIN was never delivered (withdrawn to MEMBER and re-drained) is therefore live. A target is not live
    only with positive evidence of death or exit: SWIM FAULTY or UNKNOWN AND the transport link down.
  - A target that is not live is reaped only by an active CTM whose remaining counted members are quorum-safe for a
    known configured core size. An unknown size (below 1) is refused (fail-closed).
  - A deficit no longer blocks the reap: terminating a node that is not live removes no capacity.
  - The DRAIN command is cleared in every branch.

  [mechanism: `ClusterTopologyManagerRecord.graceReapVerdict` and `MembershipLiveness.live`, pinned by
  `ClusterTopologyManagerActuatorTest.DrainGraceRecheck` and `.DrainGraceWithRealMembership` through the real
  `drainNode` → scheduler path, and by `MembershipLivenessTest`. Red at `c974cb3f4`:
  `surplusDrain_realMembership_targetReturnedToMember_isNeverReaped_evenWithSpareCapacity`. Red under a verdict keyed on
  counted membership: `surplusDrain_realMembership_reDrainedDepartingTarget_swimAliveAndConnected_isNotReaped`]
- **A refused or dropped reap no longer leaves a billed orphan.** `activate()` now runs a one-shot activation replay over
  this cluster's labelled core instances (`aether-cluster`, `aether-role=core`). It terminates an instance only when, at
  two reads `provisioningTimeout` apart, its node is neither tracked by the membership FSM, nor showing evidence of life,
  nor a replacement in flight. A replay read, and the terminate once the second read resolves, act only while the
  activation is current, active and quorum-safe — a listing that resolves after `deactivate()` terminates nothing.
  An instance the replay skips ONLY because raw SWIM still reports its node alive (untracked, uncounted, not in flight,
  link down) is parked for the FAULTY edge that follows, so an orphan whose parked reap died with the previous leader
  is not lost while the new leader's own SWIM still holds it SUSPECTED. Terminating an instance that is already gone
  completes quietly.

  [mechanism: pinned by `ClusterTopologyManagerActuatorTest.ActivationReplay` and
  `.OrphanFreeAcrossLeadershipAndQuorum`. The latter reproduces verify-1057's two orphan probes, including a run where
  quorum is actually lost and restored so `NodeRemoved` reaches only inactive CTMs. Both were red at `c974cb3f4`. The
  resolution re-check is `terminateOrphans`, pinned by `replay_secondListingResolvesAfterDeactivation_terminatesNothing`
  (terminated at `ff50a3274`) with its fixture control `replay_secondListingResolvesWhileStillActive_terminatesTheOrphan`.
  The park is `unprotectedOrParked` / `MembershipLiveness.swimOnlyProtected`, pinned by
  `replay_leadershipChangeInsideSuspicionWindow_parkedOrphanReapedAtTheFaultyEdge` (lost until the next activation at
  `93e7dd349`) and its guard `replay_trackedAndSwimAliveInstance_isNotParked`]
- **A departed-node reap re-checks liveness first (#1062).** `reapDepartedNode` terminates only while the node shows no
  independent evidence of life: the leader's transport link, raw SWIM, or a counted membership.
  - With such evidence the reap is deferred (WARN, with the evidence) and re-checked every `provisioningTimeout / 12`
    (5s at defaults), for at most `provisioningTimeout` (60s). A deferral belongs to the activation that started it: one
    that outlives a deactivate→activate drops out at its next re-check.
  - A node still live after the last re-check is never terminated. The re-check budget bounds the TRANSPORT evidence
    (a dead peer's link is evicted within `pingInterval × 8`); it does not bound SWIM's suspicion window, which is
    LHM-scaled and can outlast it. A reap that runs out of re-checks while SWIM still reports the node SUSPECTED is
    ABANDONED and parked; the SWIM FAULTY edge for that node re-arms it (`ClusterTopologyManager.onSwimFaulty`, delivered
    by the SWIM observation listener in `AetherNode`), still through the same evidence gate. A node SWIM never declares
    FAULTY is never terminated; a parked reap dies with its activation, and a rejoin under the same id (`NodeJoined`)
    ends the parked episode — the new incarnation's death arrives as its own `NodeRemoved`. SWIM's UNKNOWN edge (a
    suspicion window that expired without co-confirmation) reads not-alive but is not positive death evidence: it
    re-arms nothing, and a parked reap stays parked through it.
  - A deferral belongs to the activation that started it: the epoch is bumped first in `activate()`, so a
    `NodeRemoved` delivered while `activate()` is still running starts a chain under the new activation.
  - A genuinely departed node is reaped at once, with no added delay.

  [mechanism: pinned by `ClusterTopologyManagerActuatorTest.DepartedReapLivenessRecheck`, which includes #1062's
  transport-still-connected acceptance test, red at `c974cb3f4`. The re-arm:
  `nodeRemoved_swimSuspectedPastEveryRecheck_reapedOnceWhenSwimReportsFaulty` (billed orphan at `ff50a3274`), its control
  `nodeRemoved_swimSuspectedForever_isNeverTerminated`, and `swimFaulty_forANodeWhoseReapWasNeverAbandoned_terminatesNothing`;
  the routing by `SwimFaultyToCtmRoutingTest` and its registration in the node assembly by `SwimFaultyReArmBootTest`,
  which runs the whole chain on a booted node with a real SWIM `FaultyObserved` raised by gossip over UDP. The epoch
  drop: `nodeRemoved_reactivatedDuringDeferral_staleDeferralDropped` (terminated at `ff50a3274`); the epoch-first bump:
  `nodeRemoved_deliveredInsideActivate_deferralBelongsToThatActivation`; the rejoin:
  `nodeJoined_clearsTheParkedReap_faultyAfterRejoinReArmsNothing`]
- The membership and liveness evidence reaches the CTM through one named seam, `AetherNode.drainGraceLiveness`. It also
  supplies the configured core count that the `LeaderReconciler` and `QuorumLossDetector` use.
  [mechanism: pinned by `DrainGraceLivenessSeamTest`; its body forcing the count to 0 reddens it, and its SWIM term
  passing `_ -> false` or counting FAULTY as alive reddens
  `drainGraceLiveness_swimAlive_isHealthyOrSuspected_neverFaultyOrUnknown`]
- `JOIN_GRACE_REAP` and `OPERATOR_COMMAND` drains still reap as issued, and never read membership. [mechanism:
  `DrainReason.isSurplusTrim`, every constant pinned by `DrainReasonTest`]
- **Limits.**
  - [unverified: an orphan left by a refused or dropped reap is terminated only once some CTM activates and then holds
    leadership and quorum safety for one `provisioningTimeout`, with a successful inventory listing; an instance whose
    node-id or role label is missing is never replayed]
  - [unverified: the in-JVM tests use one node-local FSM and a synchronous projector, and hand-feed the SWIM evidence
    and the FAULTY edge; no multi-node, forge or cloud run drove a real `SwimProtocol` SUSPECTED past the re-check budget
    and then FAULTY]
  - [unverified: the transport predicate at the `drainGraceLiveness` call site (`connectedPeers().contains`) has no
    seam and is unpinned; and `SwimFaultyReArmBootTest` activates the booted node's CTM through its public API because a
    single node never elects itself, so the leader-change toggle that activates it in production is not on that path]
  - [unverified: SUSPECT still counts as coverage — a peer killed during the grace and still SUSPECT at expiry keeps the
    counted set whole for the quorum-safety check until its eviction backstop fires]
  - In production order a surplus target that dies is terminated twice by the same CTM — the `NodeRemoved` path and
    the grace backstop each reap the same episode — and the second completes quietly at the lifecycle layer
    (`terminateNode_noMatchingInstance_completesAsDone_withoutCallingProvider`). The duplicate is left in place: the
    memory that would remove it, a per-id "already reaped" record, is refused because a PERMANENT one would also swallow
    #166's re-kill of a restart-looping phantom, which arrives as a fresh `NodeRemoved` after a rejoin under the same id;
    an episode-scoped one (cleared on `NodeJoined`) would not, and is simply not worth the state for a quiet no-op.
    [unverified: a provider still listing a server that is being deleted receives the second terminate call]
  - [unverified: a DRAIN that can never be delivered makes the reconciler re-drain a live target without bound (withdraw,
    re-drain, skip at every grace); this fix only guarantees the target is never reaped. The churn belongs to #1058 and
    #1055]
  - Out of scope: the 15s DEPARTING-timeout reap (#1054) and the age-0 ephemeral OVERPROVISION drain (#1055).
