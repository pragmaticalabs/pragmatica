// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.function.BiConsumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;


/// Test [StreamForwardTransport] that hands every message to a sink and reports it accepted ([WriteOutcome.Sent]): what
/// a sink-only transport answered before `send` reported an outcome (#2583 P1).
public sealed interface AcceptingStreamForwardTransport {
    static StreamForwardTransport accepting(BiConsumer<NodeId, StreamForwardMessage> sink) {
        return (target, message) -> accept(sink, target, message);
    }

    private static Promise<WriteOutcome> accept(BiConsumer<NodeId, StreamForwardMessage> sink,
                                                NodeId target,
                                                StreamForwardMessage message) {
        sink.accept(target, message);

        return Promise.success(new WriteOutcome.Sent(target));
    }

    record unused() implements AcceptingStreamForwardTransport {}
}
