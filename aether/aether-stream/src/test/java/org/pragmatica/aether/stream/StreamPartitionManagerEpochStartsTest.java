// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// I-24 (#1971, PR-6a, NT-140/NT-141): a non-owner copy is doubted from the start of the earliest committed epoch AFTER its own
/// lineage, taken from the committed epoch starts, never from the current epoch's start and never from 0 when the history is known.
/// The committed history here is E2 at 11 and E3 at 21; the copy holds 0..15.
class StreamPartitionManagerEpochStartsTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);
    private static final Epoch E3 = Epoch.epoch(1L, 1L, 3L);
    private static final Epoch E4 = Epoch.epoch(1L, 1L, 4L);

    private static final List<EpochStart> STALE_TAIL_STARTS = List.of(new EpochStart(E1, 0L), new EpochStart(E3, 11L));

    private final AtomicReference<Epoch> committed = new AtomicReference<>(E3);
    private final AtomicReference<List<EpochStart>> starts = new AtomicReference<>(List.of(new EpochStart(E2, 11L), new EpochStart(E3, 21L)));
    private final AtomicReference<Role> role = new AtomicReference<>(Role.OWNER);
    @TempDir
    Path wal;

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.ownerEpochSource((_, _) -> committed.get());
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.epochStarts((_, _) -> starts.get());
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// NT-140 (MT-67 reds it: the current epoch's start 21 would leave 11..15 trusted): the copy follows E1's lineage, which E2
    /// re-assigned from 11 on, across two epoch changes.
    @Test
    void replicaHolding0to15_epochsAt11And21_readerAt11_isRefused() {
        append(16);

        assertThat(manager.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.some(11L));

        var refused = manager.readServing(STREAM, PARTITION, 11L, 10);

        assertThat(refused.isFailure()).as("got %s", refused).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaNotVerified.class));
        var below = manager.readServing(STREAM, PARTITION, 0L, 10);

        assertThat(below.unwrap()).as("below 11 nothing is held back: %s %s", below, manager.unverifiedFrom(STREAM, PARTITION)).hasSize(10);
    }

    /// NT-141: a copy whose records end below the first start after its lineage is not doubted after the epochs advance.
    @Test
    void replicaWithValidPrefix_afterEpochAdvance_isNotDoubted() {
        append(11);

        assertThat(manager.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.none());
        assertThat(manager.replicaVerified(STREAM, PARTITION)).isTrue();
        assertThat(manager.readServing(STREAM, PARTITION, 5L, 10).isSuccess()).isTrue();
    }

    /// A copy verified for E2 follows E2's lineage: only E3's start (21) is after it, and its head 15 is below, so 0..15 stays served
    /// (the copy holding 0..20 would be doubted from 21, see the next test).
    @Test
    void copyVerifiedForTheMiddleEpoch_isDoubtedOnlyFromTheNextStart_andHeadBelowItIsTrusted() {
        append(16);
        manager.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(manager.unverifiedFrom(STREAM, PARTITION)).as("head 15 is below 21: nothing the copy holds was re-assigned").isEqualTo(Option.none());
        assertThat(manager.replicaVerified(STREAM, PARTITION)).isTrue();
        assertThat(manager.readServing(STREAM, PARTITION, 11L, 10).isSuccess()).isTrue();
    }

    @Test
    void copyVerifiedForTheMiddleEpoch_holdingPastTheNextStart_isDoubtedFromIt() {
        append(25);
        manager.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(manager.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.some(21L));
        assertThat(manager.replicaVerified(STREAM, PARTITION)).isFalse();
        assertThat(manager.readServing(STREAM, PARTITION, 11L, 10).isSuccess()).as("11..20 is of E2's lineage").isTrue();
    }

    /// A copy whose lineage IS the current epoch is doubted nowhere.
    @Test
    void copyVerifiedForTheCurrentEpoch_isNotDoubted() {
        append(16);
        manager.markVerifiedForEpoch(STREAM, PARTITION, E3);

        assertThat(manager.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.none());
    }

    /// No committed history: the copy is doubted from 0, as before the change.
    @Test
    void noCommittedHistory_isDoubtedFromZero() {
        starts.set(List.of());
        append(3);

        assertThat(manager.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.some(0L));
    }

    /// A doubt waits for the start it needs: the leader committed E3 before its owner committed E3's start; once the start
    /// is committed above the head, the copy is trusted without any compare.
    @Test
    void doubt_isReJudgedWhenTheStartIsCommitted() {
        starts.set(List.of());
        append(3);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).as("no history yet: doubted from 0").isFalse();

        starts.set(List.of(new EpochStart(E3, 8L)));

        assertThat(manager.replicaVerified(STREAM, PARTITION)).as("head 2 is below the committed start 8").isTrue();
    }

    /// A copy with a log is judged by the epochs its own log records: a copy that recorded E2 only, with E2's successor starting
    /// at 10 and its head at 4, holds nothing E3 re-assigned (without the log's epoch it would count as older than every kept
    /// start and be doubted from the oldest, 0).
    @Test
    void copyWithALog_isJudgedFromTheEpochItsLogRecorded() {
        try (var logged = loggedReplica(List.of(new EpochStart(E2, 0L), new EpochStart(E3, 10L)), E2, 5)) {
            assertThat(logged.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.none());
        }
    }

    /// A log whose newest recorded epoch IS the current one is doubted nowhere.
    @Test
    void copyWhoseLogRecordedTheCurrentEpoch_isNotDoubted() {
        try (var logged = loggedReplica(List.of(new EpochStart(E3, 0L)), E3, 5)) {
            assertThat(logged.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.none());
        }
    }

    /// v2858a A1 (p1): every epoch the copy's history records is judged, not the newest. Committed E1 at 0 and E3 at 11; the copy
    /// holds E1's 0..12 (E3 re-assigned 11 on) and then, contiguously, E3's live record at 13 (it missed E3's 11..12 batch). Its
    /// newest recorded epoch is the current one, yet its 11..12 are of the replaced lineage: doubted from 11.
    @Test
    void staleTailUnderACurrentEpochAppend_isDoubtedFromTheReassignedStart() {
        try (var logged = emptyLoggedReplica(STALE_TAIL_STARTS)) {
            record(logged, 0, 13, E1);
            record(logged, 13, 14, E3);

            assertThat(logged.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.some(11L));
            assertThat(logged.replicaVerified(STREAM, PARTITION)).isFalse();
            assertThat(logged.readServing(STREAM, PARTITION, 11L, 2).isFailure()).as("E1's replaced 11..12 are not served").isTrue();
        }
    }

    /// v2858a A1 (p1r): the doubt of the same copy survives a restart, which drops every in-memory verdict, because it comes from
    /// the copy's own history.
    @Test
    void staleTailUnderACurrentEpochAppend_isStillDoubtedAfterARestart() {
        try (var logged = emptyLoggedReplica(STALE_TAIL_STARTS)) {
            record(logged, 0, 13, E1);

            assertThat(logged.unverifiedFrom(STREAM, PARTITION)).as("first life, before the E3 append").isEqualTo(Option.some(11L));

            record(logged, 13, 14, E3);

            assertThat(logged.unverifiedFrom(STREAM, PARTITION)).as("first life, after the E3 append").isEqualTo(Option.some(11L));
        }
        try (var reopened = emptyLoggedReplica(STALE_TAIL_STARTS)) {
            assertThat(reopened.unverifiedFrom(STREAM, PARTITION)).as("after the restart").isEqualTo(Option.some(11L));
            assertThat(reopened.readServing(STREAM, PARTITION, 11L, 2).isFailure()).as("E1's replaced 11..12 are not served").isTrue();
        }
    }

    /// v2858a A2 (p2): a later epoch may start below an earlier one; the copy is doubted from the LOWEST start after its lineage.
    /// E3 starts at 9, below E2's 11: an E1 copy holding 0..10 holds E1's 9..10, which E3 re-assigned.
    @Test
    void laterEpochStartingBelowAnEarlierStart_doubtsFromTheLowest() {
        try (var logged = emptyLoggedReplica(List.of(new EpochStart(E1, 0L), new EpochStart(E2, 11L), new EpochStart(E3, 9L)))) {
            record(logged, 0, 11, E1);

            assertThat(logged.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.some(9L));
        }
    }

    /// A judge that read an OLDER current epoch keeps a later epoch's verdict, as `markVerifiedForEpoch` does: the copy verified
    /// for E3 is not re-judged at E2 (where nothing after its lineage is committed, so it would be doubted from 0).
    @Test
    void staleEpochJudge_keepsTheNewerVerification() {
        append(25);
        manager.markVerifiedForEpoch(STREAM, PARTITION, E3);
        committed.set(E2);

        assertThat(manager.unverifiedFrom(STREAM, PARTITION)).as("the E3 verification stands under a stale E2 view").isEqualTo(Option.none());
        assertThat(manager.replicaVerified(STREAM, PARTITION)).isTrue();
    }

    /// A copy verified for E3 whose committed epoch moved on to E4 before E4's start was committed: no start after its lineage is
    /// known, so it is doubted from 0 until that start is.
    @Test
    void verifiedLineage_withNoCommittedStartAfterIt_isDoubtedFromZero() {
        append(16);
        manager.markVerifiedForEpoch(STREAM, PARTITION, E3);
        committed.set(E4);

        assertThat(manager.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.some(0L));
    }

    /// The doubted range ends at the head: a copy whose ONLY record at or above the re-assigned start is its head is doubted.
    @Test
    void copyHoldingOnlyTheFirstReassignedOffset_isDoubtedFromIt() {
        try (var logged = emptyLoggedReplica(STALE_TAIL_STARTS)) {
            record(logged, 0, 12, E1);

            assertThat(logged.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.some(11L));
        }
    }

    /// Entries a catch-up installed above the head before its records are applied hold no record yet, so they doubt nothing:
    /// E2's range ends at the head + 1 (not at E3's entry), and E3's holds none (its epoch has no committed start after it,
    /// which would otherwise doubt from 0). The copy's E1 records 0..4 lie below E2's start 5.
    @Test
    void entriesInstalledAboveTheHead_doubtNothing() {
        committed.set(E4);
        try (var logged = emptyLoggedReplica(List.of(new EpochStart(E1, 0L), new EpochStart(E2, 5L), new EpochStart(E3, 6L)))) {
            record(logged, 0, 5, E1);
            logged.installProvenance(STREAM, PARTITION, 5L, 9L, List.of(owned(E1, 0L), owned(E2, 5L), owned(E3, 8L))).unwrap();

            assertThat(logged.epochHistory(STREAM, PARTITION).unwrap()).as("the pending install").hasSize(3);
            assertThat(logged.unverifiedFrom(STREAM, PARTITION)).isEqualTo(Option.none());
        }
    }

    private static ProvenanceEntry owned(Epoch epoch, long start) {
        return ProvenanceEntry.provenanceEntry(epoch, Option.none(), start);
    }

    private StreamPartitionManager emptyLoggedReplica(List<EpochStart> committedStarts) {
        return loggedReplica(committedStarts, E1, 0);
    }

    private static void record(StreamPartitionManager logged, int from, int toExclusive, Epoch epoch) {
        for (var i = from; i < toExclusive; i++) {
            logged.appendRecovered(STREAM, PARTITION, i, ("r-" + i).getBytes(UTF_8), 1000L + i, epoch).unwrap();
        }
        logged.syncReplicated(STREAM, PARTITION).await();
    }

    private StreamPartitionManager loggedReplica(List<EpochStart> committedStarts, Epoch recorded, int count) {
        var logged = streamPartitionManager(Long.MAX_VALUE, Option.some(wal));

        logged.ownerEpochSource((_, _) -> committed.get());
        logged.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest"))
              .onFailure(cause -> fail(cause.message()));
        logged.placementRoleSupplier((_, _) -> Role.REPLICA);
        logged.epochStarts((_, _) -> committedStarts);
        for (var i = 0; i < count; i++) {
            logged.appendRecovered(STREAM, PARTITION, i, ("old-" + i).getBytes(UTF_8), 1000L + i, recorded).unwrap();
        }
        logged.syncReplicated(STREAM, PARTITION).await();
        return logged;
    }

    private void append(int count) {
        for (var i = 0; i < count; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, ("old-" + i).getBytes(UTF_8), 1000L + i, E1).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        role.set(Role.REPLICA);
    }
}
