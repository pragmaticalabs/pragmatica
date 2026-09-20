# Hierarchical cluster requirement reconciliation

This is the acceptance checklist for the [contract](hierarchical-cluster-contract-spec.md),
not a claim that every specified scenario has passed. This update inspected integrated checkout
`c5f3f2a19633faf8e3c22aeee67837ad8cee8875` plus uncommitted review corrections in
`/private/tmp/pragmatica-hierarchy-runtime-pr`. The local evidence below predates the current
integrated build and does not establish final-head results. The
[implementation plan](hierarchical-cluster-implementation-plan.md) owns commands and the
execution ledger; attach the final head SHA and CI run there before merge acceptance.

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
| H03 | DONE | `VoterAuthority`, `ConfigurationCertificate`, `RabiaEngine.reconfigure`, `CoreVoterReconciler`, and installed-voter drain budgets separate desired capacity, admission, and votes. `VoterConfigurationStateTest`, `RabiaVoterRecoveryTest`, `CoreVoterReconcilerTest`, `NodeLifecycleRoutesDrainBudgetTest`; Forge `HierarchicalCoreResizeTest`. | Promotion writes removed (H01). Re-establish handoff and operator concurrency tests after rc4 merge; post-barrier availability is documented separately, with no timeout rollback. |
| H04 | DONE | `GovernorAuthority` acquires authority using committed leader/read witnesses; `GovernorAnnouncer` waits for accepted acquisition; `LeaderTransaction` and owner fences refuse stale effects. `GovernorAuthorityTest`, `GovernorAnnouncerTest`, `GovernorAuthorityClientTest`, `KVStoreLeaderTransactionTest`, `KVStoreOwnerFenceTest`; Forge `HierarchicalGovernorReportLossTest`. | Final-head CI; broader split/heal sequence is H-T04. |
| H05 | DONE | `CommunityPlacementPlanner`, committed `NodePlacementValue`, `SourceComputeRegistry`, `CapacityControlledLifecycle`, and provider native location observations separate assignment from source/physical location. `CommunityPlacementParserTest`, `CommunityPlacementPlannerTest`, `SourceComputeRegistryTest`, `NodeLifecycleSourceRoutingTest`; Forge `HierarchicalCommunityMovementTest`, `HierarchicalLoadedMovementTest`. | Replica separation is distinct node identity, independently of community membership: `ClusterDeploymentStateDrainEvictionTest.workerDrainRequiresAllDesiredActiveReplicasInCurrentEligibleAudience` rejects LOADING, out-of-policy, stale-ready and draining substitutes; requires two distinct eligible ACTIVE workers for target two. [limit: replica-zone-diversity] Automatic zone-diverse replicas are not provided; single-zone clusters remain supported. Cross-source fallback now uses durable availability/refusal evidence and bound allocation; `CommunityPlacementFallbackSafetyTest` passed eight cases. H-T09 still requires live provider refusal/fallback acceptance. |
| H06 | DONE (local) | `AetherNode` activation holder/single SWIM callback, announcer stop guards, periodic health runtime cancellation. `GovernorAnnouncerTest.stop_pendingResponseCannotReactivateGovernor`, `CommunityHealthRuntimeTest`, `ActivationRoleTest`; Forge worker formation and movement. | Added `HierarchicalWorkerRuntimeReplayTest`: actual guarded directive commits and scoped metadata, repeated same-community updates, new-community reassignment, identity/task/listener counts, old callback refusal and shutdown cancellation. Passed locally in `HierarchicalWorkerRuntimeReplayTest`; final-head CI remains required. |
| H07 | DONE | `ClusterSyncCollector` separates observation exchange from term/sender control checks; `CommunityHealthIndex` checks report authority and assignment; core reachability excludes worker evidence. `ClusterSyncObservationAuthorityTest`, `CommunityHealthIndexTest`, `CommunityHealthRuntimeTest`; Forge `HierarchicalGovernorReportLossTest`. | Final-head CI and live mixed-term exchange coverage in H-T07. |
| H08 | DONE (focused local) | Origin observation versions, sample age and scope filtering in `ClusterSyncCollector`, `WorkerMetricsAggregator`, and health index. `ClusterSyncMetricsScopeTest`, `WorkerMetricsAggregatorTest`, `CommunityHealthIndexTest`; Forge `HierarchicalMetadataRecoveryTest` covers projection freshness, not metric incarnation persistence. | `ProducerIncarnation` allocates a durable epoch before node assembly from `producer-incarnation.bin` under the configured per-node control directory. Five `ProducerIncarnationTest` cases passed locally (restart despite regressed clock metadata, lost published counter, corruption, exhaustion/path failure, abandoned temporary write). Raw and typed metrics use this epoch; `ClusterSyncPong` carries separate SWIM incarnation. Final-head CI and live H-T08 remain required. |
| H09 | MISSING | `CommunityPlacementReconciler` persists phase/operation identity, uses guarded transactions and bound provider selection, waits for readiness/retirement proof and committed drain acknowledgment. `CommunityPlacementReconcilerTest`, `CapacityControlledLifecycleTest`, `CommunityDrainCoordinatorTest`; Forge empty/loaded movement and `HierarchicalWorkerDrainTest`. | Targeted local gate passed `CommunityPlacementReconcilerTest` (24 cases), including nine persisted-phase cases in `freshLeaderResumesEveryPersistedWindowWithoutUnsafeProviderEffects`, `restartCannotRebindRecordedCreateToChangedProviderIdentity` and `restartAfterProviderTerminationConfirmsAbsenceWithoutDuplicateEffect`. Live takeover remains unexecuted (H-T10). |
| H10 | MISSING | `QuicClusterNetwork` separate traffic lanes and bounded retries; scoped/chunked worker metadata; bounded health admission/probes. `QuicConsensusBackpressureTest`, `WorkerMetadataChannelTest`, `WorkerAdmissionTest`, `WorkerPeerScopeTest`; Forge metadata blackout recovery. | `HierarchicalRecoveryEnvelopeTest` passed a bounded 3-core plus 1/2/3-worker blackout/recovery workload with CPU, heap, transport and HTTP observations. Actual transport reconnect remains unexecuted (`HierarchicalWorkerReconnectTest`); physical multi-region outage and 10K reconnect capacity are unverified. H-T11 remains open; H-T12 is a bounded local measurement only. |

