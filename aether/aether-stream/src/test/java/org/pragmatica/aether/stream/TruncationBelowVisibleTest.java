// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.WarningLevel;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2981 step 2 (design 2.2 check 3, r7.1 C2): a divergent-tail cut that would go below the ring's visible offset is REFUSED for a
/// confirmation-factor >= 2 partition (nothing is cut, the quarantine stands, `stream-truncation-refused` is raised, the `refused` counter moves) and
/// proceeds as a counted RETRACTION for CF = 1 (durable or not): `stream-truncation-retracted` at INFO, the `retracted` counter moves. The
/// refusal is closed by `stream-truncation-quarantine-cleared` when the cut finally goes through or the replica goes away.
class TruncationBelowVisibleTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long VISIBLE = 2L;
    private static final long KEEP = 0L;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 2L, 1L);

    @TempDir
    Path walDir;

    private final List<OperatorWarning> delivered = new CopyOnWriteArrayList<>();
    private StreamPartitionManager replica;

    @AfterEach
    void tearDown() {
        replica.close();
    }

    @Test
    void confirmationFactorTwo_cutBelowVisible_isRefused_nothingIsCut_andCounted() {
        quarantinedReplica(2, false);

        var refused = replica.repairDivergence(STREAM, PARTITION, _ -> true);

        assertThat(refused.isFailure()).as("the cut is refused").isTrue();
        refused.onSuccess(_ -> fail("must be refused"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.TruncateBelowVisible.class));
        assertNothingCut();
        assertThat(replica.truncationRefusedBelowVisible()).isEqualTo(1L);
        assertThat(replica.truncationRetractedBelowVisible()).isZero();
        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_TRUNCATION_REFUSED);
        assertThat(delivered.getFirst().subject()).isEqualTo(STREAM + "[" + PARTITION + "]");
    }

    @Test
    void aRefusalRetried_isCountedEachTime_butRaisedOncePerEpisode() {
        quarantinedReplica(2, false);

        replica.repairDivergence(STREAM, PARTITION, _ -> true);
        replica.repairDivergence(STREAM, PARTITION, _ -> true);

        assertThat(replica.truncationRefusedBelowVisible()).isEqualTo(2L);
        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_TRUNCATION_REFUSED);
        assertNothingCut();
    }

    @Test
    void confirmationFactorThree_alsoRefuses() {
        quarantinedReplica(3, false);

        assertThat(replica.repairDivergence(STREAM, PARTITION, _ -> true).isFailure()).isTrue();
        assertNothingCut();
    }

    @Test
    void confirmationFactorOne_durable_cutBelowVisible_isRetracted_andCounted() {
        quarantinedReplica(1, true);

        var cut = replica.repairDivergence(STREAM, PARTITION, _ -> true);

        assertThat(cut.isSuccess()).as("the legitimate rejoin repair goes through").isTrue();
        assertRetracted();
    }

    @Test
    void confirmationFactorOne_nonDurable_cutBelowVisible_isRetracted_andCounted() {
        quarantinedReplica(1, false);

        var cut = replica.repairDivergence(STREAM, PARTITION, _ -> true);

        assertThat(cut.isSuccess()).as("the legitimate rejoin repair goes through").isTrue();
        assertRetracted();
    }

    @Test
    void aCutAtVisible_isNeitherRefusedNorCounted() {
        quarantinedReplica(2, false, KEEP);

        assertThat(replica.repairDivergence(STREAM, PARTITION, _ -> true).isSuccess()).as("keep == visible is not below visible").isTrue();
        assertThat(replica.truncationRefusedBelowVisible()).isZero();
        assertThat(replica.truncationRetractedBelowVisible()).isZero();
        assertThat(codes()).isEmpty();
    }

    /// A configuration can only get STRONGER online (CF never drops on a live life), so the refusal is in practice closed by the replica going
    /// away; the resume path still has to name the right recovery should the cut ever go through after a refusal. The resume is driven directly.
    @Test
    void aRefusal_isClosedByQuarantineCleared_whenTheCutResumes() {
        quarantinedReplica(2, false);
        replica.repairDivergence(STREAM, PARTITION, _ -> true);

        replica.cutResumed(STREAM, PARTITION, new StreamPartitionManager.PartitionRef(STREAM, PARTITION));

        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_TRUNCATION_REFUSED,
                                            OperatorWarningCode.STREAM_TRUNCATION_QUARANTINE_CLEARED);
    }

    private void assertRetracted() {
        var ring = replica.partitionBuffer(STREAM, PARTITION).or(() -> fail("no ring"));

        assertThat(replica.appendedHead(STREAM, PARTITION)).as("the data was cut back to keep").isEqualTo(KEEP);
        assertThat(ring.visibleOffset()).as("visible lowered with the cut").isEqualTo(KEEP);
        assertThat(replica.truncationRetractedBelowVisible()).isEqualTo(1L);
        assertThat(replica.truncationRefusedBelowVisible()).isZero();
        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_TRUNCATION_RETRACTED);
        assertThat(delivered.getFirst().code().level()).as("a retraction is INFO").isEqualTo(WarningLevel.INFO);
    }

    private void assertNothingCut() {
        var ring = replica.partitionBuffer(STREAM, PARTITION).or(() -> fail("no ring"));

        assertThat(replica.appendedHead(STREAM, PARTITION)).as("the ring keeps every record").isEqualTo(VISIBLE);
        assertThat(ring.visibleOffset()).as("visible is unchanged").isEqualTo(VISIBLE);
        assertThat(replica.quarantinedAt(STREAM, PARTITION).isPresent()).as("the quarantine stands").isTrue();
    }

    /// The sink hands off on its own thread: the delivered list is read once it stopped growing for a quiet period.
    private List<OperatorWarningCode> codes() {
        var last = -1;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (delivered.size() != last && System.nanoTime() < deadline) {
            last = delivered.size();
            sleepQuietly();
        }
        return delivered.stream().map(OperatorWarning::code).toList();
    }

    private void quarantinedReplica(int confirmationFactor, boolean withWal) {
        quarantinedReplica(confirmationFactor, withWal, VISIBLE);
    }

    private void quarantinedReplica(int confirmationFactor, boolean withWal, long visibleThrough) {
        replica = withWal
                  ? streamPartitionManager(Long.MAX_VALUE, Option.some(walDir))
                  : streamPartitionManager(Long.MAX_VALUE);
        replica.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        replica.operatorWarnings(OperatorWarningSink.handingOffTo(delivered::add));
        replica.createStream(configOf(confirmationFactor)).onFailure(cause -> fail(cause.message()));
        for (var i = 0L; i <= VISIBLE; i++) {
            replica.appendRecovered(STREAM, PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, E1)
                   .onFailure(cause -> fail(cause.message()));
        }
        replica.syncReplicated(STREAM, PARTITION).await();
        assertThat(replica.appendRecovered(STREAM, PARTITION, 1L, "other".getBytes(UTF_8), 2000L, E2).isFailure())
            .as("control: a divergence at offset 1").isTrue();
        replica.partitionBuffer(STREAM, PARTITION).or(() -> fail("no ring")).advanceVisible(visibleThrough);
    }

    private static void sleepQuietly() {
        try {
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    private static StreamConfig configOf(int confirmationFactor) {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         confirmationFactor);
    }
}
