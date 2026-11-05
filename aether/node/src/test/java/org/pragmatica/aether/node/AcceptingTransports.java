// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.function.BiConsumer;

import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.lang.Promise;


/// Test transports that hand every message to a sink and report it accepted ([WriteOutcome.Sent]): what a sink-only
/// transport answered before `send` reported an outcome (#2583 P1).
public sealed interface AcceptingTransports {
    static DHTNetwork acceptingDht(BiConsumer<NodeId, ProtocolMessage> sink) {
        return (target, message) -> accept(sink, target, message);
    }

    static ReplicationTransport acceptingReplication(BiConsumer<NodeId, ReplicationMessage> sink) {
        return (target, message) -> accept(sink, target, message);
    }

    private static <M> Promise<WriteOutcome> accept(BiConsumer<NodeId, M> sink, NodeId target, M message) {
        sink.accept(target, message);

        return Promise.success(new WriteOutcome.Sent(target));
    }

    record unused() implements AcceptingTransports {}
}
