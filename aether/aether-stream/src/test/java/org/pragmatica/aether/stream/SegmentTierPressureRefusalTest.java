// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1604: at [SegmentTierPressure#REFUSE_AT] an OWNER publish is refused, typed and transient, before it takes an
/// offset -- the WAL disk must not fill with records that can never be sealed. A replica append is not refused:
/// a replica that dropped what its owner accepted would diverge.
class SegmentTierPressureRefusalTest {
    private static final String STREAM = "orders";

    @Test
    void ownerPublish_isRefusedTyped_atTheRefusalThreshold_andAcceptedBelowIt() {
        var utilization = new AtomicReference<>(0.5);
        var manager = managerWith(utilization);

        publish(manager).onFailure(cause -> fail(cause.message()));

        utilization.set(SegmentTierPressure.REFUSE_AT);
        publish(manager).onSuccess(_ -> fail("the durable segment tier is at the refusal threshold"))
                        .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.SEGMENT_TIER_FULL));
        assertThat(StreamError.General.SEGMENT_TIER_FULL.transientCapacity()).as("retryable").isTrue();

        utilization.set(0.94);
        publish(manager).onFailure(cause -> fail("below the threshold again: " + cause.message()))
                        .onSuccess(offset -> assertThat(offset).as("the refused publish took no offset").isEqualTo(1L));
        manager.close();
    }

    @Test
    void replicaAppend_isNotRefused_atTheRefusalThreshold() {
        var manager = managerWith(new AtomicReference<>(0.99));

        manager.appendRecovered(STREAM, 0, "replicated".getBytes(StandardCharsets.UTF_8), 1_000L)
               .onFailure(cause -> fail("a replica must take what its owner accepted: " + cause.message()));
        manager.close();
    }

    private static StreamPartitionManager managerWith(AtomicReference<Double> utilization) {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        manager.segmentTierPressure(utilization::get);
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        return manager;
    }

    private static Result<Long> publish(StreamPartitionManager manager) {
        return manager.publishLocal(STREAM, 0, "event".getBytes(StandardCharsets.UTF_8), 1_000L);
    }
}
