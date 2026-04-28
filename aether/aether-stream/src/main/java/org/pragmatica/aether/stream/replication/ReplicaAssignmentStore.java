// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


@FunctionalInterface public interface ReplicaAssignmentStore {
    @Contract void persistAssignment(String streamName, int partition, NodeId nodeId, boolean assigned);

    ReplicaAssignmentStore NOOP = (_, _, _, _) -> {};
}
