// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;


public interface ClusterSyncEvents extends ClusterFsmEvent {
    record PingTick(Epoch currentEpoch) implements ClusterSyncEvents {}

    record PongReceived(NodeId peer) implements ClusterSyncEvents {}
}
