# Runtime hierarchy validation ledger

All local runs below used `/private/tmp/pragmatica-hierarchy-runtime-pr` and Java25.
These are checkpoint results, not final-head CI approval. Build/install the matching ref with
`env -u HCLOUD_TOKEN ./build.sh` before reproducing Forge; Forge consumes installed runtime jars.
The earlier integrated checkpoint is `20316f07bd732768e5cc9d7a999521e379bb4063`, incorporating rc4
`836832f5` and prerequisite heads `4601627c2` / `c6bcefb47`. Full build6 passed before this
integration; the subsequent node reactor install passed. Later corrections and their gates are listed below.

## Passed checkpoints

**Targeted146**, source `c5f3f2a19633faf8e3c22aeee67837ad8cee8875`: 146 cases, zero failures/errors,
BUILD SUCCESS. Local log: `/private/tmp/hierarchy-review-final-targeted.log`.
Original command (including its unmatched `DrainBudgetTest` selector):

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl integrations/consensus,aether/aether-metrics,aether/aether-deployment,aether/node,aether/ember,aether/slice -am test -Dtest='DurableRabiaPersistenceTest,RabiaDurableRestartTest,CommunityPlacementReconcilerTest,CommunityPlacementAvailabilityTest,CommunityPlacementFallbackSafetyTest,ClusterTopologyManagerZoneRotationTest,ProducerIncarnationTest,GovernorAuthorityTest,NodeLifecycleRoutesPromoteTest,DrainBudgetTest,CommunityHealthReporterTest,WorkerAdmissionTest,GovernorRecoveryTest,ClusterSyncObservationAuthorityTest,WireAssignmentTripwireTest,SystemCodecPinningTest,OwnershipEpochHighWaterTest,KVStoreAetherEpochFenceTest,CapacityControlledLifecycleTest,ClusterDeploymentStateDrainEvictionTest' -Dsurefire.failIfNoSpecifiedTests=false
```

`DrainBudgetTest` names no class and contributed zero cases. The actual
`NodeLifecycleRoutesDrainBudgetTest` cases ran in the separate Authority78 gate below.

**Authority78**, source `2a175cecf310d96c2b068697ae45578348703a0b`: 78 cases, zero failures/errors,
BUILD SUCCESS. Local log: `/private/tmp/hierarchy-review-authority-unit.log`.
Original command:

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl integrations/consensus,aether/node -am test -Dtest='RabiaHierarchySafetyTest,RabiaSyncAdoptionQuorumTest,RabiaReorderedDeliveryTest,RabiaVoterRecoveryTest,VoterConfigurationStateTest,CoreVoterReconcilerTest,NodeLifecycleRoutesDrainBudgetTest,CommittedLeaderRefreshTest,GovernorAnnouncerTest,GovernorAuthorityClientTest,CommunityHealthIndexTest,CommunityHealthRuntimeTest' -Dsurefire.failIfNoSpecifiedTests=false
```

**Twenty-class Forge matrix**, source `1bf7ff14d207d090b89bc9cf3dffc5d8feb26c5c`: 38 cases,
zero failures/errors, BUILD SUCCESS in approximately 1,390 seconds. Local log:
`/private/tmp/hierarchy-review-final-forge-matrix.log`. Reproduce the same class selection:

```sh
env -u HCLOUD_TOKEN ./forge.sh 'HierarchicalGovernorConcurrencyRestartTest,HierarchicalMetadataRecoveryTest,HierarchicalMovementTakeoverTest,HierarchicalRecoveryEnvelopeTest,HierarchicalGovernorReportLossTest,HierarchicalWorkerDrainTest,HierarchicalWorkerFormationTest,HierarchicalLoadedMovementTest,HierarchicalCoreRestartTest,ClusterFormationTest,EmberAddNodeRoleLabelTest,HierarchicalWorkerWorkloadTest,HierarchicalCommunityMovementTest,StreamOwnershipDriverFenceTest,HierarchicalWorkerReconnectTest,HierarchicalDecisionReplayTest,HierarchicalWorkerRuntimeReplayTest,HierarchicalCoreResizeTest,HierarchicalLeaderObservationGraceTest,SliceInvocationTest'
```

