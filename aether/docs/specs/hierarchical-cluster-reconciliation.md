# Hierarchical cluster requirement reconciliation

This is the acceptance checklist for the [contract](hierarchical-cluster-contract-spec.md),
not a claim that final-head CI or physical-scale acceptance has completed. This update records
named local checkpoints from `/private/tmp/pragmatica-hierarchy-runtime-pr`: targeted tests at
`c5f3f2a1`, authority regressions and capacity fallback at `2a175cecf`, and corrected live authority
acceptance at `1bf7ff14d`. The 20-class Forge matrix at `1bf7ff14d` passed 38 cases (zero failures/errors);
its compiled fixture predates the corrections identified below. The strengthened Decision replay notification proof
subsequently passed in the 49-case CI matrix at `0756e148b`; the validation ledger records the
published artifact and tested merge parents. The
[implementation plan](hierarchical-cluster-implementation-plan.md) owns commands and the execution
ledger; final-head runtime CI remains a separate merge requirement.

`DONE` means the named implementation and focused regression exist and cover the bounded
claim in that row; it does **not** mean the final-head CI gate has run. `MISSING` means some
required behavior or evidence is absent. `STUB`, `SHORTCUT`, `OMISSION`, and `SIMPLIFICATION`
are reserved for an identified substitute mechanism, not synonyms for tests not yet run.
An acceptance scenario with only unit/model coverage remains `MISSING` when the contract
requires the live production path. No row may be silently waived to close this checklist.

## Invariants

