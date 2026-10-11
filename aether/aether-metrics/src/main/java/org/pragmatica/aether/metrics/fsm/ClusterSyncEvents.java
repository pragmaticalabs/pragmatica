// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;


public interface ClusterSyncEvents extends ClusterFsmEvent {
    record PingTick(Epoch currentEpoch) implements ClusterSyncEvents {}

    record PongReceived(NodeId peer) implements ClusterSyncEvents {}
}
