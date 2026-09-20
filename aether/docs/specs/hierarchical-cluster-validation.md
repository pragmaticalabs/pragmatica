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

The post-integration live selector is `env -u HCLOUD_TOKEN ./forge.sh 'HierarchicalDecisionReplayTest,HierarchicalMovementTakeoverTest,DurableTopicDeliveryForgeTest'`. The runtime CI matrix also includes durable-topic delivery. Its results and tested revisions are recorded in the CI artifacts described above.
