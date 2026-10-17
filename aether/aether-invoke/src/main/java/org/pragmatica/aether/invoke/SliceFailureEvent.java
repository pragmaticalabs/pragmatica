// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;


public sealed interface SliceFailureEvent extends Message.Local {
    /// `defectsPerHost` and `windowMs` are the evidence behind a leader-detected verdict (#1573): each hosting
    /// node's slice defects within the detection window. Empty and zero when the producer has none.
    record AllInstancesFailed(String requestId,
                              Artifact artifact,
                              MethodName method,
                              Option<Cause> lastError,
                              List<NodeId> attemptedNodes,
                              long timestamp,
                              Map<NodeId, Long> defectsPerHost,
                              long windowMs) implements SliceFailureEvent {
        public AllInstancesFailed {
            defectsPerHost = Map.copyOf(defectsPerHost);
        }

        public static AllInstancesFailed allInstancesFailed(String requestId,
                                                            Artifact artifact,
                                                            MethodName method,
                                                            Option<Cause> lastError,
                                                            List<NodeId> attemptedNodes) {
            return allInstancesFailed(requestId, artifact, method, lastError, attemptedNodes, Map.of(), 0L);
        }

        public static AllInstancesFailed allInstancesFailed(String requestId,
                                                            Artifact artifact,
                                                            MethodName method,
                                                            Option<Cause> lastError,
                                                            List<NodeId> attemptedNodes,
                                                            Map<NodeId, Long> defectsPerHost,
                                                            long windowMs) {
            return new AllInstancesFailed(requestId,
                                          artifact,
                                          method,
                                          lastError,
                                          attemptedNodes,
                                          System.currentTimeMillis(),
                                          defectsPerHost,
                                          windowMs);
        }
    }
}
