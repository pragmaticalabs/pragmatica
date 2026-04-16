// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;


/// Ping from governor to followers requesting metrics.
///
/// @param sender      governor node ID
/// @param timestampMs when the ping was sent
@Codec public record WorkerMetricsPing(NodeId sender, long timestampMs) implements Message.Wired {
    public static WorkerMetricsPing workerMetricsPing(NodeId sender, long timestampMs) {
        return new WorkerMetricsPing(sender, timestampMs);
    }

    public static WorkerMetricsPing workerMetricsPing(NodeId sender) {
        return new WorkerMetricsPing(sender, System.currentTimeMillis());
    }
}
