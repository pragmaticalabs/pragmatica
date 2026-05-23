// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
//
// TODO(rc2-#189): real implementation writes a DRAINING NodeLifecycleValue atom via consensus,
// awaits acknowledgement from peers (so they stop sending new traffic to the draining node), and
// marks the drain complete in the KV-Store. Theme C (rc1) installs only the structural handles
// (interface, no-op stub, NodeDeploymentState.Leaving placeholder, AppHttpState.Quiesced) so the
// rc2 implementation drops in without touching the FSM topology again.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


public interface DrainCoordinator {
    Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason);
    Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout);

    @Contract
    void markDrainComplete(NodeId nodeId);

    enum DrainReason {
        SCALE_DOWN,
        ROLLING_UPDATE,
        OPERATOR_DRAIN,
        FAULTY_EVICTION
    }
}
