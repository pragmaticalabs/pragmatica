// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.prefetch;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.swim.SwimProtocol.AppGossipStats;

import static org.assertj.core.api.Assertions.assertThat;

/// The node's prefetch gossip report joins SWIM's counters with the channel's own, and exists only while SWIM runs.
class PrefetchGossipStatsTest {
    private final StoragePrefetchChannel channel = channelWithTwoMalformedArrivals();

    private static StoragePrefetchChannel channelWithTwoMalformedArrivals() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var channel = StoragePrefetchChannel.storagePrefetchChannel(Map.of(), codec, codec);

        channel.incoming(new NodeId("peer"), new byte[]{1, 2, 3});
        channel.incoming(new NodeId("peer"), new byte[]{4});

        return channel;
    }

    @Test
    void prefetchGossipStats_whileSwimRuns_carriesBothSetsOfCounters() {
        var report = PrefetchGossipStats.prefetchGossipStats(Option.some(AppGossipStats.appGossipStats(9, 8, 7)), channel).unwrap();

        assertThat(report.swim()).isEqualTo(AppGossipStats.appGossipStats(9, 8, 7));
        assertThat(report.channel().droppedMalformed()).as("the channel's own counters, read from the channel").isEqualTo(2);
    }

    @Test
    void prefetchGossipStats_beforeSwimRuns_isNone() {
        assertThat(PrefetchGossipStats.prefetchGossipStats(Option.none(), channel).isEmpty()).isTrue();
    }
}
