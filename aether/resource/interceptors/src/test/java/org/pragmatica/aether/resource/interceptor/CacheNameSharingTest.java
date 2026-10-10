// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// #697: `[cache.*]` sections sharing a `cache_name` share ONE backend on purpose (the banking example's
/// reader and invalidators), so a shared name is accepted — but only while the sections agree on what
/// shapes that backend. The issue's original ask, refusing every duplicate, would have refused that example.
class CacheNameSharingTest {
    private final CacheInterceptorFactory factory = new CacheInterceptorFactory();

    private static CacheConfig config(CacheStrategy strategy, int ttl, int max, CacheMode mode) {
        return CacheConfig.cacheConfig("account-balance", strategy, ttl, max, mode).fold(_ -> null, v -> v);
    }

    private static ProvisioningContext typed(TypeToken<?> key, TypeToken<?> value) {
        return ProvisioningContext.provisioningContext().withTypeToken(key).withTypeToken(value);
    }

    private static final TypeToken<String> KEY = new TypeToken<>() {};
    private static final TypeToken<Integer> BALANCE = new TypeToken<>() {};
    private static final TypeToken<Long> OTHER = new TypeToken<>() {};
    private static final TypeToken<Void> UNIT = new TypeToken<>() {};

    private Result<CacheMethodInterceptor> provision(CacheConfig config, ProvisioningContext context) {
        return factory.provision(config, context).await();
    }

    private static CacheConfig base(CacheStrategy strategy) {
        return config(strategy, 300, 10_000, CacheMode.LOCAL);
    }

    @Test
    void matchingSections_areAccepted_andShareOneBackend_evenWithDifferentStrategies() {
        var reader = provision(base(CacheStrategy.CACHE_ASIDE), typed(KEY, BALANCE)).fold(_ -> null, v -> v);
        var invalidator = provision(base(CacheStrategy.WRITE_AROUND), typed(KEY, UNIT)).fold(_ -> null, v -> v);

        assertThat(reader).isNotNull();
        assertThat(invalidator).isNotNull();
        assertThat(invalidator.cache()).isSameAs(reader.cache());
    }

    @Test
    void sectionsWithoutTypeTokens_areNotComparedOnType() {
        assertThat(provision(base(CacheStrategy.CACHE_ASIDE), typed(KEY, BALANCE)).isSuccess()).isTrue();
        assertThat(provision(base(CacheStrategy.CACHE_ASIDE), ProvisioningContext.provisioningContext()).isSuccess()).isTrue();
    }

    @Test
    void differentTtl_isRefused_namingTheNameAndTheSetting() {
        provision(base(CacheStrategy.CACHE_ASIDE), typed(KEY, BALANCE));

        assertRefused(provision(config(CacheStrategy.CACHE_ASIDE, 60, 10_000, CacheMode.LOCAL), typed(KEY, BALANCE)),
                      "ttl_seconds", "300", "60");
    }

    @Test
    void differentMaxEntries_isRefused() {
        provision(base(CacheStrategy.CACHE_ASIDE), typed(KEY, BALANCE));

        assertRefused(provision(config(CacheStrategy.CACHE_ASIDE, 300, 5, CacheMode.LOCAL), typed(KEY, BALANCE)),
                      "max_entries", "10000", "5");
    }

    @Test
    void differentKeyType_isRefused() {
        provision(base(CacheStrategy.CACHE_ASIDE), typed(KEY, BALANCE));

        assertRefused(provision(base(CacheStrategy.WRITE_AROUND), typed(OTHER, UNIT)), "key type", "String", "Long");
    }

    @Test
    void differentStoredValueType_isRefused_butAnInvalidatorsReturnTypeIsNot() {
        provision(base(CacheStrategy.CACHE_ASIDE), typed(KEY, BALANCE));

        assertRefused(provision(base(CacheStrategy.WRITE_THROUGH), typed(KEY, OTHER)), "cached value type", "Integer", "Long");
        assertThat(provision(base(CacheStrategy.WRITE_AROUND), typed(KEY, OTHER)).isSuccess()).isTrue();
    }

    @Test
    void differentMode_isRefused() {
        var shared = CacheShape.cacheShape(base(CacheStrategy.CACHE_ASIDE), ProvisioningContext.provisioningContext());
        var incoming = CacheShape.cacheShape(config(CacheStrategy.CACHE_ASIDE, 300, 10_000, CacheMode.DISTRIBUTED),
                                             ProvisioningContext.provisioningContext());

        var refusal = shared.conflictWith(incoming, "account-balance").fold(() -> null, c -> c);

        assertThat(refusal).isNotNull();
        assertThat(refusal.message()).contains("account-balance", "mode", "LOCAL", "DISTRIBUTED");
    }

    @Test
    void refusedSection_countsNoHold_soTheFirstReleasePrunesTheName() {
        var first = provision(base(CacheStrategy.CACHE_ASIDE), typed(KEY, BALANCE)).fold(_ -> null, v -> v);
        provision(config(CacheStrategy.CACHE_ASIDE, 60, 10_000, CacheMode.LOCAL), typed(KEY, BALANCE));

        factory.close(first).await();

        assertThat(factory.retains("account-balance")).isFalse();
    }

    private static void assertRefused(Result<CacheMethodInterceptor> result, String setting, String shared, String incoming) {
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("account-balance", setting, shared, incoming));
    }
}
