// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Verify.ensure;


/// `mode` and `backend` select the dedup store exactly as [CacheConfig]'s do (#3658): absent, `backend` follows the
/// mode (`memory` for `LOCAL`, `dht` otherwise). `[guess: key name and values, #3658]`.
public record IdempotencyConfig(String storeName,
                                int retentionSeconds,
                                int maxEntries,
                                CacheMode mode,
                                Option<String> backend) {
    // Deliberately named DEFAULTS, not DEFAULT: storeName is identity-bearing. Exposing this as a
    // binder-visible DEFAULT would let multiple TOML-configured call sites silently collapse onto
    // the same idempotency store ("default") when storeName is omitted (#278). Callers MUST supply
    // an explicit storeName; a missing [idempotency.*] section fails loud via ConfigError.sectionNotFound.
    private static final IdempotencyConfig DEFAULTS = new IdempotencyConfig("default",
                                                                                3600,
                                                                                10_000,
                                                                                CacheMode.LOCAL,
                                                                                none());

    public static IdempotencyConfig idempotencyConfig() {
        return DEFAULTS;
    }

    public static Result<IdempotencyConfig> idempotencyConfig(String storeName,
                                                              int retentionSeconds,
                                                              int maxEntries,
                                                              CacheMode mode) {
        return idempotencyConfig(storeName, retentionSeconds, maxEntries, mode, none());
    }

    public static Result<IdempotencyConfig> idempotencyConfig(String storeName,
                                                              int retentionSeconds,
                                                              int maxEntries,
                                                              CacheMode mode,
                                                              Option<String> backend) {
        return Result.all(ensure(storeName, Verify.Is::present),
                          ensure(retentionSeconds, Verify.Is::positive),
                          ensure(maxEntries, Verify.Is::positive),
                          ensure(mode, Verify.Is::notNull),
                          ensure(backend, Verify.Is::notNull)).map(IdempotencyConfig::new);
    }

    public static Result<IdempotencyConfig> idempotencyConfig(String storeName) {
        return idempotencyConfig(storeName, 3600, 10_000, CacheMode.LOCAL);
    }

    public static Result<IdempotencyConfig> idempotencyConfig(String storeName, CacheMode mode) {
        return idempotencyConfig(storeName, 3600, 10_000, mode);
    }
}
