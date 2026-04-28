// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
//
// TODO(rc2-#189): replace with the real ConsensusDrainCoordinator that writes DRAINING atoms,
// awaits per-peer acks, and marks completion. The interface contract on `DrainCoordinator`
// already reflects the rc2 semantics; rc1 callers see immediate success so existing scale-down /
// terminate paths run unchanged.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


public record NoOpDrainCoordinator() implements DrainCoordinator {
    @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
        return Promise.unitPromise();
    }

    @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
        return Promise.unitPromise();
    }

    @Override@Contract public void markDrainComplete(NodeId nodeId) {}
}
