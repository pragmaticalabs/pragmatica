// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.lang.Option;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 I-20, verifier v2559 on PR-2a: the CONSEQUENCE of an entry-less write that lands after the purge. The stream
/// partition ownership record is keyed by `(stream, partition)` and is not removed with the stream, so a stream created
/// again under the same name keeps the SAME owner epoch unless its owner changes. A late `commitAdvanced` (an owner's
/// visible position in flight across the removal) or `markVerifiedForEpoch` (a backfill finishing across it) is then
/// indistinguishable from one of the new life. Each test asserts the I-20 behaviour; the controls show the same sequence
/// without the late write.
class LateEntryLessWriteConsequenceTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch EPOCH_1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch EPOCH_2 = Epoch.epoch(1L, 2L, 1L);

    private final StreamPartitionManager manager = streamPartitionManager(Long.MAX_VALUE);

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// #2087 says a replica serves no record its owner has not reported visible. The late position of the removed life
    /// makes the new life's unreported records visible at once.
    @Test
    void lateCommitAdvance_sameEpoch_doesNotExposeTheRecreatedLifesUnreportedRecords() {
        replicaRecreatedAfter(() -> manager.commitAdvanced(STREAM, PARTITION, 1000L, EPOCH_1));
        replicate(0L, "n0", EPOCH_1);
        replicate(1L, "n1", EPOCH_1);

        assertThat(readLocal()).as("the new life's owner reported nothing visible").isEmpty();

        manager.commitAdvanced(STREAM, PARTITION, 0L, EPOCH_1);

        assertThat(readLocal()).as("the new life's owner reported offset 0 only").containsExactly("n0");
    }

    @Test
    void control_noLateWrite_sameEpoch_exposesOnlyWhatTheOwnerReported() {
        replicaRecreatedAfter(() -> {});
        replicate(0L, "n0", EPOCH_1);
        replicate(1L, "n1", EPOCH_1);

        assertThat(readLocal()).isEmpty();

        manager.commitAdvanced(STREAM, PARTITION, 0L, EPOCH_1);

        assertThat(readLocal()).containsExactly("n0");
    }

    /// The owner changed with the re-create (a newer epoch): the first batch carries the stale position into the new
    /// epoch (`noteBatchEpoch`), and the new owner's lower reports under that epoch are ignored.
    @Test
    void lateCommitAdvance_newerEpoch_doesNotExposeTheRecreatedLifesUnreportedRecords() {
        replicaRecreatedAfter(() -> manager.commitAdvanced(STREAM, PARTITION, 1000L, EPOCH_1));
        replicate(0L, "n0", EPOCH_2);
        replicate(1L, "n1", EPOCH_2);
        manager.commitAdvanced(STREAM, PARTITION, 0L, EPOCH_2);

        assertThat(readLocal()).as("the new life's owner reported offset 0 only").containsExactly("n0");
    }

    @Test
    void control_noLateWrite_newerEpoch_exposesOnlyWhatTheOwnerReported() {
        replicaRecreatedAfter(() -> {});
        replicate(0L, "n0", EPOCH_2);
        replicate(1L, "n1", EPOCH_2);
        manager.commitAdvanced(STREAM, PARTITION, 0L, EPOCH_2);

        assertThat(readLocal()).containsExactly("n0");
    }

    /// A copy that holds records at or above its epoch's start is doubted until a compare clears it. A late verdict of
    /// the removed life, at the same epoch, clears the new life's copy without any compare.
    @Test
    void lateMarkVerifiedForEpoch_doesNotVerifyTheRecreatedLifesCopy() {
        manager.ownerEpochSource((_, _) -> EPOCH_1);
        replicaRecreatedAfter(() -> manager.markVerifiedForEpoch(STREAM, PARTITION, EPOCH_1));
        replicate(0L, "n0", EPOCH_1);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).as("the new life's copy was never compared").isFalse();
    }

    @Test
    void control_noLateWrite_theRecreatedLifesCopyIsDoubted() {
        manager.ownerEpochSource((_, _) -> EPOCH_1);
        replicaRecreatedAfter(() -> {});
        replicate(0L, "n0", EPOCH_1);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).isFalse();
    }

    private void replicaRecreatedAfter(Runnable lateWrite) {
        manager.placementRoleSupplier((_, _) -> Role.REPLICA);
        createStream();
        manager.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        assertThat(manager.heldPartitionRows(STREAM, PARTITION)).as("fixture: the destroy purged the name").isEmpty();
        lateWrite.run();
        createStream();
    }

    private void createStream() {
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(),
                                                       "earliest",
                                                       1_048_576L,
                                                       ConsistencyMode.EVENTUAL,
                                                       3,
                                                       2,
                                                       StreamCompression.NONE,
                                                       Option.none()))
               .onFailure(cause -> fail(cause.message()));
    }

    private void replicate(long offset, String payload, Epoch epoch) {
        manager.appendRecovered(STREAM, PARTITION, offset, payload.getBytes(UTF_8), 1L + offset, epoch)
               .onFailure(cause -> fail("replica append failed: " + cause.message()));
    }

    private List<String> readLocal() {
        return manager.readLocal(STREAM, PARTITION, 0L, 100)
                      .map(events -> events.stream().map(event -> new String(event.data(), UTF_8)).toList())
                      .onFailure(cause -> fail("read failed: " + cause.message()))
                      .or(List.of());
    }
}
