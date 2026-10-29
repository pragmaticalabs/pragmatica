// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.dht.storage.DhtCapacityListener;
import org.pragmatica.lang.Contract;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Where the DHT engine's capacity transitions of this node become operator warnings (#2258, spec 3.2 item 8): the engine
/// reports to a [DhtCapacityListener] and does not depend on the warning types, so the node binds this listener to its sink.
/// The sink is bound after the engine exists, because the node builds the engine before its event layer.
public final class DhtCapacityWarnings implements DhtCapacityListener {
    private static final Logger log = LoggerFactory.getLogger(DhtCapacityWarnings.class);
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
    public void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
        raise(OperatorWarningCode.STREAM_SEGMENT_DHT_NEAR_FULL,
              prefix,
              "DHT prefix {} holds {} live and {} tombstone bytes on this node, at least 85% of its cap of {} bytes "
              + "([streaming] dht_segment_max_bytes): client puts are refused above the cap, and tombstones count here as heap",
              prefix,
              liveBytes,
              tombstoneBytes,
              maxBytes);
    }

    @Override
    public void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
        raise(OperatorWarningCode.STREAM_SEGMENT_DHT_NEAR_FULL_CLEARED,
              prefix,
              "DHT prefix {} holds {} live and {} tombstone bytes on this node, below 80% of its cap of {} bytes",
              prefix,
              liveBytes,
              tombstoneBytes,
              maxBytes);
    }

    @Override
    public void full(String prefix, long usedBytes, long maxBytes, long refusedBytes) {
        raise(OperatorWarningCode.STREAM_SEGMENT_DHT_FULL,
              prefix,
              "DHT prefix {} refused a client put of {} bytes: this node holds {} live bytes against a cap of {} bytes "
              + "([streaming] dht_segment_max_bytes); seals that need this replica fail until retention frees space",
              prefix,
              refusedBytes,
              usedBytes,
              maxBytes);
    }

    @Override
    public void fullCleared(String prefix, long usedBytes, long maxBytes) {
        raise(OperatorWarningCode.STREAM_SEGMENT_DHT_FULL_CLEARED,
              prefix,
              "DHT prefix {} holds {} live bytes on this node, below 85% of its cap of {} bytes: client puts are accepted again",
              prefix,
              usedBytes,
              maxBytes);
    }

    private void raise(OperatorWarningCode code, String prefix, String template, Object... args) {
        OperatorWarnings.raise(log, sink.get(), code, "node " + nodeId + " dht prefix " + prefix, template, args);
    }
}