| ID | Status | Implementation and named tests | Remaining acceptance work |
|---|---|---|---|
| H01 | DONE | `RabiaEngine` checks installed electorate on consensus/synchronization inputs; `MembershipFsm` and `ActivationRole` preserve declared role. `RabiaHierarchySafetyTest`, `RabiaSyncAdoptionQuorumTest`, `ActivationRoleTest`; Forge `HierarchicalWorkerFormationTest`. | Review correction removes the promotion write path: different roles return conflict; unknown role returns not found; same known role is a no-op. `NodeLifecycleRoutesPromoteTest` pins unchanged KV/zero commands, unknown-node refusal, SPOT and immutable-descriptor precedence. Final-head CI remains required. |
| H02 | DONE | `RabiaEngine` committed-slot/recovery guards and gap synchronization. `RabiaReorderedDeliveryTest`, `RabiaHierarchySafetyTest`, `RabiaDurableRestartTest`, `RabiaVoterRecoveryTest`; Forge `HierarchicalCoreRestartTest`. | Final-head CI; full live fault schedule is tracked separately by H-T02. |
| H03 | DONE | `VoterAuthority`, `ConfigurationCertificate`, `RabiaEngine.reconfigure`, `CoreVoterReconciler`, and installed-voter drain budgets separate desired capacity, admission, and votes. `VoterConfigurationStateTest`, `RabiaVoterRecoveryTest`, `CoreVoterReconcilerTest`, `NodeLifecycleRoutesDrainBudgetTest`; Forge `HierarchicalCoreResizeTest`. | Promotion writes removed (H01). The 78-case authority gate at `2a175cecf` passed installed-electorate and operator-budget regressions; the completed Forge matrix at `1bf7ff14d` has completed `HierarchicalCoreResizeTest` successfully. Final-head CI remains required; post-barrier availability is documented separately, with no timeout rollback. |
| H04 | DONE | `GovernorAuthority` acquires authority using committed leader/read witnesses; `GovernorAnnouncer` waits for accepted acquisition; `LeaderTransaction` and owner fences refuse stale effects. `GovernorAuthorityTest`, `GovernorAnnouncerTest`, `GovernorAuthorityClientTest`, `KVStoreLeaderTransactionTest`, `KVStoreOwnerFenceTest`; Forge `HierarchicalGovernorReportLossTest`. | Final-head CI; broader split/heal sequence is H-T04. |
| H05 | DONE | `CommunityPlacementPlanner`, committed `NodePlacementValue`, `SourceComputeRegistry`, `CapacityControlledLifecycle`, and provider native location observations separate assignment from source/physical location. `CommunityPlacementParserTest`, `CommunityPlacementPlannerTest`, `SourceComputeRegistryTest`, `NodeLifecycleSourceRoutingTest`; Forge `HierarchicalCommunityMovementTest`, `HierarchicalLoadedMovementTest`. | Replica separation is distinct node identity, independently of community membership: `ClusterDeploymentStateDrainEvictionTest.workerDrainRequiresAllDesiredActiveReplicasInCurrentEligibleAudience` rejects LOADING, out-of-policy, stale-ready and draining substitutes; requires two distinct eligible ACTIVE workers for target two. [limit: replica-zone-diversity] Automatic zone-diverse replicas are not provided; single-zone clusters remain supported. Cross-source fallback now uses durable availability/refusal evidence and bound allocation; `CommunityPlacementFallbackSafetyTest` passed eight cases. `HierarchicalCapacityFallbackTest` passed its live preferred-refusal/alternate-provider scenario at `2a175cecf`; physical provider zone behavior remains separately bounded (H-T09). |
| H06 | DONE (local) | `AetherNode` activation holder/single SWIM callback, announcer stop guards, periodic health runtime cancellation. `GovernorAnnouncerTest.stop_pendingResponseCannotReactivateGovernor`, `CommunityHealthRuntimeTest`, `ActivationRoleTest`; Forge worker formation and movement. | Added `HierarchicalWorkerRuntimeReplayTest`: actual guarded directive commits and scoped metadata, repeated same-community updates, new-community reassignment, identity/task/listener counts, old callback refusal and shutdown cancellation. Passed locally in `HierarchicalWorkerRuntimeReplayTest`; final-head CI remains required. |
| H07 | DONE | `ClusterSyncCollector` separates observation exchange from term/sender control checks; `CommunityHealthIndex` checks report authority and assignment; core reachability excludes worker evidence. `ClusterSyncObservationAuthorityTest`, `CommunityHealthIndexTest`, `CommunityHealthRuntimeTest`; Forge `HierarchicalGovernorReportLossTest`. | Final-head CI and live mixed-term exchange coverage in H-T07. |
| H08 | DONE (focused local) | Origin observation versions, sample age and scope filtering in `ClusterSyncCollector`, `WorkerMetricsAggregator`, and health index. `ClusterSyncMetricsScopeTest`, `WorkerMetricsAggregatorTest`, `CommunityHealthIndexTest`; Forge `HierarchicalMetadataRecoveryTest` covers projection freshness, not metric incarnation persistence. | `ProducerIncarnation` allocates a durable epoch before node assembly from `producer-incarnation.bin` under the configured per-node control directory. Five `ProducerIncarnationTest` cases passed locally (restart despite regressed clock metadata, lost published counter, corruption, exhaustion/path failure, abandoned temporary write). Raw and typed metrics, direct pong health evidence and governor reports use this durable process epoch; SWIM's boot/refutation incarnation remains internal to SWIM. `WorkerRuntimeCommitWiringTest` pins the assembled collector across restart and refuses assembly with a corrupt epoch. Live H-T08 passed in corrected `HierarchyAuthorityAcceptanceTest` at `1bf7ff14d`; final-head CI remains required. |
| H09 | DONE (bounded local) | `CommunityPlacementReconciler` persists phase/operation identity, uses guarded transactions and bound provider selection, waits for readiness/retirement proof and committed drain acknowledgment. `CommunityPlacementReconcilerTest`, `CapacityControlledLifecycleTest`, `CommunityDrainCoordinatorTest`; Forge empty/loaded movement and `HierarchicalWorkerDrainTest`. | Targeted local gate passed `CommunityPlacementReconcilerTest` (24 cases), including nine persisted-phase cases in `freshLeaderResumesEveryPersistedWindowWithoutUnsafeProviderEffects`, `restartCannotRebindRecordedCreateToChangedProviderIdentity` and `restartAfterProviderTerminationConfirmsAbsenceWithoutDuplicateEffect`. `HierarchicalMovementTakeoverTest` has passed in the completed Forge matrix at `1bf7ff14d` with real leader loss during pending drain (H-T10). Final-head CI remains required. |
| H10 | DONE (bounded local) | `QuicClusterNetwork` separate traffic lanes and bounded retries; scoped/chunked worker metadata; bounded health admission/probes. `QuicConsensusBackpressureTest`, `WorkerMetadataChannelTest`, `WorkerAdmissionTest`, `WorkerPeerScopeTest`; Forge metadata blackout recovery. | `HierarchicalRecoveryEnvelopeTest` passed a bounded 3-core plus 1/2/3-worker blackout/recovery workload with CPU, heap, transport and HTTP observations. `HierarchicalWorkerReconnectTest` has passed actual disconnect/reconnect in the completed matrix at `1bf7ff14d`: six nodes in one JVM, not six processes. Physical multi-region outage and 10K reconnect capacity remain unverified. H-T11/H-T12 are bounded local evidence; final-head CI remains required. |

