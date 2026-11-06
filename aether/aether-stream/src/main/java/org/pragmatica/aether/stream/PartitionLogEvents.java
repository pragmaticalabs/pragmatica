// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.lang.Contract;


/// What a partition's log tells an index built over its records (#2590 P1, the control log's latest-per-key index):
/// how far the records are COMMITTED, and that a suffix was cut. Late-bound on [StreamPartitionManager]; the default
/// tells nobody. Both calls only record the fact and return: [#truncated] runs under the ring's append lock.
public interface PartitionLogEvents {
    /// Every record of `(streamName, partition)` at or below `through` is committed as far as this node knows: on the
    /// owner its visible position (the WAL fsync and the confirmation barrier both cover it), on a replica the position
    /// the committed owner announced (`CommitAdvance`, #2087). Never a bare append and never a replica's own durability.
    @Contract
    void committed(String streamName, int partition, long through);

    /// The ring of `(streamName, partition)` dropped every record above `keepThrough` (a #1730 suffix truncation); the
    /// next append reuses those offsets.
    @Contract
    void truncated(String streamName, int partition, long keepThrough);

    PartitionLogEvents NONE = new PartitionLogEvents() {
        @Contract
        @Override
        public void committed(String streamName, int partition, long through) {}

        @Contract
        @Override
        public void truncated(String streamName, int partition, long keepThrough) {}
    };
}