This matrix used the earlier Decision replay fixture. It does not prove the later receiver-side
phase-eviction/live-notification-count assertions. The reconnect envelope uses six nodes in one
JVM, not six processes; no physical 10K/WAN throughput claim follows.

**Corrected live authority**, source `1bf7ff14d207d090b89bc9cf3dffc5d8feb26c5c`: one scenario,
BUILD SUCCESS. Log `/private/tmp/hierarchy-review-authority-forge2.log`:

```sh
env -u HCLOUD_TOKEN ./forge.sh 'HierarchyAuthorityAcceptanceTest'
```

**Live capacity fallback**, source `2a175cecf310d96c2b068697ae45578348703a0b`: its one scenario
passed in `/private/tmp/hierarchy-review-authority-fallback-forge.log`. The shared two-class batch
failed the separate authority fixture; that batch is not a successful aggregate gate. Equivalent
class selection:

```sh
env -u HCLOUD_TOKEN ./forge.sh 'HierarchicalCapacityFallbackTest,HierarchyAuthorityAcceptanceTest'
```

The later standalone authority pass above supersedes that fixture failure, without changing
the source attribution of the successful fallback scenario.

## Correction gates

Source `20316f07bd732768e5cc9d7a999521e379bb4063`: this batch failed two cases (the then-unfixed replay liveness issue and an early security route probe). Reconnect and all three version-registry cases passed; the security replacement case also passed. The failures were retained and corrected in subsequent checkpoints:

```sh
env -u HCLOUD_TOKEN ./forge.sh 'HierarchicalDecisionReplayTest,HierarchicalWorkerReconnectTest,SliceVersionLifecycleTest,BlueprintSecurityOverrideClusterWideTest'
```

At `3f4c2239b`, the full three-case security class passed; replay still failed deterministically on write revision 3. The proposal-learning correction subsequently passed the strengthened live replay scenario. Its final source is `07f56f4af`; the final two-class recovery gate is recorded below when complete.

## Authoritative review artifacts

Local `/private/tmp` logs are operator breadcrumbs, not reviewer-accessible merge evidence.
The `.github/workflows/hierarchy-review.yml` runtime-acceptance job records the actual tested
merge SHA and PR head separately in uploaded `hierarchy-runtime-evidence`:

- `target/hierarchy-evidence/merge-sha.txt`
- `target/hierarchy-evidence/pr-head-sha.txt`
- runner/CPU/memory/Java metadata alongside those revisions
- Forge JUnit XML and `envelope.json` / `reconnect.txt`

The workflow executes `env -u HCLOUD_TOKEN ./build.sh` and the explicit hierarchy Forge selector
stored at that tested merge revision. Attach the passing workflow run/artifact URL to the PR;
check both revision files against the intended final head. Earlier local passes or older green
CI runs do not establish final integrated-head acceptance. Final-head runtime CI remains pending.

## Additional integrated regression commands

At `20316f07b`, 81 cases passed (Azure, core retirement/reconciliation, and newly integrated stream contracts):

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl aether/aether-stream,aether/environment/azure,aether/node -am test -Dtest='AzureComputeProviderTest,CoreCandidateRetirementTest,CoreVoterReconcilerTest,StreamWritePathContractTest,TieredReadVisibleBoundTest' -Dsurefire.failIfNoSpecifiedTests=false
```

The final proposal-learning implementation preserves the state-machine merge extension for normal batch delivery, learns only admitted nonpast proposals, and deduplicates request correlations when learning repeated proposals. Its focused install/test gate passed:

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl integrations/consensus -am install -Dtest='RabiaReorderedDeliveryTest,RabiaHierarchySafetyTest,RabiaDurableRestartTest,RabiaEngineApplyContainmentTest' -Dsurefire.failIfNoSpecifiedTests=false
```

The final local recovery selector at `07f56f4af` is:

```sh
env -u HCLOUD_TOKEN ./forge.sh 'HierarchicalDecisionReplayTest,HierarchicalMovementTakeoverTest'
```

Both cases passed at `07f56f4af`: zero failures/errors, BUILD SUCCESS, approximately 171 seconds. Log: `/private/tmp/hierarchy-review-final-recovery-forge.log`. The focused consensus install/test command above passed 27 cases, including the state-machine merge containment contract. The final-head CI gate supersedes local checkpoint evidence for merge readiness.