## Production-path scenarios

| ID | Status | Existing evidence | Additional falsifiable test or measurement required |
|---|---|---|---|
| H-T01 | DONE (bounded local) | `RabiaHierarchySafetyTest`, `RabiaSyncAdoptionQuorumTest`, and corrected Forge `HierarchyAuthorityAcceptanceTest`. | The live scenario passed at `1bf7ff14d`: four authenticated workers cannot replace genuine responses for a held cold voter; activation succeeds after genuine core responses return. `/private/tmp/hierarchy-review-authority-forge2.log` records one passing scenario and BUILD SUCCESS. Final-head CI remains required. |
| H-T02 | DONE (bounded local) | `RabiaReorderedDeliveryTest` and `HierarchicalDecisionReplayTest.reorderedAndEvictedDecisionsCannotReapplyAnOlderCommittedValue`. | The strengthened live scenario passed in `hierarchy-review-recovery-fixes-forge.log`: authentic probe decisions reordered without snapshot masking, actual receiver phase eviction after 125 writes, four replay deliveries with unchanged live notification counts, then exactly one notification for the next write. This exposed pending-queue divergence after recovery; admitted proposals now repair missed batch dissemination. The deterministic disjoint-queue regression covers six fair delivery schedules. The final implementation passed the two-class replay/takeover gate at `07f56f4af`; merge-head CI remains mandatory. |
| H-T03 | DONE (bounded local) | `ActivationRoleTest`, explicit-role CDM tests, Forge role-label tests and corrected `HierarchyAuthorityAcceptanceTest`. | At `1bf7ff14d`, workers joined below desired core count and an extra CORE joined above target; opposite-role requests were refused without directive mutation. The completed matrix at `1bf7ff14d` also completed `EmberAddNodeRoleLabelTest` (six cases). Final-head CI remains required. |
| H-T04 | DONE (bounded local) | Authority/client/announcer regressions; `HierarchicalGovernorReportLossTest` and `HierarchicalGovernorConcurrencyRestartTest`. | Both live scenarios passed in the completed matrix at `1bf7ff14d`: asymmetric report loss, stale-report replay after healing, concurrent network nominations, preserved-journal restart and stale expected-term replay. Same-owner restart retains authority; owner change advances generation. The 38-case matrix passed; this is not final-head CI evidence. |
| H-T05 | DONE (local) | Announcer pending-response cancellation tests and live formation/movement. | Added `HierarchicalWorkerRuntimeReplayTest`, with real production assembly/consensus/metadata and observed ownership handles as described under H06. Passed locally; final-head CI remains required. |
| H-T06 | DONE (local) | `CommunityHealthIndexTest` pins immediate exclusion of assigned workers without evidence; `GovernorRecoveryTest` pins the separate community-absence delay. `CommunityReachabilityTest` covers only unassigned-node grace. | Added `HierarchicalLeaderObservationGraceTest`: followers denied reports since formation, leader loss, immediate placement exclusion, bounded recovery grace and healing without worker deletion. `GovernorRecoveryTest` also pins follower-tenure independence with injected clock. The live scenario passed locally in the five-class batch; final-head execution remains required. |
| H-T07 | DONE (bounded local) | `ClusterSyncObservationAuthorityTest` and `HierarchyAuthorityAcceptanceTest`. | The corrected live authority scenario passed at `1bf7ff14d`: genuine worker/follower low/high-term pings receive pongs without drain or high-term authority acceptance. Final-head CI remains required. |
| H-T08 | DONE (bounded local) | Scope/aggregation/index regressions and corrected `HierarchyAuthorityAcceptanceTest`. | At `1bf7ff14d`, direct/follower-relayed duplicate observations did not duplicate history or renew original age; duplicate typed one-producer envelopes and overlapping memberCount=2 summary did not inflate membership and expired under continued replay. This live scenario passed in `/private/tmp/hierarchy-review-authority-forge2.log`; final-head CI remains required. |
| H-T09 | DONE (bounded local) | `CommunityPlacementFallbackSafetyTest` (eight cases), zone rotation (seven), availability (four), and live `HierarchicalCapacityFallbackTest`. | The live preferred-provider definitive refusal followed by actual alternate Ember provisioning passed at `2a175cecf` in `/private/tmp/hierarchy-review-authority-fallback-forge.log`; the shared batch failed only its separate authority fixture, later corrected and passed. Assertions include committed refusal, released old reservation, observed alternate source/readiness and shared ledger. Ember has no native zone and reports none; hard-zone constraints remain focused provider-component evidence, not invented physical zone observations. Final-head CI remains required. |
| H-T10 | DONE (bounded local) | Reconciler phase reconstruction, binding/absence regressions, live loaded movement, and `HierarchicalMovementTakeoverTest`. | The live takeover scenario passed at `1bf7ff14d`, then passed again with the core-candidate retirement correction and leader-absence-safe polling at `07f56f4af` (94.9 seconds), losing the real leader while drain was pending and completing under its successor. This complements nine persisted-phase reconstruction cases; it does not represent every physical provider crash boundary. Final-head CI remains required. |
| H-T11 | DONE (bounded local) | QUIC bounded retry tests, metadata blackout, asymmetric report loss, `HierarchicalWorkerReconnectTest` and `HierarchicalRecoveryEnvelopeTest`. | Both live scenarios passed in the completed matrix at `1bf7ff14d`. Reconnect exercised real CONNECTED→EVICTED→CONNECTED transitions for three workers, commits during churn, metadata repair and resource bounds. The envelope is six nodes in one JVM, not six processes or a measured 10K reconnect storm. Physical WAN/cloud capacity and final-head CI remain unverified. |
| H-T12 | DONE (bounded local) | Logical 100×100 producer/scope fixtures establish algorithmic bounds; small real Forge clusters establish wiring. | `HierarchicalRecoveryEnvelopeTest` passed locally at 3 cores plus 1/2/3 workers. Its JSON records 3-second warm-up, 20 HTTP samples per steady stage, 30 during recovery, CPU/heap/transport counters, convergence/recovery durations and percentiles. This is a single-JVM observation endpoint workload; physical WAN and 10K throughput remain unverified. Final-head CI must publish runner hardware and these records. |


