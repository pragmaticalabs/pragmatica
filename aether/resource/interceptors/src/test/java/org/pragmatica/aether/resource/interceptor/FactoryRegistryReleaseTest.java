// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.interceptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryRegistryReleaseTest {
    @Test
    void cacheFactory_releaseLastReference_prunesRegistryEntry() {
        var factory = new CacheInterceptorFactory();
        var config = CacheConfig.cacheConfig("release-cache", CacheStrategy.CACHE_ASIDE)
                                .fold(_ -> null, value -> value);
        var first = factory.provision(config).await().fold(_ -> null, value -> value);
        var second = factory.provision(config).await().fold(_ -> null, value -> value);

        assertThat(registrySize(factory, "cacheRegistry")).isEqualTo(1);

        factory.close(first).await();
        assertThat(registrySize(factory, "cacheRegistry"))
            .as("a shared cache remains while another interceptor holds it")
            .isEqualTo(1);

        factory.close(second).await();
        assertThat(registrySize(factory, "cacheRegistry"))
            .as("the cache registry drops the entry after its last release")
            .isZero();
    }

    @Test
    void idempotencyFactory_releaseLastReference_prunesStoreAndClaimRegistries() {
        var factory = new IdempotencyInterceptorFactory();
        var config = IdempotencyConfig.idempotencyConfig("release-store")
                                      .fold(_ -> null, value -> value);
        var first = factory.provision(config).await().fold(_ -> null, value -> value);
        var second = factory.provision(config).await().fold(_ -> null, value -> value);

        assertThat(registrySize(factory, "storeRegistry")).isEqualTo(1);
        assertThat(registrySize(factory, "claimRegistry")).isEqualTo(1);

        factory.close(first).await();
        assertThat(registrySize(factory, "storeRegistry")).isEqualTo(1);
        assertThat(registrySize(factory, "claimRegistry")).isEqualTo(1);

        factory.close(second).await();
        assertThat(registrySize(factory, "storeRegistry"))
            .as("the store registry drops the entry after its last release")
            .isZero();
        assertThat(registrySize(factory, "claimRegistry"))
            .as("the claim registry drops the entry after its last release")
            .isZero();
    }

    private static int registrySize(Object owner, String fieldName) {
        try {
            Field field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object registry = field.get(owner);
            if (registry instanceof Map<?, ?> map) {
                return map.size();
            }
            Method size = registry.getClass().getDeclaredMethod("size");
            size.setAccessible(true);
            return (int) size.invoke(registry);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect " + fieldName, exception);
        }
    }
}