## Subsequent rc4 consumer-assignment integration

The branch also integrates rc4 `8d04025e6` through foundation `6938f323a` and specifications `33063845c`. The foundation preserves both the hierarchy owner/leader guards and rc4's cross-key consumer-assignment guard; its merged KV suite passed all 92 cases. Runtime adaptation carries `ConsumerAssignmentKey` in stream metadata and sends worker cursor checkpoints through the switchable core-forwarding delegate. The assembly test exercises that actual writer binding. Rebuild and integrated runtime checks are required after this source integration; the PR checks/artifacts identify the tested final merge revision.

The integrated full build passed, followed by 114 focused cases (zero failures/errors):

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl aether/node -am test -Dtest='WorkerRuntimeCommitWiringTest,WorkerMetadataIndexTest,ClusterCursorStoreTest,ConsumerAssignmentWriterTest,StreamConsumerManagerTest,StreamConsumerRuntimeClusterCursorTest,WireAssignmentTripwireTest,SystemCodecPinningTest,RabiaReorderedDeliveryTest,CoreCandidateRetirementTest' -Dsurefire.failIfNoSpecifiedTests=false
```

The post-integration live selector is `env -u HCLOUD_TOKEN ./forge.sh 'HierarchicalDecisionReplayTest,HierarchicalMovementTakeoverTest,DurableTopicDeliveryForgeTest'`. The attempted durable-topic class is disabled upstream and produced three skipped containers, so it supplies no delivery evidence. The runtime CI matrix instead includes the enabled `DeclarativeStreamConsumerTest`, which exercises assignment-driven delivery. Its results and tested revisions are recorded in the CI artifacts described above.

The post-integration recovery cases both passed at `bda9f6b29` (approximately 166 seconds including teardown). The separate enabled stream-consumer command is `env -u HCLOUD_TOKEN ./forge.sh 'DeclarativeStreamConsumerTest'`; its result is recorded in the PR/CI artifacts.

The enabled declarative consumer suite passed all nine cases with no skips (approximately 63 seconds, `hierarchy-review-consumer-assignment-forge2.log`). Its typed-delivery assertion now compares the exact identifiers published by that test across all nodes, retaining duplicates; this removes late traffic from earlier test methods without weakening loss/duplication checks. The prior run failed with 11 arrivals against a baseline expecting 10 because the separate publish probe arrived late. No production change was needed for that fixture correction.

## Snapshot pending-queue correction and final CI fixture repairs

The broad consensus run exposed duplicate application after a lagging voter adopted a newer
snapshot while retaining a request already covered by that snapshot. Reordered proposal
learning made that stale request spread again. Snapshot synchronization and voter handoff now
reconcile pending queues at the adopted frontier: matching pending batches retain local
correlations; absent batches become an explicit `SnapshotOutcomeUnknown` for local callers.
Equal-frontier adoption preserves local-only requests. The specification defines this ambiguity
and requires outcome reconciliation before retry. This is not a durable exactly-once request ledger.

`RabiaReorderedDeliveryTest.advancingSnapshotDoesNotReproposeCoveredRequestsAndReportsUnknownOutcome`
reproduces the boundary directly and also proves a retained caller receives its later result.
`sameFrontierSnapshotPreservesLocalRequestsMissingFromPeerQueue` pins the no-gap case.
Removing the discard operation made the first test fail with two pending requests instead of
one; restoring it passed both cases. The fair-schedule test repeats its 24 schedules twenty
times to vary executor interleavings while retaining prefix and no-duplicate assertions.

Docker's running-instance inspection now reads actual provider labels instead of returning an
empty tag map. Its 49-case suite passes. The conflicting-proposals integration fixture seeds
three real local proposals before any peer delivery, waits for the engine completion barriers,
and asserts nonempty V0 ballots in the first slot and round. Later slots may legitimately converge.

Final consensus and live-recovery gate results are recorded on the PR and by the merge-head
CI artifact contract above. A sandboxed full-suite attempt could not bind local QUIC sockets;
it is not correctness evidence and was replaced with an invocation that permits loopback sockets.

At production revision `035566cd9`, the complete consensus module passed **834 tests, zero
failures/errors/skips**, including local QUIC sockets and all repeated schedules:

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl integrations/consensus test
```

