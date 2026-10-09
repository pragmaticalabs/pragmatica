// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
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

    StreamForwardTransport NOOP = (_, _) -> {};
}
