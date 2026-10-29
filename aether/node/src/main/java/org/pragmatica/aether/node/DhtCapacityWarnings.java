// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.dht.storage.DhtCapacityListener;
import org.pragmatica.lang.Contract;
import org.pragmatica.utility.warning.OperatorWarningSink;

/// Where the DHT engine's capacity transitions of this node become operator warnings (#2258, spec 3.2 item 8): the engine
/// reports to a [DhtCapacityListener] and does not depend on the warning types, so the node binds this listener to its sink.
/// The sink is bound after the engine exists, because the node builds the engine before its event layer.
public final class DhtCapacityWarnings implements DhtCapacityListener {
    private final String nodeId;
    private final AtomicReference<OperatorWarningSink> sink = new AtomicReference<>(OperatorWarningSink.logOnly());

    private DhtCapacityWarnings(String nodeId) {
        this.nodeId = nodeId;
    }

    public static DhtCapacityWarnings dhtCapacityWarnings(String nodeId) {
        return new DhtCapacityWarnings(nodeId);
    }

    /// Where the warnings go from now on.
    @Contract
    public void operatorWarnings(OperatorWarningSink sink) {
        this.sink.set(sink);
    }

    @Override
    public void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {}

    @Override
    public void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {}

    @Override
    public void full(String prefix, long usedBytes, long maxBytes, long refusedBytes) {}

    @Override
    public void fullCleared(String prefix, long usedBytes, long maxBytes) {}
}
