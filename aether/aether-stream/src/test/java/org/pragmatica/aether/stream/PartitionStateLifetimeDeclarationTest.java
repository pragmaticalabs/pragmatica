// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2201 NT-24 (spec 5.1): the extraction declares, for every per-partition row it moved, the lifetime it lives for and the
/// methods that remove it, and leaves none of rows 4-28 in [StreamPartitionManager]. Read by reflection, so a row added,
/// moved or left behind turns this red. The assignment pinned here is the spec table with the PR-2a rulings applied: rows 5, 6
/// (know `548514c110` Q2) and 4 (know `d9cd07cda1` S3) are standing state, not ring-scoped. RED at `15ac4ef4da`: the rows are
/// still fields of the manager and [PartitionState] is empty.
class PartitionStateLifetimeDeclarationTest {
    private static final Map<String, Set<String>> ROWS_BY_SUB_RECORD = Map.of("RingScoped",
                                                                              Set.of("durableWatermarks", "openCuts"),
                                                                              "Standing",
                                                                              Set.of("lastReplicatedWalWrite",
                                                                                     "unverifiedReplicas",
                                                                                     "verifiedPrefix",
                                                                                     "divergedAt",
                                                                                     "provenanceMismatchAt",
                                                                                     "epochTrust",
                                                                                     "replicaCommitted",
                                                                                     "pendingInstalls",
                                                                                     "raisedLocally",
                                                                                     "cutGenerations"),
                                                                              "RepairEpisode",
                                                                              Set.of("refusedCuts", "pendingCuts", "reportedUnsettled", "repairGenerationOf"));
    private static final Map<String, PartitionLifetime.Scope> SCOPE_OF_SUB_RECORD = Map.of("RingScoped",
                                                                                           PartitionLifetime.Scope.RING,
                                                                                           "Standing",
                                                                                           PartitionLifetime.Scope.STANDING,
                                                                                           "RepairEpisode",
                                                                                           PartitionLifetime.Scope.REPAIR_EPISODE);
    private static final Set<String> SCHEDULER_ROWS = Set.of("inFlightMaterializations",
                                                             "systemMaterializeQueue",
                                                             "appMaterializeQueue",
                                                             "queuedMaterializations",
                                                             "heldUnmaterializedSince",
                                                             "slotAcquiredTick",
                                                             "preemptedSlots",
                                                             "releaseCandidacy",
                                                             "durableAtPreviousTick");
    /// The stream-removal site (I-20): every row of a removed stream goes with it.
    private static final String PURGE = "purgePartitionState";

    @Test
    void partitionState_declaresEveryPartitionRow_inTheSubRecordOfItsLifetime() {
        var declared = Arrays.stream(PartitionState.class.getDeclaredClasses())
                             .filter(type -> ROWS_BY_SUB_RECORD.containsKey(type.getSimpleName()))
                             .collect(Collectors.toMap(Class::getSimpleName, PartitionStateLifetimeDeclarationTest::rowNames));

        assertThat(declared).as("rows 4-19 by sub-record").isEqualTo(ROWS_BY_SUB_RECORD);
    }

    @Test
    void everyPartitionRow_declaresTheLifetimeOfItsSubRecord_andRemovalSitesThatExist_includingTheStreamPurge() {
        var removalSites = removalSiteNames();
        var rows = subRecords().flatMap(type -> rowFields(type).map(field -> new Row(type, field))).toList();

        assertThat(rows).as("rows 4-19 are declared").hasSize(16);
        rows.forEach(row -> assertDeclared(row, removalSites));
    }

    @Test
    void streamPartitionManager_declaresNoneOfRows4To28() {
        var forbidden = Stream.concat(ROWS_BY_SUB_RECORD.values().stream().flatMap(Set::stream), SCHEDULER_ROWS.stream())
                              .collect(Collectors.toSet());
        var managerFields = Arrays.stream(StreamPartitionManager.class.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());

        assertThat(managerFields).as("the manager keeps none of rows 4-28").doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    void materializationScheduler_holdsRows20To28() {
        var schedulerFields = Arrays.stream(MaterializationScheduler.class.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());

        assertThat(schedulerFields).as("rows 20-28").containsAll(SCHEDULER_ROWS);
    }

    private record Row(Class<?> subRecord, Field field) {}

    private static void assertDeclared(Row row, Set<String> removalSites) {
        var name = row.subRecord().getSimpleName() + "." + row.field().getName();
        var lifetime = row.field().getAnnotation(PartitionLifetime.class);

        assertThat(lifetime).as("%s declares its lifetime", name).isNotNull();
        assertThat(lifetime.value()).as("%s lives as long as its sub-record", name).isEqualTo(SCOPE_OF_SUB_RECORD.get(row.subRecord().getSimpleName()));
        assertThat(lifetime.removedBy()).as("%s has a removal site, and the stream purge is one", name).contains(PURGE);
        assertThat(removalSites).as("%s: every removal site it names exists", name).contains(lifetime.removedBy());
    }

    private static Stream<Class<?>> subRecords() {
        return Arrays.stream(PartitionState.class.getDeclaredClasses()).filter(type -> ROWS_BY_SUB_RECORD.containsKey(type.getSimpleName()));
    }

    private static Stream<Field> rowFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers()));
    }

    private static Set<String> rowNames(Class<?> type) {
        return rowFields(type).map(Field::getName).collect(Collectors.toSet());
    }

    private static Set<String> removalSiteNames() {
        return Stream.concat(Stream.of(StreamPartitionManager.class, StreamEntries.class, PartitionState.class),
                             Arrays.stream(PartitionState.class.getDeclaredClasses()))
                     .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                     .map(Method::getName)
                     .collect(Collectors.toSet());
    }
}
