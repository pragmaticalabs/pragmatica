// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Pluggable transport for sending replication messages to replica nodes.
/// Implementations are provided by the network layer (node module).
@FunctionalInterface public interface ReplicationTransport {
    @Contract void send(NodeId target, ReplicationMessage message);

    ReplicationTransport NOOP = (_, _) -> {};
}
