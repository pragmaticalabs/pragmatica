// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.T0;
import static org.pragmatica.aether.node.HoldRecordsFixture.ofEpisode;

/// The bounds of the hold records (#3345, H11): a node's entry is dropped once its episodes are closed, their closers landed
/// and the node left the view; an unlanded closer is re-raised only by the tick, at most once per backoff period, never by a
/// pong. Counts, not wall time: the raise calls are counted on the fake event layer.
class HoldRecordsBoundTest {
    private static final OperatorWarningCode DELIVERED = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED;
    private static final OperatorWarningCode HOLD_ENDED = OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED;
    private static final OperatorWarningCode AT_RISK = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK;

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    @Test
    void tick_tenThousandNodesFinishedLandedAndGone_mapIsEmpty() throws ReflectiveOperationException {
        var nodes = nodes(10_000);

        nodes.forEach(nodeId -> pong(nodeId, HeldEnd.NONE));
        nodes.forEach(nodeId -> pong(nodeId, HeldEnd.DELIVERED));
        nodes.forEach(fx.records::onLeft);
        assertThat(recorded()).as("finished, but the FAN still reports them: kept as tombstones").isEqualTo(10_000);

        fx.live.clear();
        fx.records.tick();

        assertThat(fx.raisedOf(AT_RISK)).isEqualTo(10_000);
        assertThat(fx.closersOf(DELIVERED)).hasSize(10_000);
        assertThat(recorded()).as("every entry dropped").isZero();
    }

    @Test
    void tick_twoThousandUnlandedClosers_reRaisesEachAtMostOncePerBackoff() {
        var nodes = nodes(2_000);

        nodes.forEach(nodeId -> pong(nodeId, HeldEnd.NONE));
        fx.lands = false;
        nodes.forEach(fx.records::onDead);
        fx.live.clear();
        assertThat(fx.closersOf(HOLD_ENDED)).as("one closer per death").hasSize(2_000);

        nodes.forEach(fx.records::onPong);
        fx.records.tick();
        assertThat(fx.closersOf(HOLD_ENDED)).as("no retry on a pong, none on a tick inside the backoff").hasSize(2_000);

        fx.now = T0 + 1_000;
        fx.records.tick();
        fx.records.tick();
        assertThat(fx.closersOf(HOLD_ENDED)).as("first backoff passed: each re-raised once").hasSize(4_000);

        fx.now = T0 + 2_000;
        fx.records.tick();
        assertThat(fx.closersOf(HOLD_ENDED)).as("the backoff doubled: no retry yet").hasSize(4_000);

        fx.now = T0 + 3_000;
        fx.records.tick();
        assertThat(fx.closersOf(HOLD_ENDED)).as("second backoff passed: each re-raised once more").hasSize(6_000);
    }

    private static List<NodeId> nodes(int count) {
        return IntStream.range(0, count)
                        .mapToObj(index -> NodeId.nodeId("bound-" + index).unwrap())
                        .toList();
    }

    private void pong(NodeId nodeId, HeldEnd ended) {
        fx.live.put(nodeId, ofEpisode(nodeId.id() + "@1000", -1, "", T0 - 500, ended));
        fx.records.onPong(nodeId);
    }

    /// The number of entries in the unit's map (its record component, read reflectively: the map is not an API).
    private int recorded() throws ReflectiveOperationException {
        var accessor = fx.records.getClass().getDeclaredMethod("drainers");

        accessor.setAccessible(true);

        return ((Map<?, ?>) accessor.invoke(fx.records)).size();
    }
}
