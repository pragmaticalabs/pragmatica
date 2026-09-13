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
  nor a replacement in flight. A replay read acts only while its activation is current, active and quorum-safe.
  Terminating an instance that is already gone completes quietly.

  [mechanism: pinned by `ClusterTopologyManagerActuatorTest.ActivationReplay` and
  `.OrphanFreeAcrossLeadershipAndQuorum`. The latter reproduces verify-1057's two orphan probes, including a run where
  quorum is actually lost and restored so `NodeRemoved` reaches only inactive CTMs. Both were red at `c974cb3f4`.]
- **A departed-node reap re-checks liveness first (#1062).** `reapDepartedNode` terminates only while the node shows no
  independent evidence of life: the leader's transport link, raw SWIM, or a counted membership.
  - With such evidence the reap is deferred (WARN, with the evidence) and re-checked every `provisioningTimeout / 12`
    (5s at defaults), for at most `provisioningTimeout` (60s).
  - A node still live after the last re-check is never terminated.
  - A genuinely departed node is reaped at once, with no added delay.

  [mechanism: pinned by `ClusterTopologyManagerActuatorTest.DepartedReapLivenessRecheck`, which includes #1062's
  transport-still-connected acceptance test, red at `c974cb3f4`]
- The membership and liveness evidence reaches the CTM through one named seam, `AetherNode.drainGraceLiveness`. It also
  supplies the configured core count that the `LeaderReconciler` and `QuorumLossDetector` use.
  [mechanism: pinned by `DrainGraceLivenessSeamTest`; its body forcing the count to 0 reddens it]
- `JOIN_GRACE_REAP` and `OPERATOR_COMMAND` drains still reap as issued, and never read membership. [mechanism:
  `DrainReason.isSurplusTrim`, every constant pinned by `DrainReasonTest`]
- **Limits.**
  - [unverified: an orphan left by a refused or dropped reap is terminated only once some CTM activates and then holds
    leadership and quorum safety for one `provisioningTimeout`, with a successful inventory listing; an instance whose
    node-id or role label is missing is never replayed]
  - [unverified: the in-JVM tests use one node-local FSM and a synchronous projector; no multi-node, forge or cloud run
    was made]
  - [unverified: SUSPECT still counts as coverage — a peer killed during the grace and still SUSPECT at expiry keeps the
    counted set whole for the quorum-safety check until its eviction backstop fires]
  - [unverified: a repeated terminate is recorded twice by the in-JVM recorder; only the lifecycle layer's quiet
    completion of a not-found terminate is tested (`NodeLifecycleManagerTerminateTest`). A provider still listing a
    server that is being deleted would receive a second terminate call]
  - [unverified: a DRAIN that can never be delivered makes the reconciler re-drain a live target without bound (withdraw,
    re-drain, skip at every grace); this fix only guarantees the target is never reaped. The churn belongs to #1058 and
    #1055]
  - Out of scope: the 15s DEPARTING-timeout reap (#1054) and the age-0 ephemeral OVERPROVISION drain (#1055).
