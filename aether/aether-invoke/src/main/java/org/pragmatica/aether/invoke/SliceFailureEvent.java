// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;

import java.util.List;


public sealed interface SliceFailureEvent extends Message.Local {
    record AllInstancesFailed(String requestId,
                              Artifact artifact,
                              MethodName method,
                              Option<Cause> lastError,
                              List<NodeId> attemptedNodes,
                              long timestamp) implements SliceFailureEvent {
        public static AllInstancesFailed allInstancesFailed(String requestId,
                                                            Artifact artifact,
                                                            MethodName method,
                                                            Option<Cause> lastError,
                                                            List<NodeId> attemptedNodes) {
            return new AllInstancesFailed(requestId,
                                          artifact,
                                          method,
                                          lastError,
                                          attemptedNodes,
                                          System.currentTimeMillis());
        }
    }
}
