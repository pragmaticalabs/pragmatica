// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.function.Supplier;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Whether this node's copy of `(streamName, partition)` is quarantined (#1505 F2). A partition is quarantined
/// when it was found to hold a DIVERGENT entry, and the result is the lowest such offset. {@link PartitionBackfill}
/// refuses every self-promotion of a quarantined partition and demotes a CAUGHT_UP one. Production is
/// `StreamPartitionManager.quarantineView()`, backed by the manager that records the divergence inside the ordered
/// append section.
public interface QuarantineView {
    Option<Long> quarantinedAt(String streamName, int partition);
    /// Run `promotion` — the self-promotion and its completion ack — unless the partition is quarantined, ATOMICALLY
    /// with respect to recording a divergence (#1505 R3): the divergence is recorded either before the check, which
    /// then refuses, or after `promotion` has returned. [Option#none] when quarantined; `promotion` then never runs.
    <T> Option<T> unlessQuarantined(String streamName, int partition, Supplier<T> promotion);

    /// Repair the quarantined partition by cutting its tail back to the last offset it shares with its sender (#1730
    /// phase 2, KIP-101) and lifting the quarantine: the kept offset, or [Option#none] when nothing was quarantined. A
    /// failure leaves the partition quarantined. The caller must know the sender is the committed owner and that this
    /// node is not the owner. Views without a partition manager behind them repair nothing.
    default Result<Option<Long>> repair(String streamName, int partition, RepairAuthority authority) {
        return Result.success(Option.none());
    }

    /// Whether this copy may be cut back (#1730 phase 2, KIP-101): evaluated by the manager INSIDE the cut's ordered section,
    /// with the epoch of the records about to be removed (none for a copy that keeps no history), so a committed owner that
    /// changed since the caller decided cannot authorise a cut it no longer may.
    @FunctionalInterface
    interface RepairAuthority {
        boolean holds(Option<Epoch> divergentEpoch);
    }

    /// The repair of this copy is over (it is CAUGHT_UP again): report what the repair discarded, once, however many window
    /// steps it took. Views without a partition manager report nothing.
    @Contract
    default void repairSettled(String streamName, int partition) {}

    /// This copy has been compared with the committed owner of `epoch` (#1730 phase 2): from then on, and while the committed
    /// epoch is still `epoch`, it may serve and acknowledge what it holds at and above that epoch's start. Views without a
    /// partition manager behind them ignore it.
    @Contract
    default void verifiedForEpoch(String streamName, int partition, Epoch epoch) {}

    /// The committed owner epoch a backfill is about to compare this copy against, or [Epoch#ZERO] when there is no committed
    /// ownership record (unfenced).
    default Epoch committedEpoch(String streamName, int partition) {
        return Epoch.ZERO;
    }

    /// Whether this copy may serve and acknowledge what it holds at and above the committed epoch's start: false for a demoted
    /// owner, or a replica whose committed epoch advanced past the one it was last compared under, while it holds records at or
    /// above that start. True when there is nothing to doubt, and for views without a partition manager.
    default boolean verifiedForCurrentEpoch(String streamName, int partition) {
        return true;
    }

    /// The divergence this copy could not repair was found by comparing owner-epoch provenance: raise the durable
    /// `MARKED_DIVERGED` flag for it (once per process). A divergence that a repair resolves raises nothing. Views without a
    /// partition manager behind them flag nothing.
    @Contract
    default void flagUnrepaired(String streamName, int partition) {}

    /// This copy has been compared with its sender through `offset` (#1730 phase 2): a replica that restarted with a
    /// recovered tail shows nothing of it to readers until it is. Views without a partition manager behind them ignore
    /// it.
    @Contract
    default void verified(String streamName, int partition, long offset) {}

    /// For the legacy and test factories, which have no partition manager behind them: nothing is ever quarantined.
    QuarantineView NONE = new NeverQuarantined();

    final class NeverQuarantined implements QuarantineView {
        private NeverQuarantined() {}

        @Override
        public Option<Long> quarantinedAt(String streamName, int partition) {
            return Option.none();
        }

        @Override
        public <T> Option<T> unlessQuarantined(String streamName, int partition, Supplier<T> promotion) {
            return Option.some(promotion.get());
        }
    }
}
