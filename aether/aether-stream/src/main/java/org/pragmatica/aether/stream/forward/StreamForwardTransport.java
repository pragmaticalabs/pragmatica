// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;


/// Unicast transport for stream forward messages. Every send reports the local [WriteOutcome] of the underlying cluster
/// network: [WriteOutcome.Sent] means not refused locally, not that it was delivered. A forward read whose frame was not sent
/// ([WriteOutcome] other than `Sent`) fails at once instead of waiting out its read timeout for an answer that cannot
/// come (#2574).
@FunctionalInterface
public interface StreamForwardTransport {
    Promise<WriteOutcome> send(NodeId target, StreamForwardMessage message);

    /// As [#send(NodeId, StreamForwardMessage)] for a request whose caller stops waiting after `callerWait` (#1996): a
    /// transport with an offline buffer drops the frame at the flush once that wait has passed. Default ignores it.
    default Promise<WriteOutcome> send(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
        return send(target, message);
    }

    StreamForwardTransport NOOP = (target, _) -> Promise.success(new WriteOutcome.Sent(target));
}