### Local checkpoints and remaining execution

All paths below are local logs under `/private/tmp`; a passing class in a failed batch
is not represented as an aggregate passing gate.

- `hierarchy-review-final-targeted.log`, source `c5f3f2a1`: BUILD SUCCESS, 146 cases. Named
  selections cover durable persistence/restart, epoch fences, observation authority, placement
  fallback/reconstruction, zone rotation, replica-aware drain, capacity accounting, immutable
  role routes, codec tripwires, producer epochs and governor/worker health.
- `hierarchy-review-authority-unit.log`, source `2a175cecf`: BUILD SUCCESS, 78 cases in
  `RabiaSyncAdoptionQuorumTest`, `RabiaReorderedDeliveryTest`, `RabiaHierarchySafetyTest`,
  `VoterConfigurationStateTest`, `RabiaVoterRecoveryTest`, `CoreVoterReconcilerTest`,
  `NodeLifecycleRoutesDrainBudgetTest`, `CommittedLeaderRefreshTest`, `GovernorAnnouncerTest`,
  `GovernorAuthorityClientTest`, `CommunityHealthIndexTest` and `CommunityHealthRuntimeTest`.
- `hierarchy-review-authority-fallback-forge.log`, source `2a175cecf`:
  `HierarchicalCapacityFallbackTest` passed. The two-class batch failed its separate
  `HierarchyAuthorityAcceptanceTest`; the failure is not erased by the fallback pass.
