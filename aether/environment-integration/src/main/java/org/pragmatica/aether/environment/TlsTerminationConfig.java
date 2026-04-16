// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// TLS termination configuration for load balancers.
public record TlsTerminationConfig(String certificateId, Option<String> privateKeyPath, boolean redirectHttp) {
    public static Result<TlsTerminationConfig> tlsTerminationConfig(String certificateId) {
        return success(new TlsTerminationConfig(certificateId, Option.empty(), true));
    }
}
