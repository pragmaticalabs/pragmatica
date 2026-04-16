// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.serialization.Codec;


/// Messages for HTTP request forwarding between nodes.
///
///
/// When a node receives an HTTP request for a slice it doesn't host,
/// it forwards the request to a node that does host the slice.
///
///
/// Flow:
/// <ol>
///   - Node A receives HTTP request for slice S
///   - Node A doesn't host S, finds Node B that does
///   - Node A sends HttpForwardRequest to Node B
///   - Node B processes request, sends HttpForwardResponse back
///   - Node A returns response to original HTTP client
/// </ol>
@Codec public sealed interface HttpForwardMessage extends ProtocolMessage {
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