## Production-path scenarios

| ID | Status | Existing evidence | Additional falsifiable test or measurement required |
|---|---|---|---|
| H-T01 | MISSING | `RabiaHierarchySafetyTest` and `RabiaSyncAdoptionQuorumTest` reject worker evidence; Forge worker formation. | Added `HierarchyAuthorityAcceptanceTest`: four authenticated workers, three configured cores, held cold voter with genuine core responses blocked and worker-sent copies of genuine snapshots; restore core responses and require activation. The first local run failed during worker startup before the scenario: full start awaited a DHT marker on the intentionally held core. The fixture now observes committed worker activation and requires the original start promises to complete after that core returns. Corrected scenario and final-head execution remain pending. |
| H-T02 | DONE (bounded local) | `RabiaReorderedDeliveryTest` injects duplicate/reordered decisions and carry-forward gaps; durable restart Forge exercises real recovery. | `HierarchicalDecisionReplayTest.reorderedAndEvictedDecisionsCannotReapplyAnOlderCommittedValue` passed locally: authentic newer-before-older delivery without snapshot masking, repair, 110+ real commits, actual phase-cache eviction, duplicate replay without visible value rollback, then continued commits. Exact notification count is separately pinned by unit tests. Final-head CI remains required. |
| H-T03 | MISSING | `ActivationRoleTest`, explicit-role CDM tests, Forge role-label/worker formation coverage. | Added `HierarchyAuthorityAcceptanceTest`: workers join while only two of three desired cores are live, fourth CORE joins above target three; opposite-role API requests return409 without directive mutation. The shared authority fixture failed before these assertions; its startup correction awaits rerun and final-head execution. |
| H-T04 | MISSING | Authority/client/announcer unit regressions; `GovernorRecoveryTest`; Forge `HierarchicalGovernorReportLossTest` tests asymmetric report loss while incumbent remains alive. | Extended `HierarchicalGovernorReportLossTest` with capture/replay of the original report after replacement and healing, asserting unchanged accepted authority. Added `HierarchicalGovernorConcurrencyRestartTest` for concurrent network nominations and process restart on preserved core journals, followed by stale expected-term replay. Same-owner restart continues the authority; an owner change advances generation. Both scenarios await final-head execution. |
| H-T05 | DONE (local) | Announcer pending-response cancellation tests and live formation/movement. | Added `HierarchicalWorkerRuntimeReplayTest`, with real production assembly/consensus/metadata and observed ownership handles as described under H06. Passed locally; final-head CI remains required. |
| H-T06 | DONE (local) | `CommunityReachabilityTest` and `CommunityHealthIndexTest` bound unknown/freshness; governor-report loss Forge. | Added `HierarchicalLeaderObservationGraceTest`: followers denied reports since formation, leader loss, immediate placement exclusion, bounded recovery grace and healing without worker deletion. `GovernorRecoveryTest` also pins follower-tenure independence with injected clock. The live scenario passed locally in the five-class batch; final-head execution remains required. |
| H-T07 | MISSING | `ClusterSyncObservationAuthorityTest` checks arbitrary observer senders and authority-bearing fields. | Added `HierarchyAuthorityAcceptanceTest`: real worker/follower low/high-term pings and returned pongs, asserting no drain and no high-term authority acceptance. The shared authority fixture failed before this scenario; corrected-fixture and final-head execution remain pending. |
| H-T08 | MISSING | Scope/aggregation/index unit tests reject duplicate versions, overlapping summaries and stale forwarded age. | Added `HierarchyAuthorityAcceptanceTest`: direct worker plus follower relay of the same producer observation, metric/history nonduplication and expiry under continuing replay. Also sends a core-relayed typed batch containing duplicate one-producer envelopes plus an overlapping memberCount=2 summary; expects one retained member and original-age expiry under continued replay. The shared authority fixture failed before this scenario; corrected-fixture and final-head execution remain pending. |
| H-T09 | MISSING | Planner/config/provider tests; live source movement via Ember. | `CommunityPlacementFallbackSafetyTest` passed eight focused cases: explicit refusal permits allowed alternate after restart, ambiguous create forbids fallback, shared capacity and location minima remain binding, stale-owner callbacks cannot authorize selection, and source-binding changes cannot bypass unresolved allocations. `HierarchicalCapacityFallbackTest` now injects only a definitive preferred-provider refusal, delegates alternate creation to the actual Ember provider, and asserts committed availability, released refused reservation, alternate observed source/readiness and shared ledger. It remains unexecuted. Ember has no native zone, which this test asserts remains absent; hard-zone constraints are covered by focused tests, not fabricated location facts. |
| H-T10 | MISSING | `CommunityPlacementReconcilerTest` covers create races, drain takeover, late acknowledgment, lost destination readiness, changed retirement proof, target zero and capacity overlap; live loaded movement proves happy-path quiescence. | Nine persisted-phase restart cases and two provider-binding/absence cases passed locally. `HierarchicalMovementTakeoverTest` adds a real leader loss while drain is pending; it is source-only, not executed. Existing movement Forge tests contain no leader takeover. |
| H-T11 | MISSING | QUIC bounded-retry tests, metadata blackout Forge, asymmetric governor-report loss Forge. | `HierarchicalWorkerReconnectTest` adds actual CONNECTED→EVICTED→CONNECTED transitions for all three workers, core commits during churn, metadata repair and resource-bound observations. Combine its six-process envelope with `HierarchicalRecoveryEnvelopeTest` regional blackout instrumentation; the envelope passed locally, while the actual-disconnect scenario remains unexecuted. Both require final-head execution. This is not a measured 10K reconnect storm. |
| H-T12 | DONE (bounded local) | Logical 100×100 producer/scope fixtures establish algorithmic bounds; small real Forge clusters establish wiring. | `HierarchicalRecoveryEnvelopeTest` passed locally at 3 cores plus 1/2/3 workers. Its JSON records 3-second warm-up, 20 HTTP samples per steady stage, 30 during recovery, CPU/heap/transport counters, convergence/recovery durations and percentiles. This is a single-JVM observation endpoint workload; physical WAN and 10K throughput remain unverified. Final-head CI must publish runner hardware and these records. |


