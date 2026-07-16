// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies the durable-entity resource is registered purely through the SPI
/// (`META-INF/services/org.pragmatica.aether.resource.ResourceFactory`) and that provisioning
/// constructs a working entity without external IO.
class DurableEntityFactoryTest {
    private static final TimeSpan AWAIT = timeSpan(5).seconds();

    @Test
    void serviceLoader_discoversDurableEntityFactory() {
        var discovered = ServiceLoader.load(ResourceFactory.class)
                                      .stream()
                                      .map(ServiceLoader.Provider::get)
                                      .anyMatch(factory -> factory instanceof DurableEntityFactory);

        assertThat(discovered)
            .as("DurableEntityFactory must be discoverable via the ResourceFactory SPI")
            .isTrue();
    }

    @Test
    void factory_reportsResourceAndConfigTypes() {
        var factory = new DurableEntityFactory();

        assertThat(factory.resourceType()).isEqualTo(DurableEntity.class);
        assertThat(factory.configType()).isEqualTo(DurableEntityConfig.class);
    }

    @Test
    void provision_returnsResolvedWorkingEntity() {
        var factory = new DurableEntityFactory();

        DurableEntityConfig.durableEntityConfig("orders")
                           .onFailure(DurableEntityFactoryTest::failCause)
                           .onSuccess(config -> assertProvisions(factory, config));
    }

    private static void assertProvisions(DurableEntityFactory factory, DurableEntityConfig config) {
        var promise = factory.provision(config);

        assertThat(promise.isResolved())
            .as("provision() must return an already-resolved Promise — no async IO permitted")
            .isTrue();

        promise.await(AWAIT)
               .onFailure(DurableEntityFactoryTest::failCause)
               .onSuccess(DurableEntityFactoryTest::assertWorks);
    }

    @SuppressWarnings("unchecked")
    private static void assertWorks(DurableEntity entity) {
        DurableEntity<String, Integer> typed = entity;

        typed.create("k", 1)
             .await(AWAIT)
             .onFailure(DurableEntityFactoryTest::failCause)
             .onSuccess(state -> assertThat(state).isEqualTo(1));
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
