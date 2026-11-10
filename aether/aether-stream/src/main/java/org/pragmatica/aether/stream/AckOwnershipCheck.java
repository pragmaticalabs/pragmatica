// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.Executor;

import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The acknowledgement-time ownership check (#2981 direction B). A `confirmation_factor` >= 2 append whose replicas have
/// confirmed it is acknowledged to its writer only if the COMMITTED ownership record, read after a no-op consensus round
/// ([LinearizableBarrier]), still carries the ownership the append was admitted under (`admittedUnder`: owner, owner epoch,
/// ownership term and transfer stamp, the record without its ISR, as [OwnerActivation#sameOwnership] compares) and names
/// this node. Otherwise the append fails with [StreamError.OwnershipMovedBeforeAck], an unknown outcome to the writer.
///
/// Why it closes N2: a deposed owner that has not yet applied its deposal admits from its stale view and gathers acks from
/// its OLD in-sync set, while the new owner may already have activated without the append (its catch-up probe is a snapshot).
/// The new owner activates only on a committed record naming it. The round starts after the acks resolved, and Rabia
/// applies in one total order, so the record read after it shows every ownership change committed before the acks
/// completed, and therefore the move. `[mechanism: TLA+ Streams.tla FenceMech=B, AckedNeverLost, report §6; this
/// check, StreamPartitionManager#awaitReplication]`
///
/// The only call site is [StreamPartitionManager#awaitReplication], which every acknowledging writer uses (publish,
/// forwarded publish, control runs, entity logs). [#UNCHECKED] (no consensus behind the manager: Forge, unit tests)
/// acknowledges as before.
@FunctionalInterface
public interface AckOwnershipCheck {
    /// Resolves when the acknowledgement of an append of `(stream, partition)` admitted under `admittedUnder` may complete;
    /// fails with [StreamError.OwnershipMovedBeforeAck] when the committed ownership moved, or with the round's own failure
    /// (an unknown outcome as well).
    Promise<Unit> confirm(String stream, int partition, Option<StreamPartitionOwnershipValue> admittedUnder);
    /// No check: the default of a manager with no consensus behind it.
    AckOwnershipCheck UNCHECKED = (_, _, _) -> Promise.unitPromise();

    /// The production check: one barrier round per resolved batch of acknowledgements per partition, see
    /// [BatchedAckOwnershipCheck]. `roundStarter` runs the start of each round, so that every waiter one ack resolves joins
    /// the same round.
    static AckOwnershipCheck batched(NodeId self,
                                     OwnerActivation.OwnershipRecordSource records,
                                     LinearizableBarrier barrier,
                                     Executor roundStarter) {
        return BatchedAckOwnershipCheck.batchedAckOwnershipCheck(self, records, barrier, roundStarter);
    }

    /// The production round starter: a virtual-thread hop, so the round starts after the acking thread has released
    /// every waiter it resolves.
    Executor VIRTUAL_HOP = AckOwnershipCheck::startVirtual;

    @Contract
    private static void startVirtual(Runnable round) {
        Thread.ofVirtual().name("ack-ownership-round").start(round);
    }
}
