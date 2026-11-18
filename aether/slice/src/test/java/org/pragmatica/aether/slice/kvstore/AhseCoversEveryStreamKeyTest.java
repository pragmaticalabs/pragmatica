// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// T-09 (stream storage design §9.1, S0): every stream, topic, entity, consumer and storage-state key is a row of the AHSE
/// map (§5). The census enumerates the leaf record types of the sealed [AetherKey] hierarchy through
/// `getPermittedSubclasses()` (recursing through the sealed `ClusterStateKey` / `RuntimeKey` branches) and selects those that
/// name a stream-side artifact, by either of two readings of "names": the record's simple name matches the design's own search
/// space (§5, closing paragraph), or one of its components is named for a stream, topic, keyspace, group or storage instance.
/// Each selected record must be a key row of [#KEY_ROWS], the test's copy of the §5 list. A key-adding PR therefore has to add
/// its row to the design and to this copy in the same change (S3, S4a and S4b add theirs; the list ends at 39 rows in the design).
class AhseCoversEveryStreamKeyTest {
    /// The §5 rows at S0: rows 1-27 (today's artifacts) and 32-39 (found by enumerating `AetherKey`), 35 rows. Rows 28-31 and
    /// 40-41 are new artifacts of later PRs and are not copied until the PR that creates them.
    private static final List<String> ROWS = List.of("1 Ring (OffHeapRingBuffer)",
                                                     "2 WAL <stream>/<part>.wal",
                                                     "3 <log>.epochs",
                                                     "4 <log>.acked",
                                                     "5 <log>.failstopped",
                                                     "6 .pending-cut file",
                                                     "7 RecoverySegment",
                                                     "8 Sealed segment blocks",
                                                     "9 Sealed segment refs / index",
                                                     "10 Reclaimed-through floor ref",
                                                     "11 Metadata snapshot files",
                                                     "12 Node-local cursor ref",
                                                     "13 StreamCursorCheckpointKey",
                                                     "14 ConsumerAssignmentKey",
                                                     "15 ConsumerGroupKey and ConsumerGroupTombstone values",
                                                     "16 StreamConfigKey",
                                                     "17 StreamPartitionOwnershipKey",
                                                     "18 NodeLeaseKey",
                                                     "19 Entity checkpoint blocks",
                                                     "20 EntityCheckpointKey",
                                                     "21 StreamPartitionRecoveryKey",
                                                     "22 Control-log records",
                                                     "23 Control index / tail",
                                                     "24 Divergence quarantine maps",
                                                     "25 Replica watermarks (in-memory registry; the two store seams were deleted in S0-1)",
                                                     "26 Dead letters",
                                                     "27 Postgres segment sink and cursor store (deleted in S0-1)",
                                                     "32 TopicSubscriptionKey",
                                                     "33 TopicPublisherKey",
                                                     "34 StreamRegistrationKey",
                                                     "35 EntityKeyspaceRegistrationKey",
                                                     "36 StreamRegistryKey",
                                                     "37 StreamRegistryHolderKey",
                                                     "38 BlueprintStreamBindingsKey",
                                                     "39 StorageStatusKey");

    private static final List<Integer> S0_ROW_NUMBERS = IntStream.concat(IntStream.rangeClosed(1, 27),
                                                                                           IntStream.rangeClosed(32, 39))
                                                                                  .boxed()
                                                                                  .toList();
    private static final int ROWS_FINGERPRINT = -2097458471;

    /// The `AetherKey` record behind each key row, with its §5 row number.
    private static final Map<String, Integer> KEY_ROWS = Map.ofEntries(Map.entry("StreamCursorCheckpointKey", 13),
                                                                       Map.entry("ConsumerAssignmentKey", 14),
                                                                       Map.entry("ConsumerGroupKey", 15),
                                                                       Map.entry("StreamConfigKey", 16),
                                                                       Map.entry("StreamPartitionOwnershipKey", 17),
                                                                       Map.entry("NodeLeaseKey", 18),
                                                                       Map.entry("EntityCheckpointKey", 20),
                                                                       Map.entry("StreamPartitionRecoveryKey", 21),
                                                                       Map.entry("TopicSubscriptionKey", 32),
                                                                       Map.entry("TopicPublisherKey", 33),
                                                                       Map.entry("StreamRegistrationKey", 34),
                                                                       Map.entry("EntityKeyspaceRegistrationKey", 35),
                                                                       Map.entry("StreamRegistryKey", 36),
                                                                       Map.entry("StreamRegistryHolderKey", 37),
                                                                       Map.entry("BlueprintStreamBindingsKey", 38),
                                                                       Map.entry("StorageStatusKey", 39));

    /// Matches the §5 search space by name but is not a stream artifact: the DHT's own partition ownership (design §5, closing paragraph).
    private static final Set<String> NOT_STREAM_SIDE = Set.of("DhtPartitionOwnershipKey");
    private static final Pattern STREAM_SIDE_NAME = Pattern.compile("Stream|Consumer|Entity|Topic|Isr|Projection|Partition|Segment|Dlq|DeadLetter|Subscription");
    private static final Set<String> STREAM_SIDE_COMPONENTS = Set.of("stream", "streamName", "topic", "keyspace", "consumerGroup", "groupId", "instanceName");