- `hierarchy-review-authority-forge2.log`, source `1bf7ff14d`: corrected
  `HierarchyAuthorityAcceptanceTest` passed; one scenario, BUILD SUCCESS. This supersedes
  earlier authority-fixture startup/response failures for H-T01/H-T03/H-T07/H-T08.
- `hierarchy-review-final-forge-matrix.log`: source `1bf7ff14d`, BUILD SUCCESS,
  38 cases in 20 classes, zero failures/errors (approximately 1,390 seconds). Classes include governor
  concurrency/restart, metadata recovery, movement takeover, recovery envelope, governor
  report loss, held worker drain, worker formation, loaded movement, core restart,
  `ClusterFormationTest` (five), `EmberAddNodeRoleLabelTest` (six), worker workload,
  community movement, `StreamOwnershipDriverFenceTest` (two), worker reconnect, the
  **earlier** Decision replay fixture, worker runtime replay, core resize and leader
  observation grace and `SliceInvocationTest` (nine cases). The newer receiver-eviction/live-notification replay fixture subsequently passed after the proposal-learning correction; see H-T02.
- Foundation CI at head `82ce` is reported fully green; its exact workflow evidence remains
  separate from runtime final-head CI and is not added to runtime test totals.

The core-candidate retirement correction passed its focused regressions and the live replacement-security scenario. The version-registry locality correction passed all three cases; the security class passed all three after waiting for route publication. The integrated stream/provider/retirement gate passed 81 cases. The proposal-learning correction passed its deterministic recovery and containment gates; the live replay proof passed with that correction. Final-head runtime CI remains required after
all source/test corrections; physical 10K/WAN measurements remain unverified.

## Persistence and wire acceptance

`DurableRabiaPersistence` declares frame magic `0x52414231` and format `VERSION = 1`.
Each frame carries magic, version, payload length, CRC32C and serialized payload. Journal
records carry sequence numbers; checkpoint payloads use the pinned codec registry. V1 is a
same-version restart contract, not an upgrade/migration promise. Reopening a journal must
reject unsupported versions and malformed/corrupt evidence without voting.

`atomicReplace` forces the temporary file, atomically renames it, then forces the parent
directory. Checkpoint publication precedes replacement of the covered WAL. The directory
force is part of durability, not an optional optimization.

