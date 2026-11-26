// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3658: the `backend` key binds through the real TOML -> [ProviderBasedConfigService] path for both
/// `[cache.*]` and `[idempotency.*]`, and a section without it still binds (absent -> none, the mode decides),
/// so every existing section keeps working.
class CacheBackendConfigBindingTest {
    @Test
    void cacheConfig_bindsTheBackendKey() {
        var config = configServiceFrom("""
                [cache.calls]
                cache_name = "calls"
                strategy = "CACHE_ASIDE"
                ttl_seconds = 60
                max_entries = 100
                mode = "DISTRIBUTED"
                backend = "dht"
                """).config("cache.calls", CacheConfig.class)
                    .unwrap();

        assertThat(config.backend()).isEqualTo(some("dht"));
    }

    @Test
    void cacheConfig_withoutBackendKey_bindsNone() {
        var config = configServiceFrom("""
                [cache.calls]
                cache_name = "calls"
                strategy = "CACHE_ASIDE"
                ttl_seconds = 60
                max_entries = 100
                mode = "LOCAL"
                """).config("cache.calls", CacheConfig.class)
                    .unwrap();

        assertThat(config.backend()).isEqualTo(none());
    }

    @Test
    void idempotencyConfig_bindsTheBackendKey_andBindsNoneWithoutIt() {
        var service = configServiceFrom("""
                [idempotency.named]
                store_name = "named"
                retention_seconds = 60
                max_entries = 100
                mode = "LOCAL"
                backend = "memory"

                [idempotency.implied]
                store_name = "implied"
                retention_seconds = 60
                max_entries = 100
                mode = "LOCAL"
                """);

        assertThat(service.config("idempotency.named", IdempotencyConfig.class)
                          .unwrap()
                          .backend()).isEqualTo(some("memory"));
        assertThat(service.config("idempotency.implied", IdempotencyConfig.class)
                          .unwrap()
                          .backend()).isEqualTo(none());
    }

    private static ProviderBasedConfigService configServiceFrom(String toml) {
        var provider = ConfigurationProvider.builder()
                                            .withSource(TomlConfigSource.tomlConfigSource(toml)
                                                                        .unwrap())
                                            .build();

        return ProviderBasedConfigService.providerBasedConfigService(provider);
    }
}
