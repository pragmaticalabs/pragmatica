// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The node a new owner's move fence must reach (#2981 step 3, design §2.3, model action `GateProbe`): `L`, the owner of the
/// newest STARTED epoch older than the record's own epoch, read from the record's `epochStarts`.
///
/// Only an owner whose lineage write landed can have admitted, acked or shown anything (activation follows that guarded
/// write), so owners of later epochs, which have no start, never served; owners of earlier epochs were fenced by `L`'s own
/// gate. `L` is taken from the record alone, never from the live placement: an owner that stopped hosting the slice is still
/// `L` and is still probed.
public sealed interface FenceTarget {
    /// No older epoch started, or its owner is this node: nobody else can hold an acked record this node has not seen.
    record NoFence() implements FenceTarget {
        public static NoFence noFence() {
            return new NoFence();
        }
    }

    /// `owner` started the newest older epoch: the fence probes it and races its answer against the deadline.
    record Probe(NodeId owner) implements FenceTarget {
        public static Probe probe(NodeId owner) {
            return new Probe(owner);
        }
    }

    /// The newest older start names no owner (recorded before the field existed): the fence probes nobody first and waits the
    /// full deadline, which is safe for any `L`.
    record Unknown() implements FenceTarget {
        public static Unknown unknown() {
            return new Unknown();
        }
    }

    /// The fence target of `record` as seen by `self`, the owner the record names.
    static FenceTarget fenceTarget(StreamPartitionOwnershipValue record, NodeId self) {
        return Option.from(record.epochStarts()
                                 .stream()
                                 .filter(start -> start.epoch()
                                                       .compareTo(record.ownerEpoch()) < 0)
                                 .reduce((_, later) -> later))
                     .map(start -> targetOf(start, self))
                     .or(NoFence::noFence);
    }

    private static FenceTarget targetOf(EpochStart start, NodeId self) {
        return start.owner()
                    .map(owner -> owner.equals(self)
                                  ? NoFence.noFence()
                                  : (FenceTarget) Probe.probe(owner))
                    .or(Unknown::unknown);
    }
}
