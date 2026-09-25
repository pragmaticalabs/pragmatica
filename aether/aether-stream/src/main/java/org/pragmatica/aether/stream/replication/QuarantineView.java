// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.function.Supplier;

import org.pragmatica.lang.Option;


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
