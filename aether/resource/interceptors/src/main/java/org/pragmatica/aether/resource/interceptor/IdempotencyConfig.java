// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;


public record IdempotencyConfig(String storeName, int retentionSeconds, int maxEntries, CacheMode mode) {
    private static final IdempotencyConfig DEFAULTS = new IdempotencyConfig("default",
                                                                            3600,
                                                                            10_000,
                                                                            CacheMode.LOCAL);

    public static IdempotencyConfig idempotencyConfig() {
        return DEFAULTS;
    }

    public static Result<IdempotencyConfig> idempotencyConfig(String storeName,
                                                              int retentionSeconds,
                                                              int maxEntries,
                                                              CacheMode mode) {
        return Result.all(ensure(storeName, Verify.Is::present),
                          ensure(retentionSeconds, Verify.Is::positive),
                          ensure(maxEntries, Verify.Is::positive),
                          ensure(mode, Verify.Is::notNull)).map(IdempotencyConfig::new);
    }

    public static Result<IdempotencyConfig> idempotencyConfig(String storeName) {
        return idempotencyConfig(storeName, 3600, 10_000, CacheMode.LOCAL);
    }

    public static Result<IdempotencyConfig> idempotencyConfig(String storeName, CacheMode mode) {
        return idempotencyConfig(storeName, 3600, 10_000, mode);
    }
}