### Local checkpoints and remaining execution

- `/private/tmp/hierarchy-review-runtime-targeted.log`: earlier-base targeted checkpoint;
  persistence, movement reconstruction and codec counts are recorded below. These are not
  results for the current integrated checkout.
- `/private/tmp/hierarchy-review-new-forge.log`: five live classes, four passed and one
  fixture failure. `HierarchicalDecisionReplayTest`, `HierarchicalWorkerRuntimeReplayTest`,
  `HierarchicalLeaderObservationGraceTest` and `HierarchicalRecoveryEnvelopeTest` each passed
  one scenario. `HierarchyAuthorityAcceptanceTest` failed before its authority/metrics
  assertions; the corrected fixture has no passing evidence yet.
- `/private/tmp/hierarchy-review-fallback-tests2.log`: BUILD SUCCESS, 48 tests, zero failures,
  errors or skips: fallback safety 8, reconciler 24, zone rotation 7, availability 4 and
  capacity lifecycle 5. These are focused production-component tests, not a live cloud
  provider fallback demonstration.
- Added live governor concurrency/restart, stale report replay, movement takeover and
  transport reconnect scenarios remain pending execution. Current integrated build and
  final-head CI evidence must be attached after they complete.

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

Execution evidence: `/private/tmp/hierarchy-review-runtime-targeted.log` reports BUILD SUCCESS;
JUnit reports contain `DurableRabiaPersistenceTest` 12/0/0/0, `CommunityPlacementReconcilerTest`
24/0/0/0, `WireAssignmentTripwireTest` 3/0/0/0 and `SystemCodecPinningTest` 3/0/0/0
(tests/failures/errors/skips). These runs exercised **uncommitted corrections over `9067424b2`**,
not a final published head or CI. The earlier four rc4-merge baseline gaps passed this real-registry
gate; new registrations after that checkpoint remain subject to revalidation.

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
9. `BLOCKED` / `CREATE_UNCERTAIN`: restart preserves escalation and bounded retry behavior; a
   policy edit/removal cannot orphan in-flight inventory or silently discard uncertainty.

## Interpretation limits

[limit: core-hosted-state] Core consensus/DHT state and workload endpoint cardinality remain
separate scaling costs from bounded community control topology.
[limit: application-graph] Cluster-wide application dependencies can require nonconstant
endpoint catalogs and connections; community peer scoping does not bound arbitrary workloads.
[unverified: final-head-ci] This reconciliation separates source coverage and bounded local results from the final execution ledger.
No historical worktree result should be presented as final merged-head CI evidence.