| Crash boundary / format property | Status | Existing named pin or required addition |
|---|---|---|
| Durable vote evidence before outgoing messages | DONE | `RabiaDurableRestartTest.restartAfterEachVotingEmissionRetainsTheOriginalPromise`; `failedWriteCannotEscapeAsAVoteOrAppliedDecision`. |
| Decision replay before synchronization replies | DONE | `RabiaDurableRestartTest.committedDecisionReplaysOnceBeforeTheRestartCanAnswerSynchronization`. |
| Reopen, exclusive writer, torn tail, checksum and removed frame | DONE | `DurableRabiaPersistenceTest`: `durablePromisesSurviveReopenAndConflictingBallotsAreRejected`, `exclusiveDirectoryOwnershipPreventsTwoWriters`, `tornTailFailsWithoutTruncatingTheEvidence`, `checksumCorruptionFailsClosed`, `removingAWholeValidFrameStillFailsTheSequenceCheck`. |
| Checkpoint published; old WAL still present | DONE | `DurableRabiaPersistenceTest.checkpointPublishedBeforeWalReplacementCanRecoverCoveredOldFrames` reconstructs this file state. It does not inject a filesystem crash at rename. |
| Temporary checkpoint write/force interruption | DONE (local) | `everyDurabilityFailurePoisonsWriterAndLeavesRecoverableFileCombination` injects before temporary force and before rename (after force), refuses acknowledgment and further writes, and reopens retained file combinations. |
| Checkpoint renamed, directory not yet forced | DONE (local) | `everyDurabilityFailurePoisonsWriterAndLeavesRecoverableFileCombination` covers `CHECKPOINT_DIRECTORY_FORCE`; failure is propagated and the writer poisoned. Controlled failure/reopen does not emulate filesystem power loss. |
| WAL replacement renamed, directory not yet forced | DONE (local) | Same test separately covers `WAL_TEMP_FORCE`, `WAL_RENAME` and `WAL_DIRECTORY_FORCE`, retaining vote evidence on reopen. `appendAcknowledgmentWaitsForForceBoundary` pins acknowledgment ordering. |
| V1 bytes and unknown-version rejection | DONE (local) | `versionOneHeaderAndCrc32cHaveFixedGoldenBytes` pins header and CRC32C payload coverage; `unknownFormatVersionFailsWithoutRewritingEvidence` pins refusal and evidence preservation. |
| Every registered runtime message/KV type has baseline tag | DONE (local checkpoint) | `WireAssignmentTripwireTest` (3) and `SystemCodecPinningTest` (3) passed the targeted source checkpoint. Later schema edits require rerunning both gates; this is not final-head/CI evidence. |

Execution evidence: `/private/tmp/hierarchy-review-final-targeted.log`, source `c5f3f2a1`,
reports BUILD SUCCESS across 146 selected cases. Included results were
`DurableRabiaPersistenceTest` 12/0/0/0, `CommunityPlacementReconcilerTest` 24/0/0/0,
`WireAssignmentTripwireTest` 3/0/0/0 and `SystemCodecPinningTest` 3/0/0/0
(tests/failures/errors/skips). This supersedes the older uncommitted `9067424b2` checkpoint
for these selected classes. It remains a named local source checkpoint, not final-head CI;
later schema or runtime corrections require their affected gates again.

## Movement interruption acceptance matrix

The 24-case reconciler checkpoint exercises persisted-phase reconstruction, binding and late-effect guards. The following matrix remains the review checklist for exact fault placement and assertions; a phase-level test alone does not prove every provider crash boundary. Use a fresh reconciler/core owner over the same committed store after each fault; do not merely
call the previous in-memory instance again. For every row assert provider call count, durable
phase and bound source identity, stale-owner refusal, and whether the old node may be retired.

1. `RESERVED`: restart before dispatch, and fail guarded `CREATE_REQUESTED` commit.
2. `CREATE_REQUESTED`: provider accepts but response is lost; restart and reconcile inventory
   without second create. Keep uncertainty if matching inventory cannot be established.
3. `AWAITING_READY`: replacement is visible but unready; restart, then provide fresh readiness;
   no old-worker drain before workload/replica retirement proof.
4. `DRAIN_REQUESTED`: restart before send and after send before acknowledgment; new leader adopts
   the operation, duplicate requests are idempotent, stale acknowledgments cannot advance it.
5. `DRAIN_UNCERTAIN`: restart after grace expiry or lost acknowledgment; absence is not quiescence.
6. `DRAINED`: restart; revoke destination readiness or retirement proof before termination; no
   destructive provider effect until both are re-established.
7. `TERMINATING`: provider deletion succeeds but reply is lost; restart and confirm absence using
   the recorded provider/account binding before completing or releasing reservation.
