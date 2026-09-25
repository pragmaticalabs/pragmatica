// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Option;


/// Whether this node's copy of `(streamName, partition)` is quarantined (#1505 F2). A partition is quarantined
/// when it was found to hold a DIVERGENT entry, and the result is the lowest such offset. {@link PartitionBackfill}
/// refuses every self-promotion of a quarantined partition and demotes a CAUGHT_UP one. Production binds
/// `StreamPartitionManager::quarantinedAt`, the manager that records the divergence inside the ordered append
/// section.
@FunctionalInterface
public interface QuarantineView {
    Option<Long> quarantinedAt(String streamName, int partition);
    /// For the legacy and test factories, which have no partition manager behind them: nothing is ever quarantined.
    QuarantineView NONE = (_, _) -> Option.none();
}
