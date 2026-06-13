// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry.RegisteredByKind;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;


class StreamRegistryEntryTest {

    private static final ResourceAddress SYSTEM_EVENTS =
            resourceAddress("system:cluster-events:1.0.0").unwrap();
    private static final ResourceAddress APP_ORDERS =
            resourceAddress("com.example.app:orders:1.0.0").unwrap();

    @Test
    void frameworkFactorySetsRegisteredByAndInitialRef() {
        var entry = StreamRegistryEntry.framework(SYSTEM_EVENTS,
                                                   RetentionPolicy.retentionPolicy(),
                                                   Instant.EPOCH);

        assertThat(entry.address()).isEqualTo(SYSTEM_EVENTS);
        assertThat(entry.registeredBy()).isEqualTo(RegisteredByKind.FRAMEWORK);
        assertThat(entry.refCount()).isEqualTo(1);
    }

    @Test
    void blueprintFactorySetsRegisteredByAndInitialRef() {
        var entry = StreamRegistryEntry.blueprint(APP_ORDERS,
                                                   RetentionPolicy.retentionPolicy(),
                                                   Instant.EPOCH);

        assertThat(entry.registeredBy()).isEqualTo(RegisteredByKind.BLUEPRINT);
        assertThat(entry.refCount()).isEqualTo(1);
    }

    @Test
    void incrementRefReturnsNewEntryWithIncrementedCount() {
        var original = StreamRegistryEntry.framework(SYSTEM_EVENTS,
                                                      RetentionPolicy.retentionPolicy(),
                                                      Instant.EPOCH);

        var incremented = original.incrementRef();

        assertThat(incremented.refCount()).isEqualTo(2);
        assertThat(original.refCount()).isEqualTo(1);
    }

    @Test
    void decrementRefReturnsNewEntryWithDecrementedCount() {
        var original = StreamRegistryEntry.framework(SYSTEM_EVENTS,
                                                      RetentionPolicy.retentionPolicy(),
                                                      Instant.EPOCH)
                                           .incrementRef();

        var decremented = original.decrementRef();

        assertThat(decremented.refCount()).isEqualTo(1);
        assertThat(original.refCount()).isEqualTo(2);
    }

    @Test
    void withRefCountReplacesCount() {
        var entry = StreamRegistryEntry.blueprint(APP_ORDERS,
                                                   RetentionPolicy.retentionPolicy(),
                                                   Instant.EPOCH);

        assertThat(entry.withRefCount(42).refCount()).isEqualTo(42);
    }

    @Test
    void addressAndRetentionPropagate() {
        var retention = RetentionPolicy.retentionPolicy(500, 1024L, 60_000L);
        var entry = StreamRegistryEntry.blueprint(APP_ORDERS, retention, Instant.EPOCH);

        assertThat(entry.address()).isEqualTo(APP_ORDERS);
        assertThat(entry.retention()).isEqualTo(retention);
    }
}
