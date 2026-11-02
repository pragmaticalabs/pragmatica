// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;


@FunctionalInterface
public interface StreamForwardTransport {
    @Contract
    void send(NodeId target, StreamForwardMessage message);

    /// As [#send(NodeId, StreamForwardMessage)] for a request whose caller stops waiting after `callerWait` (#1996): a
    /// transport with an offline buffer drops the frame at the flush once that wait has passed. Default ignores it.
    @Contract
    default void send(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
        send(target, message);
    }

    /// As [#send(NodeId, StreamForwardMessage, TimeSpan)], reporting the local transport's outcome (#2574): a forward read
    /// whose frame was not sent ([WriteOutcome] other than `Sent`) fails at once instead of waiting out its read timeout for
    /// an answer that cannot come. The default sends and reports [WriteOutcome.Sent], the conservative answer of a transport
    /// that does not track its writes: it never claims a frame was not sent.
    default Promise<WriteOutcome> sendOutcome(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
        send(target, message, callerWait);

        return Promise.success(new WriteOutcome.Sent(target));
    }

    StreamForwardTransport NOOP = (_, _) -> {};
}
