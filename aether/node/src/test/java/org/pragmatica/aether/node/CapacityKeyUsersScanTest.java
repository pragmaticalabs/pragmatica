// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// O3-3 (q4 7.1.3): after the readers moved onto `CapacityView` and the one outside writer onto `CapacityRecords`, the capacity keys are named by the
/// port, the key and value declarations and the classifications, and by nothing else. A new main source that names `CapacityReservationKey` or
/// `CapacityLedgerKey` (or filters a snapshot by `CapacityReservationValue`) bypasses the seam O3-4 replaces; this tripwire fails it by name.
///
/// A scan of the text, not a proof: a key assembled from a string defeats it. Controls: the patterns match the old inline reads, and the scan reaches
/// the declarations and the port.
class CapacityKeyUsersScanTest {
    private static final Pattern CAPACITY_KEYS = Pattern.compile("CapacityReservationKey|CapacityLedgerKey|instanceof\\s+(?:AetherValue\\.)?CapacityReservationValue|CapacityReservationValue\\.class");
    private static final List<String> ALLOWED = List.of("AetherKey.java",
                                                        "AetherValue.java",
                                                        "ReplicaFilter.java",
                                                        "WorkerMetadataIndex.java",
                                                        "CapacityView.java",
                                                        "CapacityRecords.java",
                                                        "LifecycleMutations.java",
                                                        "SystemTags.java");

    private static Path root() {
        return Path.of("..", "..").toAbsolutePath().normalize();
    }

    private static List<Path> mainSources() {
        try (var walk = Stream.of("aether", "integrations").map(root()::resolve).filter(Files::isDirectory).flatMap(CapacityKeyUsersScanTest::walk)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                       .filter(path -> path.toString().contains("/src/main/java/"))
                       .filter(path -> !path.toString().contains("/target/") && !path.toString().contains("/.m2-local/"))
                       .toList();
        }
    }

    private static Stream<Path> walk(Path directory) {
        try {
            return Files.walk(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void onlyThePortTheDeclarationsAndTheClassifications_nameTheCapacityKeys() {
        var offenders = mainSources().stream()
                                     .filter(path -> CAPACITY_KEYS.matcher(read(path)).find())
                                     .map(path -> path.getFileName().toString())
                                     .filter(name -> !ALLOWED.contains(name))
                                     .sorted()
                                     .toList();

        assertThat(offenders).as("main sources that read or write the capacity keys outside the port").isEmpty();
    }

    @Test
    void theScan_reachesThePortAndTheDeclarations() {
        var named = mainSources().stream().filter(path -> CAPACITY_KEYS.matcher(read(path)).find()).map(path -> path.getFileName().toString()).toList();

        assertThat(named).contains("CapacityView.java", "CapacityRecords.java", "AetherKey.java", "ReplicaFilter.java");
        assertThat(mainSources().stream().map(path -> path.getFileName().toString()).toList()).contains("CapacityControlledLifecycle.java",
                                                                                                         "CommunityPlacementReconciler.java",
                                                                                                         "NodeReplacementWiring.java",
                                                                                                         "WorkerHierarchyAssembly.java",
                                                                                                         "TopologyAssembly.java",
                                                                                                         "CoreAdmission.java");
    }

    @Test
    void thePattern_matchesTheOldInlineReads_andMissesThePortCalls() {
        assertThat(CAPACITY_KEYS.matcher("store.getTyped(new AetherKey.CapacityReservationKey(node), AetherValue.CapacityReservationValue.class)").find()).isTrue();
        assertThat(CAPACITY_KEYS.matcher("snapshot.containsKey(AetherKey.CapacityLedgerKey.INSTANCE)").find()).isTrue();
        assertThat(CAPACITY_KEYS.matcher("value instanceof AetherValue.CapacityReservationValue allocation").find()).isTrue();
        assertThat(CAPACITY_KEYS.matcher("CapacityView.overStore(store).reservation(node)").find()).isFalse();
        assertThat(CAPACITY_KEYS.matcher("CapacityRecords.forBuilding().create(node, value)").find()).isFalse();
    }
}
