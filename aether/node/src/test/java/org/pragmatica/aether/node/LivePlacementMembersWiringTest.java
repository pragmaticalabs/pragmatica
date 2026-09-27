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
        assertThat(assemblyCode()).contains("Option.option(clusterEventsControllerRef.get()).flatMap(ownershipController->ownershipController.desiredOwner(stream,partition)))");
    }

    /// #1555 sticky ownership: every node routes by the committed record, and backfill resolves the same owner.
    @Test
    void stickyOwnership_routesByCommittedRecord_andBackfillFollowsIt() {
        var code = assemblyCode();

        assertThat(code).contains("streamReplicaSetController.committedOwnerSource((stream,partition)->kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,partition),StreamPartitionOwnershipValue.class).map(StreamPartitionOwnershipValue::owner));");
        assertThat(code).contains("streamPartitionBackfill.ownerResolver(streamReplicaSetController::ownerFor);");
    }

    @Test
    void clusterEventsGateAndPlacementRole_readTheController() {
        var code = assemblyCode();

        assertThat(code).contains("clusterEventsControllerRef.set(streamReplicaSetController);");
        assertThat(code).contains("streamPartitionManager.placementRoleSupplier(streamReplicaSetController::roleFor);");
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
