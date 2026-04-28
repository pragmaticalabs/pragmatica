// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;


@Codec@CodecFor(MethodName.class) public sealed interface InvocationMessage extends ProtocolMessage {
    record InvokeRequest(NodeId sender,
                         String correlationId,
                         String requestId,
                         Artifact targetSlice,
                         MethodName method,
                         byte[] payload,
                         boolean expectResponse,
                         int depth,
                         int hops,
                         boolean sampled) implements InvocationMessage {
        public static InvokeRequest invokeRequest(NodeId sender,
                                                  String correlationId,
                                                  String requestId,
                                                  Artifact targetSlice,
                                                  MethodName method,
                                                  byte[] payload,
                                                  boolean expectResponse,
                                                  int depth,
                                                  int hops,
                                                  boolean sampled) {
            return new InvokeRequest(sender,
                                     correlationId,
                                     requestId,
                                     targetSlice,
                                     method,
                                     payload,
                                     expectResponse,
                                     depth,
                                     hops,
                                     sampled);
        }

        public static InvokeRequest invokeRequest(NodeId sender,
                                                  String correlationId,
                                                  String requestId,
                                                  Artifact targetSlice,
                                                  MethodName method,
                                                  byte[] payload,
                                                  boolean expectResponse) {
            return new InvokeRequest(sender,
                                     correlationId,
                                     requestId,
                                     targetSlice,
                                     method,
                                     payload,
                                     expectResponse,
                                     0,
                                     0,
                                     false);
        }
    }

    record InvokeResponse(NodeId sender, String correlationId, String requestId, boolean success, byte[] payload) implements InvocationMessage {
        public static InvokeResponse invokeResponse(NodeId sender,
                                                    String correlationId,
                                                    String requestId,
                                                    boolean success,
                                                    byte[] payload) {
            return new InvokeResponse(sender, correlationId, requestId, success, payload);
        }
    }
}
