// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1596, spec #1569 §7.5.1: where a partition log's owner-epoch history is recorded. Owner publish and live replica
/// receive attribute each record to the epoch it was first appended under; a catch-up apply attributes nothing
/// itself and installs the source's slice instead, after the N13 check. Each test names the mutation that reddens it.
class PartitionProvenanceRecordingTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(1, 0);
    private static final Epoch E2 = Epoch.epoch(2, 0);
    private static final byte[] PAYLOAD = "e".getBytes(UTF_8);

    @TempDir
    Path walDir;

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = open();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Nested
    class OwnerPublish {
        /// Red under "owner publish writes unattributed frames". The history survives a restart: it is on the volume.
        @Test
        void publish_recordsEachEpochOnce_atItsFirstOffset_durably() {
            publish(E1, 3);
            publish(E2, 2);

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 3));

            manager.close();
            manager = open();

            assertThat(history()).as("the history is read back from the volume").containsExactly(at(E1, 0), at(E2, 3));
        }

        /// The batch publish attributes its run too (to the manager's owner-epoch source, the unfenced floor here).
        /// Red under "batch publish writes unattributed frames".
        @Test
        void batchPublish_attributesTheRun() {
            manager.publishLocalBatchAtFloor(STREAM, PARTITION, List.of(PAYLOAD, PAYLOAD), 1L, 0)
                   .onFailure(cause -> fail(cause.message()));
            publish(E1, 1);

            assertThat(history()).containsExactly(at(Epoch.ZERO, 0), at(E1, 2));
        }
    }

    @Nested
    class ReplicaReceive {
        /// The live receive records the batch epoch; the catch-up append (no epoch of its own) records nothing.
        /// Red under "live receive unattributed" and under "catch-up append attributed to its fence epoch".
        @Test
        void liveReceive_recordsTheBatchEpoch_catchUpAppendRecordsNothing() {
            live(0, E1);
            live(1, E1);
            caughtUp(2);
            live(3, E2);

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 3));
        }

        /// Empty-history rule: a log holding records but no history (written before provenance, here by unattributed
        /// catch-up appends) records nothing when a live epoch arrives, so it reads as HISTORY_MISSING, never as a
        /// history starting mid-log. Red under "drop the empty-history rule".
        @Test
        void logWithRecordsButNoHistory_recordsNothing() {
            caughtUp(0);
            caughtUp(1);
            live(2, E1);

            assertThat(history()).isEmpty();
        }

        /// A batch of an epoch older than one the log already records is refused BEFORE the ring appends it, so ring
        /// and WAL stay in step. Red under "drop the pre-section provenance check" (the in-section refusal comes after
        /// the ring assigned the offset).
        @Test
        void olderEpochThanTheLogRecords_isRefusedBeforeTheRingAppends() {
            live(0, E2);
            live(1, E2);

            var refused = manager.appendRecovered(STREAM, PARTITION, 2, PAYLOAD, 1L, E1);

            refused.onSuccess(_ -> fail("an older epoch must be refused"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ProvenanceRegression.class));
            assertThat(manager.nextExpectedOffset(STREAM, PARTITION)).as("nothing entered the ring").isEqualTo(2L);
            assertThat(history()).containsExactly(at(E2, 0));
        }
    }

    @Nested
    class CatchUpInstall {
        /// N13 holds (the replica's own prefix matches the slice): the slice's entries past the replica's head are
        /// recorded. Red under "skip the install".
        @Test
        void matchingSlice_isRecorded() {
            live(0, E1);
            live(1, E1);
            live(2, E1);

            install(3, 6, at(E1, 0), at(E2, 5)).onFailure(cause -> fail(cause.message()));

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 5));
        }

        /// N13 fails: the source says e2 began at 3, this replica holds e1 records at 3 and 4. Nothing is recorded,
        /// the partition is quarantined at 3, the first offset whose provenance differs. Red under "drop N13".
        @Test
        void mismatchingSlice_isRefused_andQuarantinesAtTheFirstDifferingOffset() {
            for (var offset = 0; offset < 5; offset++) {
                live(offset, E1);
            }

            install(5, 8, at(E1, 0), at(E2, 3)).onSuccess(_ -> fail("a mismatching slice must be refused"))
                                               .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.ProvenanceMismatch(STREAM,
                                                                                                                                  PARTITION,
                                                                                                                                  3)));
            assertThat(history()).containsExactly(at(E1, 0));
            assertThat(manager.quarantinedAt(STREAM, PARTITION)).isEqualTo(Option.some(3L));
        }

        /// A replica holding nothing installs the whole slice, the base included.
        @Test
        void emptyReplica_installsTheWholeSlice() {
            install(0, 9, at(E1, 0), at(E2, 4)).onFailure(cause -> fail(cause.message()));

            assertThat(history()).containsExactly(at(E1, 0), at(E2, 4));
        }

        /// A partition that keeps no log records no provenance: the install is a no-op, never a refusal.
        @Test
        void withoutALog_installIsANoOp() {
            var walless = streamPartitionManager(Long.MAX_VALUE);

            walless.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
            walless.installProvenance(STREAM, PARTITION, 0, 9, List.of(at(E1, 0))).onFailure(cause -> fail(cause.message()));

            assertThat(walless.epochHistory(STREAM, PARTITION).unwrap()).isEmpty();
            walless.close();
        }
    }

    @Test
    void localProvenance_reportsTheHeadAndTheHistory() {
        publish(E1, 4);

        var local = manager.localProvenance(STREAM, PARTITION).unwrap();

        assertThat(local.head()).isEqualTo(3L);
        assertThat(local.base()).isEqualTo(0L);
        assertThat(local.history()).containsExactly(at(E1, 0));
    }

    private StreamPartitionManager open() {
        var opened = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        opened.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        return opened;
    }

    private void publish(Epoch epoch, int count) {
        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, PAYLOAD, 1L, epoch).onFailure(cause -> fail(cause.message()));
        }
    }

    private void live(long offset, Epoch epoch) {
        manager.appendRecovered(STREAM, PARTITION, offset, PAYLOAD, 1L, epoch).onFailure(cause -> fail(cause.message()));
    }

    private void caughtUp(long offset) {
        manager.appendRecovered(STREAM, PARTITION, offset, PAYLOAD, 1L).onFailure(cause -> fail(cause.message()));
    }

    private Result<?> install(long from, long to, ProvenanceEntry... slice) {
        return manager.installProvenance(STREAM, PARTITION, from, to, List.of(slice));
    }

    private List<ProvenanceEntry> history() {
        return manager.epochHistory(STREAM, PARTITION).unwrap();
    }

    private static ProvenanceEntry at(Epoch epoch, long start) {
        return ProvenanceEntry.provenanceEntry(epoch, Option.none(), start);
    }
}
