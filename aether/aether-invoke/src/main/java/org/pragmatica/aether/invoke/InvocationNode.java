// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.lang.Option;

import java.time.Instant;


public record InvocationNode(String requestId,
                             int depth,
                             Instant timestamp,
                             String nodeId,
                             String caller,
                             String callee,
                             long durationNs,
                             Outcome outcome,
                             Option<String> errorMessage,
                             boolean local,
                             int hops) {
    public enum Outcome {
        SUCCESS,
        FAILURE
    }

    public double durationMs() {
        return durationNs / 1_000_000.0;
    }

    public static InvocationNode invocationNode(String requestId,
                                                int depth,
                                                Instant timestamp,
                                                String nodeId,
                                                String caller,
                                                String callee,
                                                long durationNs,
                                                Outcome outcome,
                                                Option<String> errorMessage,
                                                boolean local,
                                                int hops) {
        return new InvocationNode(requestId,
                                  depth,
                                  timestamp,
                                  nodeId,
                                  caller,
                                  callee,
                                  durationNs,
                                  outcome,
                                  errorMessage,
                                  local,
                                  hops);
    }
}
