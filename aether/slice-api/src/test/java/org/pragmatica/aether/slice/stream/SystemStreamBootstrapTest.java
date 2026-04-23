// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
        assertThat(entries).hasSize(1);
        var entry = entries.getFirst();
        assertThat(entry.address()).isEqualTo(SystemStreams.CLUSTER_EVENTS);
        assertThat(entry.registeredBy()).isEqualTo(RegisteredByKind.FRAMEWORK);
        assertThat(entry.refCount()).isEqualTo(1);
        assertThat(entry.registeredAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void bootstrapIsIdempotent() {
        var bootstrap = new SystemStreamBootstrap(registry);

        bootstrap.bootstrap();
        var second = bootstrap.bootstrap();

        assertThat(second.isSuccess()).isTrue();
        assertThat(registry.snapshot()).hasSize(1);
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