Log: `/private/tmp/hierarchy-review-consensus-full2.log` (approximately five minutes).
The two new request-outcome tests subsequently use bounded `Promise.await(TimeSpan)` for
terminal results, avoiding a race with asynchronous callback dispatch. Their focused rerun
is recorded in `/private/tmp/hierarchy-review-recovery-pending-final.log`.

The final production rebuild at `77f74b403` passed all six build steps. The following live
selector passed **12 tests with zero failures/errors/skips** (log
`/private/tmp/hierarchy-review-snapshot-recovery-forge.log`):

```sh
env -u HCLOUD_TOKEN ./forge.sh 'HierarchicalDecisionReplayTest,HierarchicalMovementTakeoverTest,HierarchicalCoreResizeTest,DeclarativeStreamConsumerTest'
```

The subsequent broad CI run found a Hetzner fixture that created server 42 but returned
server 1 from its readiness lookup. The fixture now returns server 42 and asserts the queried
identifier; no provider production behavior changed. All five compute-provider suites passed
**233 cases, zero failures/errors/skips**:

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl aether/environment/azure,aether/environment/aws,aether/environment/gcp,aether/environment/hetzner,aether/environment/docker -am test -Dtest='*ComputeProviderTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Log: `/private/tmp/hierarchy-review-all-compute-providers.log`. Because the provider dependency
failure prevented the earlier CI run from reaching node tests, the complete node module is
also exercised locally; its final result belongs to the PR and merge-head CI evidence.

## Contextual admission and node-fixture integration

The first complete node run reached 1,697 cases and exposed 22 failures: 19 boot cases whose
shared data-tier fault injection also blocked the newly mandatory durable control directory,
one alert test expecting immediate resampling inside the collector's one-second observation
interval, and two durable-topic cases. The latter identified a production omission:
`AdmittedSliceBridge` did not forward `invokeWithContext`, so legitimate contextual subscribers
received `CONTEXT_NOT_SUPPORTED` and entered dead-letter handling. The wrapper now delegates
contextual calls through the same admission gate. A dedicated regression preserves the exact
context and bytes, rejects a second call during drain, and retains execution through caller
cancellation. The existing real stream delivery/retry tests also pass.

Boot fixtures now configure a writable per-test control directory while retaining their
intentionally unwritable data-tier paths. The alert test polls for the next origin-preserving
sample within a bounded `TimeSpan`; its breach and clear assertions are unchanged.

The entire invocation module passed **311 tests with zero failures/errors/skips**:

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl aether/aether-invoke test
```

Log: `/private/tmp/hierarchy-review-invoke-full2.log`. All affected node paths then passed
**31 cases with zero failures/errors/skips**:

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl aether/node test -Dtest='EntityCheckpointLagMetricTest,AetherNode*BootTest,AetherNodeStorageShutdownTest,DeferredStartRedriveWiringBootTest,ScheduledTaskDrainWiringBootTest,SwimFaultyReArmBootTest,SwimHintInstallBootTest,WorkerJoinCtmWiringBootTest,WorkerRosterPruneBootTest,DurableTopicContextDeliveryTest'
```

Log: `/private/tmp/hierarchy-review-node-corrections.log`. The complete node suite is rerun after
these changes; its pre-existing disabled manual observability benchmark is not runtime evidence.

## Protocol-emission synchronization in the consensus fixture

