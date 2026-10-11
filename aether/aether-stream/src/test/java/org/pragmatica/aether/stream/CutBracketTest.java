// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaSetController;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2087 (B4-cut): the log's cut generation is ODD while a cut runs and EVEN on every exit of it. A cut that is begun inside the ring's ordered
/// section and then fails to shrink the ring (so the ring's after-cut callback never runs) must not leave it odd: that would refuse every entity
/// read of the partition for good and invert the parity on the next cut.
class CutBracketTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 2L, 1L);

    private StreamPartitionManager replica;

    @BeforeEach
    void setUp() {
        replica = streamPartitionManager(Long.MAX_VALUE);
        replica.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        replica.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        for (var i = 0L; i < 3; i++) {
            replica.appendRecovered(STREAM, PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, E1).onFailure(cause -> fail(cause.message()));
        }
        assertThat(replica.appendRecovered(STREAM, PARTITION, 1L, "other".getBytes(UTF_8), 2000L, E2).isFailure()).as("control: a divergence at offset 1").isTrue();
    }

    @AfterEach
    void tearDown() {
        replica.close();
    }

    @Test
    void aCutWhoseRingShrinkFails_leavesTheGenerationEven_andTheNextCutStillBracketsCorrectly() {
        var ring = replica.partitionBuffer(STREAM, PARTITION).or(() -> fail("no ring"));
        var before = replica.cutGeneration(STREAM, PARTITION);

        ring.afterDurableCutProbe(() -> {
            throw new IndexOutOfBoundsException("injected: the shrink fails after the durable cut");
        });
        var failed = replica.repairDivergence(STREAM, PARTITION, _ -> true);

        assertThat(failed.isFailure()).as("the shrink failed, so the cut did not happen").isTrue();
        assertThat(replica.cutGeneration(STREAM, PARTITION) & 1L).as("every exit closes the bracket").isZero();
        assertThat(replica.appendedHead(STREAM, PARTITION)).as("control: the ring was not shrunk").isEqualTo(2L);

        ring.afterDurableCutProbe(() -> {});
        var seen = new AtomicLong(-1L);
        var done = replica.repairDivergence(STREAM, PARTITION, _ -> {
            seen.set(replica.cutGeneration(STREAM, PARTITION));
            return true;
        });

        assertThat(done.isSuccess()).as("control: the next cut proceeds").isTrue();
        assertThat(seen.get() & 1L).as("the parity did not invert: odd while the next cut runs").isEqualTo(1L);
        assertThat(replica.cutGeneration(STREAM, PARTITION) & 1L).as("even after it").isZero();
        assertThat(replica.cutGeneration(STREAM, PARTITION)).isNotEqualTo(before);
    }
}
