// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry.RegisteredByKind;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;


class SystemStreamBootstrapTest {

    private InMemoryStreamRegistry registry;

    @BeforeEach void setUp() {
        registry = new InMemoryStreamRegistry();
    }

    @Test
    void bootstrapRegistersClusterEventsStream() {
        var bootstrap = new SystemStreamBootstrap(registry,
                                                    RetentionPolicy.retentionPolicy(),
                                                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        var result = bootstrap.bootstrap();

        assertThat(result.isSuccess()).isTrue();
        var entries = result.unwrap();
        assertThat(entries).hasSize(SystemStreams.ALL.size());
        var entry = entries.getFirst();
        assertThat(entry.address()).isEqualTo(SystemStreams.CLUSTER_EVENTS);
        assertThat(entry.registeredBy()).isEqualTo(RegisteredByKind.FRAMEWORK);
        assertThat(entry.refCount()).isEqualTo(1);
        assertThat(entry.registeredAt()).isEqualTo(Instant.EPOCH);
    }

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    /// Retention is per stream: the capacity streams declare their own, `cluster-events` keeps the default.
    @Test
    void capacityStreamsAreRegisteredWithTheirOwnRetention_clusterEventsKeepsTheDefault() {
        var defaults = RetentionPolicy.retentionPolicy();

        new SystemStreamBootstrap(registry, defaults, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)).bootstrap();

        var minute = registry.lookup(SystemStreams.CAPACITY_MINUTE).unwrap();
        var hour = registry.lookup(SystemStreams.CAPACITY_HOUR).unwrap();

        assertThat(minute.retention().maxAgeMs()).isEqualTo(35 * DAY_MS);
        assertThat(hour.retention().maxAgeMs()).isEqualTo(1095 * DAY_MS);
        assertThat(minute.retention().maxCount()).isGreaterThan(defaults.maxCount());
        assertThat(hour.retention().maxCount()).isGreaterThan(defaults.maxCount());
        assertThat(registry.lookup(SystemStreams.CLUSTER_EVENTS).unwrap().retention()).isEqualTo(defaults);
        assertThat(minute.registeredBy()).isEqualTo(RegisteredByKind.FRAMEWORK);
        assertThat(hour.registeredBy()).isEqualTo(RegisteredByKind.FRAMEWORK);
    }

    @Test
    void bootstrapIsIdempotent() {
        var bootstrap = new SystemStreamBootstrap(registry);

        bootstrap.bootstrap();
        var second = bootstrap.bootstrap();

        assertThat(second.isSuccess()).isTrue();
        assertThat(registry.snapshot()).hasSize(SystemStreams.ALL.size());
    }

    @Test
    void idempotentBootstrapDoesNotIncrementRefCount() {
        var bootstrap = new SystemStreamBootstrap(registry);

        bootstrap.bootstrap();
        bootstrap.bootstrap();
        bootstrap.bootstrap();

        var entry = registry.lookup(SystemStreams.CLUSTER_EVENTS).unwrap();
        assertThat(entry.refCount()).isEqualTo(1);
    }

    @Test
    void coversAllSystemStreamAddresses() {
        var bootstrap = new SystemStreamBootstrap(registry);

        bootstrap.bootstrap();

        for (var address : SystemStreams.ALL) {
            assertThat(registry.lookup(address).isPresent()).as("address %s", address).isTrue();
        }
    }
}
