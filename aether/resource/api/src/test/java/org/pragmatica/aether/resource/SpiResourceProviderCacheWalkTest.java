// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConfig;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.acquire;
import static org.pragmatica.aether.resource.SharedPoolFixtures.providerOf;
import static org.pragmatica.aether.resource.SharedPoolFixtures.release;

/// The per-slice cache walk of `releaseAll` follows the table, not the map's count: a slice's resource is released even
/// when `isEmpty()` reads empty while the entry is present.
class SpiResourceProviderCacheWalkTest {
    private static final class LyingMap extends ConcurrentHashMap<Object, Object> {
        private final AtomicBoolean lied = new AtomicBoolean();

        @Override
        public boolean isEmpty() {
            return lied.compareAndSet(false, true) || super.isEmpty();
        }
    }

    @Test
    void releaseAll_whenIsEmptyLiesOnce_stillReleasesTheSlicesResource() throws Exception {
        var factory = new FakeShareableFactory();
        var provider = providerOf(Map.of("database", FakeConfig.perSlice("pg://h/db")), factory);
        var field = SpiResourceProvider.class.getDeclaredField("promiseCache");
        var raw = acquire(provider, "database", "slice-a").underlying();

        field.setAccessible(true);
        var held = new LyingMap();

        held.putAll((Map<?, ?>) field.get(provider));
        field.set(provider, held);
        release(provider, "slice-a");

        assertThat(factory.closed).containsExactly(raw);
    }
}
