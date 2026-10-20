// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.prefetch;

import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimProtocol.AppGossipStats;

/// What the node's prefetch gossip has done, beyond what each prefetch manager counts: SWIM's application-gossip
/// counters (datagrams sent, accepted, dropped -- a payload over the datagram budget is one of the drops) and the
/// channel's own (hints for an instance this node does not have, bytes that did not decode, hint lists trimmed to fit).
/// Node-wide: every instance that prefetches shares one channel.
public record PrefetchGossipStats(AppGossipStats swim, StoragePrefetchChannel.ChannelStats channel) {
    public static PrefetchGossipStats prefetchGossipStats(AppGossipStats swim, StoragePrefetchChannel.ChannelStats channel) {
        return new PrefetchGossipStats(swim, channel);
    }

    /// The node's report: SWIM's counters (none until SWIM runs) beside the channel's own, read now.
    public static Option<PrefetchGossipStats> prefetchGossipStats(Option<AppGossipStats> swim, StoragePrefetchChannel channel) {
        return swim.map(counters -> prefetchGossipStats(counters, channel.stats()));
    }
}
