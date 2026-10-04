// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1550 wiring pins for the single stream-placement source. The consumers are wired inline inside
/// `AetherNode`'s node assembly, which no unit test can construct, so these pins read the assembly source:
/// comments are stripped and whitespace removed, then each consumer's wiring expression must be present.
/// Reverting any consumer to a direct `coreNodes()` read (the #1390 regression shape) removes its expression
/// AND adds a second voter-set read, so it fails here twice. `LivePlacementMembersSeamTest` pins what the
/// source itself computes.
class LivePlacementMembersWiringTest {
    private static final Pattern VOTER_SET_READ = Pattern.compile("observer\\(\\)(\\.coreNodes\\(\\)|::coreNodes)");

    @Test
    void assembly_readsVoterSetOnlyThroughTheSingleSource() {
        var code = assemblyCode();
        var reads = VOTER_SET_READ.matcher(code)
                                  .results()
                                  .count();

        assertThat(reads).as("exactly one voter-set read in AetherNode; a second one is a placement consumer bypassing "
                             + "livePlacementMembers (#1550)")
                         .isEqualTo(1);
        assertThat(code).contains("varplacementMembers=livePlacementMembers(clusterTopologyManager.observer()::coreNodes,membershipFsm);");
    }

    @Test
    void replicaSetController_placesOverTheSingleSource() {
        assertThat(assemblyCode()).contains("ReplicaSetController.replicaSetController(streamReplicaRegistry,config.self(),placementMembers,");
    }

    @Test
    void backfillOrchestrator_fallsBackToTheSingleSource() {
        assertThat(assemblyCode()).contains("()->streamPlacementMembers(clusterEventsControllerRef,placementMembers),");
    }

    @Test
    void entityOwnershipReconciler_readsTheControllerSnapshot() {
        assertThat(assemblyCode()).contains("EntityOwnershipReconciler.entityOwnershipReconciler(kvStore,config.self(),streamReplicaSetController::reconciledMembers,");
    }

    /// Rebalance handoff (run 8, 02w): the receiving service must be told what the COMMITTED state says
    /// about this node, or every missing target answers terminally again — the wiring is the half a service
    /// unit test cannot see, because the test constructs its own predicate.
    @Test
    void entityForwardService_isGivenTheCommittedStakeTest() {
        assertThat(assemblyCode()).contains("EntityForwardService.entityForwardService(config.self(),clusterNode.network()::sendOutcome,ENTITY_FORWARD_TIMEOUT,CommittedEntityStake.committedEntityStake(kvStore,config.self()));");
    }

    /// The reconciler's event triggers: a real executor (not a no-op) for the retract-driven pass, and the
    /// committed-registration-removal route that wakes the leader's mint.
    @Test
    void entityReconcile_isTriggeredByRetractAndByACommittedRegistrationRemoval() {
        var code = assemblyCode();

        assertThat(code).contains("clusterCommandApplier,ENTITY_RECONCILE_KICK);");
        assertThat(code).contains("MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class,entityOwnershipReconciler::onRegistrationRemoved)");
    }

    @Test
    void consumerGroupOwnership_readsTheController() {
        var code = assemblyCode();

        assertThat(code).contains("varstreamConsumerOwnership=streamConsumerOwnership(streamPartitionManager,streamReplicaSetController);");
        assertThat(code).contains("publicList<NodeId>liveMembers(){returncontroller.reconciledMembers();}");
    }

    /// #1555 sticky ownership: the leader's writer decides by `desiredOwner` (keep the committed owner while it is in
    /// the leader's live set), not by the routing owner, which now follows the committed record.
    @Test
    void ownershipWriterHrwOwner_readsTheControllersDesiredOwner() {
        // #1730: the writer is the ISR-aware one -- the same desired-owner read, plus the live/initial-ISR inputs from the
        // controller and the committed leader its guarded transactions carry.
        assertThat(assemblyCode()).contains("Option.option(clusterEventsControllerRef.get()).flatMap(ownershipController->ownershipController.desiredOwner(stream,partition)),streamIsrInputs(clusterEventsControllerRef),()->kvStore.getTyped(LeaderKey.INSTANCE,LeaderValue.class));");
    }

    /// #1555 sticky ownership: every node routes by the committed record, and backfill resolves the same owner.
    @Test
    void stickyOwnership_routesByCommittedRecord_andBackfillFollowsIt() {
        var code = assemblyCode();

        assertThat(code).contains("streamReplicaSetController.committedOwnerSource((stream,partition)->kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,partition),StreamPartitionOwnershipValue.class).map(StreamPartitionOwnershipValue::owner));");
        assertThat(code).contains("streamPartitionBackfill.ownerResolver(streamReplicaSetController::ownerFor);");
    }

