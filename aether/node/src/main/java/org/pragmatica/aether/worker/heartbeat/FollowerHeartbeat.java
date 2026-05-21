// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.heartbeat;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;


@Codec
public record FollowerHeartbeat(NodeId nodeId, long lastDecisionSequence, long timestampMs) implements Message.Wired {
    public static FollowerHeartbeat followerHeartbeat(NodeId nodeId, long lastDecisionSequence, long timestampMs) {
        return new FollowerHeartbeat(nodeId, lastDecisionSequence, timestampMs);
    }

    public static FollowerHeartbeat followerHeartbeat(NodeId nodeId, long lastDecisionSequence) {
        return new FollowerHeartbeat(nodeId, lastDecisionSequence, System.currentTimeMillis());
    }
}
