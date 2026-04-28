// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.consensus.NodeId;

import java.time.Instant;
import java.util.List;


public sealed interface NodeReconcilerState {
    record Inactive(String reason) implements NodeReconcilerState{}

    record Forming(Instant since) implements NodeReconcilerState{}

    record Converged() implements NodeReconcilerState{}

    record Reconciling(int targetSize,
                       int currentSize,
                       List<ProvisioningSlot> inFlight,
                       List<NodeId> terminating,
                       Instant startedAt) implements NodeReconcilerState{}

    record ProvisioningSlot(long spawnedAtMs, long deadlineMs){}
}
