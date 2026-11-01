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
    private static final Pattern CAPACITY_KEYS = Pattern.compile("CapacityReservationKey|CapacityLedgerKey|instanceof\\s+(?:AetherValue\\.)?Capacity(?:Reservation|Ledger)Value|Capacity(?:Reservation|Ledger)Value\\.class");
    /// The port's key accessors: a key obtained from them and handed to a raw store read names no capacity class, so the pattern above cannot see it.
    private static final Pattern KEY_ACCESSORS = Pattern.compile("\\b(?:recordKey|ledgerKey)\\s*\\(");
    private static final List<String> KEY_ACCESSOR_USERS = List.of("CapacityRecords.java", "CapacityControlledLifecycle.java");
    /// The files that only CLASSIFY the keys (an arm of an exhaustive switch, or a comment): every line of theirs that names a capacity key must be one.
    private static final List<String> CLASSIFIERS = List.of("WorkerMetadataIndex.java", "ReplicaFilter.java");
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
    void noOtherMainSource_callsThePortsKeyAccessors() {
        var offenders = mainSources().stream()
                                     .filter(path -> KEY_ACCESSORS.matcher(read(path)).find())
                                     .map(path -> path.getFileName().toString())
                                     .filter(name -> !KEY_ACCESSOR_USERS.contains(name))
                                     .sorted()
                                     .toList();

        assertThat(offenders).as("main sources that take a capacity key from the port and read the store with it").isEmpty();
        assertThat(mainSources().stream().filter(path -> KEY_ACCESSORS.matcher(read(path)).find()).map(path -> path.getFileName().toString()).toList())
            .as("control: the accessors are used where they are allowed").containsAll(KEY_ACCESSOR_USERS);
    }

    @Test
    void theClassifyingFiles_nameTheCapacityKeys_onlyInSwitchArmsAndComments() {
        var offending = mainSources().stream()
                                     .filter(path -> CLASSIFIERS.contains(path.getFileName().toString()))
                                     .flatMap(path -> read(path).lines()
                                                                .filter(line -> CAPACITY_KEYS.matcher(line).find())
                                                                .map(String::strip)
                                                                .filter(line -> !(line.startsWith("case ") || line.startsWith("//") || line.startsWith("*")))
                                                                .map(line -> path.getFileName() + ": " + line))
                                     .toList();

        assertThat(offending).as("a read added to a file that is allowed to name the keys only to classify them").isEmpty();
        assertThat(mainSources().stream().filter(path -> CLASSIFIERS.contains(path.getFileName().toString())).count()).as("control: both classifiers are scanned").isEqualTo(2);
    }

    /// The core admission's reservation source (`TopologyAssembly`) was pinned by no test over the whole node module: replacing it with "no reservation" left all
    /// 4,499 tests green. The wiring is one line in a method that needs a running topology to exercise, so it is pinned as text until O3-4 extracts a seam for it.
    @Test
    void theCoreAdmission_isGivenItsReservationsByTheView() {
        var source = read(root().resolve("aether/node/src/main/java/org/pragmatica/aether/node/assembly/TopologyAssembly.java")).replaceAll("\\s+", " ");

        assertThat(source).contains("CoreAdmission.coreAdmission(membershipFsmRef::get, verifiedVoterIdentities::get, CapacityView.overStore(kvStore)::reservation,");
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
        assertThat(CAPACITY_KEYS.matcher("value instanceof AetherValue.CapacityLedgerValue counter").find()).isTrue();
        assertThat(KEY_ACCESSORS.matcher("store.get(CapacityRecords.forBuilding().recordKey(node))").find()).isTrue();
        assertThat(KEY_ACCESSORS.matcher("CapacityView.overStore(store).reservation(node)").find()).isFalse();
        assertThat(CAPACITY_KEYS.matcher("CapacityView.overStore(store).reservation(node)").find()).isFalse();
        assertThat(CAPACITY_KEYS.matcher("CapacityRecords.forBuilding().create(node, value)").find()).isFalse();
    }
}
