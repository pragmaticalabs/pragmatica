// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// What a rebuild will replay for one partition (#1333): the earliest offset still retained and the
/// last VISIBLE offset — the offsets the group's consumer will actually be handed. Answered the way the
/// consumer's own read is routed (CTO ruling 4 (a), know `99b1a8d58`): from the ring this node holds
/// when it holds one (owner or replica), else forwarded to the resolved owner through the read-forward
/// path, whose response carries the bounds. A node that can reach neither refuses, and a refused capture
/// touches nothing (`Projection.rebuild` captures before it resets).
@FunctionalInterface
public interface PartitionBounds {
    /// `earliestRetained` through `visibleHead` inclusive; [Option#none] for a partition that holds no
    /// visible event (nothing to replay — LIVE from the start).
    record Bounds(long earliestRetained, long visibleHead) {}

    Promise<Option<Bounds>> bounds(String streamName, int partition);

    static PartitionBounds routed(StreamReadRouter router) {
        return (streamName, partition) -> router.bounds(streamName, partition)
                                                .map(PartitionBounds::visible);
    }

    private static Option<Bounds> visible(VisibleBounds bounds) {
        return bounds.isEmpty()
               ? Option.none()
               : Option.some(new Bounds(bounds.earliestRetained(), bounds.visibleHead()));
    }
}
