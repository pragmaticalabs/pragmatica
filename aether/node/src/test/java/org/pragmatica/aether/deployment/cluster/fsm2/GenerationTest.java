// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;

/// NT-114: generations only grow (R-1.6.1): across GC and leader changes, adoption keeps the row's own, and the wire carries a
/// `viewSequence` beyond 32 bits.
class GenerationTest {
    private static Generation generationOf(FsmHarness h, org.pragmatica.consensus.NodeId node) {
        return h.model().desired(pair(node, ORDERS)).map(SliceDesiredValue::generation).or(gen(0));
    }

    // NT-114
    // Red when: a fresh generation does not exceed earlier observations after GC and a leader change.
    @Test
    void freshGenerations_exceedEveryEarlierObservation_afterGcAndAfterALeaderChange() {
        var h = leader();

        h.activate();
        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        var first = generationOf(h, CORE_1);
        var n1 = node(h, CORE_1, 11);

        n1.follows(ORDERS);
        h.drain();
        n1.follows(ORDERS);
        h.drain();
        h.seedRemove(new SliceTargetKey(ORDERS.base()));
        h.drain();
        n1.follows(ORDERS);
        h.drain();
        h.seedRemove(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey(CORE_1, ORDERS));
        h.drain();
        h.advance(60_000L);
        assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS)).isPresent()).as("garbage collected").isFalse();

        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.activate();
        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();

        assertThat(generationOf(h, CORE_1)).as("after GC and a leader change").isGreaterThan(first);
        assertThat(generationOf(h, CORE_1).viewSequence()).isEqualTo(2L);
    }

    // NT-114
    // Red when: a new incarnation under the SAME leader value issues a generation not above the ones already in the store.
    @Test
    void sameLeaderValue_newIncarnation_continuesAboveTheStoredGenerations() {
        var h = leader();

        h.seedQuietly(new SliceDesiredKey(CORE_3, BILLING), desired(SliceGoal.ACTIVE, gen(50)));
        h.seedQuietly(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey(CORE_3, BILLING), row(SliceObservedState.ACTIVE, 1L, gen(50), process(13)));
        h.activate();
        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();

        assertThat(generationOf(h, CORE_1)).isGreaterThan(gen(50));
    }

    // NT-114
    // Red when: an adoption is written at another generation than the row's own.
    @Test
    void adoption_isWrittenAtTheRowsOwnGeneration() {
        var h = leader();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.seedQuietly(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey(CORE_2, ORDERS), row(SliceObservedState.ACTIVE, 1L, gen(33), process(12)));
        h.activate();

        assertThat(h.model().desired(pair(CORE_2, ORDERS)).map(d -> d.generation())).isEqualTo(org.pragmatica.lang.Option.some(gen(33)));
        assertThat(h.model().desired(pair(CORE_2, ORDERS)).map(d -> d.goal())).isEqualTo(org.pragmatica.lang.Option.some(SliceGoal.ACTIVE));
    }

    // NT-114
    // Red when: a Generation with viewSequence > 2^32 does not round-trip through the wire codec.
    @Test
    void generationBeyondThirtyTwoBits_roundTripsThroughTheCodec() {
        var generation = new Generation((1L << 32) + 5L, (1L << 33) + 9L);
        var key = SliceDesiredKey.sliceDesiredKey(CORE_1, ORDERS);
        var value = new SliceDesiredValue(SliceGoal.ACTIVE, generation, 1_760_000_000_000L);
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var buffer = Unpooled.buffer();

        codec.write(buffer, new KVCommand.Put<AetherKey, AetherValue>(key, value));
        var bytes = ByteBufUtil.getBytes(buffer);
        KVCommand.Put<?, ?> decoded = codec.read(buffer);

        assertThat(decoded.value()).isEqualTo(value);
        assertThat(((SliceDesiredValue) decoded.value()).generation()).isEqualTo(generation)
                                                                      .isGreaterThan(new Generation(1L << 32, 0L))
                                                                      .isGreaterThan(new Generation(7L, 0L));
        assertThat(bytes.length).isPositive();
    }
}