    /// #1555 items 7/8: the gate's block reaches the partition status read, the alarm is the operator warning, the
    /// unreachable window is two SWIM suspect windows, and the overlap read uses the node's rings and peers.
    @Test
    void ownerPromotionBlock_reachesStatusReadAlarmAndWindow() {
        var code = assemblyCode();

        // #1730: a partition with no live in-sync replica has no owner to report, so the controller's block joins it.
        assertThat(code).contains("streamPartitionManager.ownerBlockSource((stream,partition)->ownerActivation.blockOf(stream,partition).orElse(()->streamReplicaSetController.noInSyncReplica(stream,partition)));");
        assertThat(code).contains("AetherNode::raiseOwnerPromotionBlock,ownerPromotionAlarmWindow(config.timeouts().swim().suspectTimeout()));");
        assertThat(code).contains("returnsuspectTimeout.plus(suspectTimeout);");
        assertThat(code).as("v1555 F1: the overlap read is the production OwnerPeerReads.ownerRange the gate tests exercise")
                        .contains("OwnerPeerReads.ownerRange(config.self(),streamPartitionManager,streamTieredReader,streamForwardClient::readRemoteCatchup,STREAM_CATCHUP_BATCH_SIZE),AetherNode::raiseOwnerPromotionBlock");
    }

    /// #1730 phase 2 (B5-B7): the gate's relaxation reads the candidate's sealed floor and forgets a left-out peer's registry row; a
    /// replica that has not been compared with the committed owner of the current epoch acknowledges nothing.
    @Test
    void epochVerification_isWiredIntoTheGateAndTheReceiveHandler() {
        var code = assemblyCode();

        assertThat(code).contains("ownerActivation.sealedFloor(streamSegmentIndex::lastSealedOffset);");
        assertThat(code).contains("ownerActivation.peerRows((stream,partition,peer)->streamReplicaRegistry.updateWatermark(stream,partition,peer,-1L,ReplicationState.SYNCING));");
        assertThat(code).contains("streamReplicationReceiveHandler.ackGate(streamPartitionManager::replicaVerified);");
        assertThat(code).contains("streamReplicationManager.ownerEpochs(streamOwnerEpochSource);");
        assertThat(code).contains("streamPartitionManager.repairReportBound(TimeSpan.timeSpan(STREAM_BACKFILL_REDRIVE_INTERVAL.millis()*12L).millis());");
        assertThat(code).contains("ownerActivation.peerRingTail((node,stream,partition)->");
        assertThat(code).contains(":streamForwardClient.ringTailRemote(node,stream,partition).recover(_->Option.<Long>none()));");
    }

    @Test
    void clusterEventsGateAndPlacementRole_readTheController() {
        var code = assemblyCode();

        assertThat(code).contains("clusterEventsControllerRef.set(streamReplicaSetController);");
        assertThat(code).contains("streamPartitionManager.placementRoleSupplier(streamReplicaSetController::roleFor);");
    }

    /// #1638 N1/B2: the backfill single-flight is bounded in production by the idle bound derived from StreamingConfig;
    /// the unbounded factory is for tests only.
    @Test
    void backfillSingleFlight_isBoundedByTheConfiguredIdleBound() {
        assertThat(assemblyCode()).contains("streamPartitionManager.quarantineView(),Option.some(streamingConfig.backfillFlightIdleBound()));");
    }

    /// #1339 / #1732: `AetherNodeReplicaSetTriggersTest` drives the trigger helpers through wiring it builds itself, so
    /// deleting either production call site leaves it green. These pins are the half it cannot see: the placement-input
    /// triggers are registered on the node's routes, and the FSM transition hook reconciles on a counted-boundary edge.
    @Test
    void replicaSetInputTriggers_areWiredIntoTheAssembly() {
        var code = assemblyCode();

        assertThat(code).contains("wireReplicaSetInputTriggers(allEntries,streamReplicaSetController::reconcile,clusterNode::onVoterConfiguration);");
        assertThat(code).contains("reconcileReplicaSetOnCountedBoundary(clusterEventsControllerRef,record);");
    }

    /// `AetherNode.java` with line comments removed and all whitespace stripped, so a pin matches the
    /// wiring expression regardless of formatter line breaks. The path is derived from this class's own
    /// location (`<module>/target/test-classes` → `<module>/src/main/java`); an unreadable file fails loudly,
    /// because a pin that scans nothing always passes.
    private static String assemblyCode() {
        var file = sourceRoot().resolve("org/pragmatica/aether/node/AetherNode.java");

        assertThat(file).exists();

        return readFile(file).lines()
                             .map(line -> line.replaceFirst("//.*$", ""))
                             .collect(Collectors.joining())
                             .replaceAll("\\s+", "");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(LivePlacementMembersWiringTest.class.getProtectionDomain()
                                                                          .getCodeSource()
                                                                          .getLocation()
                                                                          .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }
}