8. `COMPLETE`: replay after restart cannot repeat create/drain/delete; changed policy can start
   a new operation without reviving the previous operation's effects.
9. `BLOCKED` / `CREATE_UNCERTAIN`: restart preserves the recorded identity, escalation and
   uncertainty. Reconciliation may inspect new matching evidence, but never retries an unknown
   provider create under a new identity. Without definitive refusal or matching provider/placement
   evidence, the operation remains parked; no force-clear API is provided. A policy edit/removal
   cannot orphan in-flight inventory or silently discard uncertainty. The additional binding and
   late-readiness recovery paths belong to #1405.

## Interpretation limits

[limit: core-hosted-state] Core consensus/DHT state and workload endpoint cardinality remain
separate scaling costs from bounded community control topology.
[limit: application-graph] Cluster-wide application dependencies can require nonconstant
endpoint catalogs and connections; community peer scoping does not bound arbitrary workloads.
[unverified: final-head-ci] This reconciliation separates source coverage and bounded local results from the final execution ledger.
No historical worktree result should be presented as final merged-head CI evidence.

Exact local checkpoint commands and the authoritative CI artifact contract are in the [runtime validation ledger](hierarchical-cluster-validation.md).

## Review corrections and limits

- [limit: crash-fault-certificates] H01 certificates carry identity lists rather than Byzantine signatures.
- H02 recovery covers both a clean stop with checkpoint-only history and a crashed stop with a remaining
  journal (`RabiaDurableRestartTest`). The two arms assert frontier and state content before synchronization.
- [limit: transport-lane-isolation] H10 uses separate bounded lanes; it does not promise scheduler priority
  or a measured bound against connection-wide QUIC window starvation.
- [limit: ttm-positional-features] The eleven TTM features remain positional and unversioned. Renaming
  interval-mean percentile labels does not change input values or prove request-level percentiles.
- The hierarchy workflow now runs for relevant paths on all PR bases (including stacked PRs), and on
  main/release pushes. Its final-head results remain required evidence.

### Placement review dispositions

`CapacityRefusalRecoveryTest` now composes the lifecycle and reconciler through a release conflict,
controller restart and lost callback. `RELEASED` reservations are durable proof of no provider
create; their capacity counter may remain conservatively charged until guarded cleanup succeeds.
An active movement consumes that evidence before cleanup removes it. This state is not provider
absence evidence and is never manufactured for an ambiguous create.

[limit: provider-refusal-coverage] AWS and Hetzner map definitive capacity refusals. GCP and Azure
generic failures remain uncertain; this code does not infer no-create from an unclassified error.
[limit: unresolved-provider-effect] No force-clear/cancel management API exists for an ambiguous
create or missing drain acknowledgement. Restore matching provider/binding evidence or obtain
authoritative provider confirmation; do not delete the reservation to retry under another identity.
The phase-preserving source-binding recovery, late-readiness resumption and stale-placement
cleanup are supplied by #1405, not the standalone #1390 implementation.

[limit: refusal-location-granularity] A provider adapter's `NodeCapExceeded` is definite no-create
evidence but currently enters the same location-backoff path as provider capacity refusal. It does
not prove that a physical zone is exhausted; another zone of the same capped provider may also
refuse. Global committed fleet admission still limits allocations independently.
[limit: admission-diagnostics] #1405's `CAPACITY_UNAVAILABLE` intentionally reports a pre-dispatch
deferral that can mean a busy source slot, ledger contention or fleet cap. It does not distinguish
those causes in its public failure value or count them as provider failures.

H01 directive-path evidence includes `CoreRuntimeActivationAndStatusTest` delivering a wrong-role
`ValuePut` through the assembled node's subscription. H06 runtime effects consult committed
authority; announcer cancellation stops its timer. The announcer's local governor flag is not an
authority source. `HierarchicalWorkerRuntimeReplayTest` pins actual runtime/task ownership.
