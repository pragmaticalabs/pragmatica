// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;


/// Unicast transport for replication messages. Every send reports the local [WriteOutcome] of the underlying cluster
/// network: [WriteOutcome.Sent] means not refused locally, not that it was delivered.
@FunctionalInterface
public interface ReplicationTransport {
    Promise<WriteOutcome> send(NodeId target, ReplicationMessage message);

    /// As [#send(NodeId, ReplicationMessage)] for a request whose caller stops waiting after `callerWait` (#1996): a
    /// transport with an offline buffer drops the frame at the flush once that wait has passed. Default ignores it.
    default Promise<WriteOutcome> send(NodeId target, ReplicationMessage message, TimeSpan callerWait) {
        return send(target, message);
    }

    ReplicationTransport NOOP = (target, _) -> Promise.success(new WriteOutcome.Sent(target));
}
