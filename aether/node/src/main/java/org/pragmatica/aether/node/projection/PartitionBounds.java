// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// What a rebuild will replay for one partition (#1333): the earliest offset still retained and the
/// last VISIBLE offset. Answered from the ring this node holds when it holds one — as owner or replica,
/// the same preference the consumer's routed read takes — because those are the offsets the group's
/// consumer will actually be handed.
///
/// A node that holds no ring for the partition cannot answer from local state; what it does then is the
/// open point of CTO ruling 4 (a forwarded query on the reader path). Until ruled, [#localOnly] REFUSES
/// with [Bounds.NotHeldHere] naming the partition, rather than guessing — a refused capture touches
/// nothing (`Projection.rebuild` captures before it resets).
@FunctionalInterface
public interface PartitionBounds {
    /// `earliestRetained` through `visibleHead` inclusive; [Option#none] for a partition that holds no
    /// visible event (nothing to replay — LIVE from the start).
    record Bounds(long earliestRetained, long visibleHead) {}

    Promise<Option<Bounds>> bounds(String streamName, int partition);

    sealed interface BoundsError extends Cause {
        record NotHeldHere(String streamName, int partition) implements BoundsError {
            @Override
            public String message() {
                return "Partition " + partition
                     + " of " + streamName
                     + " is not materialised on this node, so its replay bounds cannot be captured here;"
                     + " run the rebuild on a node that holds the partition (its owner or a replica — see"
                     + " ownerNode on the groups route)";
            }
        }
    }

    static PartitionBounds localOnly(StreamPartitionManager partitions) {
        return (streamName, partition) -> partitions.partitionBuffer(streamName, partition)
                                                    .map(ring -> Promise.success(visible(ring.tailOffset(),
                                                                                         ring.visibleOffset())))
                                                    .or(() -> new BoundsError.NotHeldHere(streamName, partition).promise());
    }

    private static Option<Bounds> visible(long earliestRetained, long visibleHead) {
        return visibleHead < 0 || visibleHead < earliestRetained
               ? Option.none()
               : Option.some(new Bounds(earliestRetained, visibleHead));
    }
}
