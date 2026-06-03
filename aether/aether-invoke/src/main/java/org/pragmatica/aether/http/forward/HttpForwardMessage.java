// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


@Codec public sealed interface HttpForwardMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.FORWARD;
    }

    @Codec enum Pipeline {
        APP,
        MANAGEMENT
    }

    record HttpForwardRequest(NodeId sender,
                              String correlationId,
                              String requestId,
                              byte[] requestData,
                              Pipeline pipeline) implements HttpForwardMessage{}

    record HttpForwardResponse(NodeId sender,
                               String correlationId,
                               String requestId,
                               boolean success,
                               byte[] payload,
                               Pipeline pipeline) implements HttpForwardMessage{}
}