    @Test
    void ahseCoversEveryStreamKey() {
        var uncovered = uncoveredBy(leafRecords(AetherKey.class));

        assertThat(uncovered).as("T-09: AetherKey records that name a stream, topic, keyspace, group or storage instance but are not a key row "
                                 + "of the AHSE map (stream storage design §5). Add the row to the design's §5 and to KEY_ROWS/ROWS here, "
                                 + "in the same PR that adds the key.")
                             .isEmpty();
        assertThat(ROWS).as("§5 rows copied at S0 (S3, S4a and S4b each add theirs)")
                        .hasSize(35);
    }

    /// ROWS is the copy of the §5 list, so its text is pinned three ways: the row numbers are the S0 sequence, each key row's
    /// text names the record [#KEY_ROWS] maps to that number, and the whole text matches its fingerprint (which catches an
    /// edit of a non-key row). A PR that adds a row updates ROWS, KEY_ROWS and [#ROWS_FINGERPRINT] together.
    @Test
    void copiedRows_arePinned() {
        var numbers = ROWS.stream()
                          .map(row -> Integer.valueOf(row.substring(0, row.indexOf(' '))))
                          .toList();

        assertThat(numbers).as("§5 row numbers at S0: 1-27 and 32-39")
                           .containsExactlyElementsOf(S0_ROW_NUMBERS);
        KEY_ROWS.forEach(AhseCoversEveryStreamKeyTest::assertKeyRowText);
        assertThat(String.join("\n", ROWS)
                         .hashCode()).as("fingerprint of the copied §5 row text; update ROWS_FINGERPRINT with the rows")
                                     .isEqualTo(ROWS_FINGERPRINT);
    }

    private static void assertKeyRowText(String record, int number) {
        assertThat(ROWS).as("row " + number + " names " + record)
                        .anyMatch(row -> row.startsWith(number + " ") && row.contains(record));
    }

    /// Positive control: a fixture key record outside the list is reported. Both selection readings are exercised: one
    /// fixture selected by its name, one only by a component name.
    @Test
    void census_reportsAFixtureKeyOutsideTheList() {
        var uncovered = uncoveredBy(List.of(StreamLookingFixtureKey.class, OpaqueNameFixtureKey.class, ProductionUnrelatedFixtureKey.class));

        assertThat(uncovered).containsExactlyInAnyOrder("StreamLookingFixtureKey", "OpaqueNameFixtureKey");
    }

    /// The census must reach the real hierarchy: the known stream-side records are enumerated, and every copied key row names a
    /// record that exists (a renamed or deleted key leaves the copy stale rather than silently passing).
    @Test
    void census_readsTheRealHierarchy_andEveryKeyRowNamesARealRecord() {
        var leaves = leafRecords(AetherKey.class).stream()
                                                 .map(Class::getSimpleName)
                                                 .collect(Collectors.toSet());

        assertThat(leaves.size()).as("AetherKey leaf records found")
                                 .isGreaterThan(60);
        assertThat(leaves).containsAll(KEY_ROWS.keySet());
        assertThat(streamSide(leafRecords(AetherKey.class))).as("stream-side records found by the census")
                                                             .contains("StreamConfigKey", "ConsumerAssignmentKey", "StorageStatusKey", "TopicPublisherKey");
        assertThat(KEY_ROWS.values()).allMatch(row -> row >= 1 && row <= 39);
    }

    private static Set<String> uncoveredBy(List<Class<?>> candidates) {
        var uncovered = new TreeSet<>(streamSide(candidates));

        uncovered.removeAll(KEY_ROWS.keySet());
        return uncovered;
    }

    private static Set<String> streamSide(List<Class<?>> candidates) {
        return candidates.stream()
                         .filter(Class::isRecord)
                         .filter(AhseCoversEveryStreamKeyTest::namesStreamSideArtifact)
                         .map(Class::getSimpleName)
                         .filter(name -> !NOT_STREAM_SIDE.contains(name))
                         .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean namesStreamSideArtifact(Class<?> type) {
        return STREAM_SIDE_NAME.matcher(type.getSimpleName())
                               .find() || Arrays.stream(type.getRecordComponents())
                                                .anyMatch(component -> STREAM_SIDE_COMPONENTS.contains(component.getName()));
    }

    /// Leaf (non-sealed-interface) permitted subclasses of `root`, found by `getPermittedSubclasses()` recursion.
    private static List<Class<?>> leafRecords(Class<?> root) {
        var leaves = new ArrayList<Class<?>>();

        for (var permitted : root.getPermittedSubclasses()) {
            if (permitted.isSealed()) {
                leaves.addAll(leafRecords(permitted));
            } else {
                leaves.add(permitted);
            }
        }
        return leaves;
    }

    record StreamLookingFixtureKey(String name) {}

    record OpaqueNameFixtureKey(String keyspace) {}

    record ProductionUnrelatedFixtureKey(String name) {}
}
