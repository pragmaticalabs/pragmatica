// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


public record SlowInvocation(MethodName methodName,
                             long timestampNs,
                             long durationNs,
                             int requestBytes,
                             int responseBytes,
                             boolean success,
                             Option<String> errorType) {
    public static SlowInvocation slowInvocation(MethodName method,
                                                long timestampNs,
                                                long durationNs,
                                                int requestBytes,
                                                int responseBytes) {
        return new SlowInvocation(method, timestampNs, durationNs, requestBytes, responseBytes, true, Option.empty());
    }

    public static SlowInvocation slowInvocation(MethodName method,
                                                long timestampNs,
                                                long durationNs,
                                                int requestBytes,
                                                String errorType) {
        return new SlowInvocation(method, timestampNs, durationNs, requestBytes, 0, false, option(errorType));
    }

    public double durationMs() {
        return durationNs / 1_000_000.0;
    }
}
