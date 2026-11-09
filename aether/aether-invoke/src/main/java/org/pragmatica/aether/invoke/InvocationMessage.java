// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;


@Codec
@CodecFor(MethodName.class)
public sealed interface InvocationMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.INVOKE;
    }

    /// `caller` (#2157) is the identity of the slice call's caller, as the edge validated it: empty for an
    /// unauthenticated caller and for every call that is not made on a caller's behalf. The receiving node hands it to
    /// the target method as its explicit `SecurityContext`/`Principal` parameter; it is identity, never authorization:
    /// no route security on the receiver reads it.
    record InvokeRequest(NodeId sender,
                         String correlationId,
                         String requestId,
                         Artifact targetSlice,
                         MethodName method,
                         byte[] payload,
                         boolean expectResponse,
                         int depth,
                         int hops,
                         boolean sampled,
                         Option<SecurityContext> caller) implements InvocationMessage {
        public static InvokeRequest invokeRequest(NodeId sender,
                                                  String correlationId,
                                                  String requestId,
                                                  Artifact targetSlice,
                                                  MethodName method,
                                                  byte[] payload,
                                                  boolean expectResponse,
                                                  int depth,
                                                  int hops,
                                                  boolean sampled,
                                                  Option<SecurityContext> caller) {
            return new InvokeRequest(sender,
                                     correlationId,
                                     requestId,
                                     targetSlice,
                                     method,
                                     payload,
                                     expectResponse,
                                     depth,
                                     hops,
                                     sampled,
                                     caller);
        }

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
                                     sampled,
                                     Option.none());
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
                                     false,
                                     Option.none());
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
