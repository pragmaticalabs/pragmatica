// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.interceptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryRegistryReleaseTest {
    @Test
    void cacheFactory_doubleClose_doesNotPruneSibling() {
        var factory = new CacheInterceptorFactory();
        var config = CacheConfig.cacheConfig("release-cache", CacheStrategy.CACHE_ASIDE)
                                .unwrap();
        var first = factory.provision(config).await().unwrap();
        var second = factory.provision(config).await().unwrap();

        assertThat(registrySize(factory, "cacheRegistry")).isEqualTo(1);

        factory.close(first).await();
        factory.close(first).await();
        assertThat(registrySize(factory, "cacheRegistry"))
            .as("a repeated close cannot steal a sibling interceptor's reference")
            .isEqualTo(1);

        factory.close(second).await();
        assertThat(registrySize(factory, "cacheRegistry"))
            .as("the cache registry drops the entry after its last release")
            .isZero();
    }

    @Test
    void idempotencyFactory_doubleClose_doesNotPruneSibling() {
        var factory = new IdempotencyInterceptorFactory();
        var config = IdempotencyConfig.idempotencyConfig("release-store")
                                      .unwrap();
        var first = factory.provision(config).await().unwrap();
        var second = factory.provision(config).await().unwrap();

        assertThat(registrySize(factory, "resourceRegistry")).isEqualTo(1);

        factory.close(first).await();
        factory.close(first).await();
        assertThat(registrySize(factory, "resourceRegistry"))
            .as("a repeated close cannot steal a sibling interceptor's reference")
            .isEqualTo(1);

        factory.close(second).await();
        assertThat(registrySize(factory, "resourceRegistry"))
            .as("the idempotency registry drops the entry after its last release")
            .isZero();
    }

    @Test
    void registry_releaseIsIdempotentPerIdentityHolder() {
        var registry = new NamedResourceRegistry<String>();
        var first = registry.acquire("same-name", () -> "resource", _ -> new Holder("same"));
        var second = registry.acquire("same-name", () -> "resource", _ -> new Holder("same"));

        assertThat(first).isEqualTo(second);
        assertThat(registry.release("same-name", first)).isTrue();
        assertThat(registry.release("same-name", first)).isFalse();
        assertThat(registry.size()).isEqualTo(1);

        assertThat(registry.release("same-name", second)).isTrue();
        assertThat(registry.size()).isZero();
    }

    private static int registrySize(Object owner, String fieldName) {
        try {
            Field field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object registry = field.get(owner);
            Method size = registry.getClass().getDeclaredMethod("size");
            size.setAccessible(true);
            return (int) size.invoke(registry);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect " + fieldName, exception);
        }
    }

    private record Holder(String value) {}
}