At `bc0c6aa22`, repository CI exposed a test-only race: `handleNewBatch` queues phase start
behind a single executor barrier, so the conflicting-proposal fixture sometimes inspected
only two emitted proposals. The fixture now waits on bounded `Promise.await(TimeSpan)`
signals from actual protocol sends before delivering peer traffic, and similarly waits for
the initial votes. It requires three distinct local proposals and three distinct V0 voters
in the initial slot/round, and repeats twenty times. No production behavior changes.

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl integrations/consensus test -Dtest=RabiaConsensusIntegrationTest
```

The corrected fixture and its enclosing integration class passed with zero failures/errors/skips
(log `/private/tmp/hierarchy-review-proposal-emission-test2.log`). An initial local attempt
observed only the generic broadcast boundary; its timeouts exposed that voter messages use
addressed sends. Both test-network paths now record emissions through the same helper.

The complete node rerun at `bc0c6aa22` passed 1,697 cases, zero failures/errors and one existing
manual benchmark skip (`/private/tmp/hierarchy-review-node-full2.log`). The full downstream
gate passed 1,916 cases with zero failures/errors/skips: cluster 166, metrics 268, deployment
1,363 and control 119 (`/private/tmp/hierarchy-review-downstream-full.log`).

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl aether/node test
env -u HCLOUD_TOKEN mvn -T1 -pl integrations/cluster,aether/aether-metrics,aether/aether-deployment,aether/aether-control test
```

The six-step build at `bc0c6aa22` also passed (`/private/tmp/hierarchy-review-runtime-build9.log`).
Current-head CI and its published artifact remain the final merge evidence.

## Portable backup filtering and current rc4 integration

The rc4 `93a5e3089` integration at `25fb3f4a4` passed all six local build steps, 27 targeted
node/Ember SWIM startup and cleanup cases, and 16 rebuilt Forge smoke cases. Logs are
`/private/tmp/hierarchy-review-runtime-build10.log`,
`/private/tmp/hierarchy-review-rc4-swim-integration.log`, and
`/private/tmp/hierarchy-review-rc4-final-smoke.log`.

Repository CI then exposed one missing portable-backup classification: a committed
`CommunityPlacementAvailabilityValue` (source refusal observation) serialized as an empty
TOML value but had no restore parser. Like capacity reservations and placement operations,
this live observation is now excluded from portable configuration backup. This classification
is used only by `KVStoreSerializer`; binary consensus snapshots retain the record for recovery.
The regression exports a refusal beside durable configuration and restores that configuration
without carrying the stale refusal or failing the entire backup. The exhaustive section
symmetry check remains unchanged.

```sh
env -u HCLOUD_TOKEN mvn -T1 -pl aether/slice test
```

All **829 cases passed, zero failures/errors/skips**
(`/private/tmp/hierarchy-review-slice-full2.log`).

## Completed rc4 checkpoint and projection integration

At runtime `0756e148b` on rc4 `93a5e3089`, the full 145-module local reactor passed
(`env -u HCLOUD_TOKEN mvn -T4 install -B -pl '!examples'`,
`/private/tmp/hierarchy-review-final-reactor.log`, 23m38s). Repository CI
[35551119868](https://github.com/pragmaticalabs/pragmatica/actions/runs/35551119868) passed.
The published hierarchy artifact from
[35551119857](https://github.com/pragmaticalabs/pragmatica/actions/runs/35551119857)
contains **49 actual cases, zero failures/errors/skips**, four envelope records, runner hardware,
and three real worker connection evictions with continued core commits. It records PR head
`0756e148bbffd23cf0cde81ff48828f5ad3f53fd` and tested merge
`9bea31ef46ae815c38b883f3e41f2db68d0d3098`; GitHub confirms that merge's parents are the
PR head and rc4 `93a5e30896ac13c12fbc15ec0ac80459f96b5518`.

rc4 then added durable projections in `0002e2194`. Its integration retains both epoch-mint
strictness and same-epoch owner fencing (`KVStoreOwnerFenceTest` pins their interaction),
and keeps `ProjectionAwareCursorStore` around the cluster cursor store while directing its
command writer through `switchableCluster`. `WorkerRuntimeCommitWiringTest` traverses that
actual wrapper and pins the worker forwarding path. The integrated head's targeted checks,
live projection/consumer gates and CI must pass independently of the preceding checkpoint.

The combined projection integration passed **356 focused cases, zero failures/errors/skips**
in five modules (`/private/tmp/hierarchy-review-projection-integration.log`). Its selector covers
all KV-store tests, facade/rebuild behavior, binary/TOML cursor round trips, actual worker assembly,
read forwarding/bounds and both wire-registry gates. The complete six-step rebuild also passed.
The dedicated CI selector now includes `DurableProjectionRebuildForgeTest`, raising the expected
live matrix from 49 to 50 cases; its new-head artifact must include the added case without skips.
