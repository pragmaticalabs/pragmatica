// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConfig;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.acquire;
import static org.pragmatica.aether.resource.SharedPoolFixtures.providerOf;

/// #280 R27: a PER-SLICE factory learns the section it is provisioned for through the [ResourceSection] extension; the
/// node-shared path (#2973) never carries it, because a [NodeShareable] factory's resource belongs to no section.
class ResourceSectionContextTest {
    private static final String SECTION = "database.main";

    /// Captures the context of every provision. Not a [NodeShareable].
    private static class CapturingFactory implements ResourceFactory<FakeConnector, FakeConfig> {
        final List<ProvisioningContext> contexts = new CopyOnWriteArrayList<>();
        private final FakeShareableFactory pools = new FakeShareableFactory();
        private final int priority;

        CapturingFactory(int priority) {
            this.priority = priority;
        }

        @Override
        public Class<FakeConnector> resourceType() {
            return FakeConnector.class;
        }

        @Override
        public Class<FakeConfig> configType() {
            return FakeConfig.class;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Promise<FakeConnector> provision(FakeConfig config) {
            return Promise.success(pools.newPool());
        }

        @Override
        public Promise<FakeConnector> provision(FakeConfig config, ProvisioningContext context) {
            contexts.add(context);

            return provision(config);
        }
    }

    /// Overrides the context overload in an INTERFACE default, which the provider's demotion check (it reads declared
    /// methods of the class chain) does not see: a [NodeShareable] that reads context and is NOT demoted. The shared path
    /// must withhold the section from it regardless.
    interface ContextCapturing extends ResourceFactory<FakeConnector, FakeConfig> {
        List<ProvisioningContext> contexts();

        @Override
        default Promise<FakeConnector> provision(FakeConfig config, ProvisioningContext context) {
            contexts().add(context);

            return provision(config);
        }
    }

    static final class UndemotedShareable extends FakeShareableFactory implements ContextCapturing {
        private final List<ProvisioningContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public List<ProvisioningContext> contexts() {
            return contexts;
        }
    }

    private static Map<String, FakeConfig> configs(FakeConfig config) {
        return Map.of(SECTION, config);
    }

    private static String sectionOf(ProvisioningContext context) {
        return context.extension(ResourceSection.class)
                      .map(ResourceSection::name)
                      .or("<absent>");
    }

    @Test
    void cachedPath_carriesTheSectionName() {
        var factory = new CapturingFactory(0);
        var provider = providerOf(configs(FakeConfig.shared("pg://h/db")), factory);

        acquire(provider, SECTION, "slice-a");

        assertThat(factory.contexts).hasSize(1);
        assertThat(sectionOf(factory.contexts.getFirst())).isEqualTo(SECTION);
    }

    @Test
    void cachedPath_keepsTheCallersOwnExtensions() {
        var factory = new CapturingFactory(0);
        var provider = providerOf(configs(FakeConfig.shared("pg://h/db")), factory);

        acquire(provider, SECTION, "slice-a");

        assertThat(factory.contexts.getFirst().extension(String.class).or("<absent>")).isEqualTo("slice-a");
    }

    @Test
    void perSlicePoolPath_carriesTheSectionName() {
        var capturing = new CapturingFactory(10);
        var provider = providerOf(configs(FakeConfig.shared("pg://h/db")), capturing, new FakeShareableFactory());

        acquire(provider, SECTION, "slice-a");

        assertThat(capturing.contexts).hasSize(1);
        assertThat(sectionOf(capturing.contexts.getFirst())).isEqualTo(SECTION);
    }

    @Test
    void nodeSharedPath_neverCarriesTheSection() {
        var factory = new UndemotedShareable();
        var provider = providerOf(configs(FakeConfig.shared("pg://h/db")), factory);

        acquire(provider, SECTION, "slice-a");

        assertThat(factory.contexts()).as("the factory was provisioned with a context").hasSize(1);
        assertThat(factory.contexts().getFirst().extension(String.class).or("<absent>")).isEqualTo("slice-a");
        assertThat(sectionOf(factory.contexts().getFirst())).as("a node-shared pool belongs to no section")
                                                            .isEqualTo("<absent>");
    }

    @Test
    void sameFactoryOnTheSlicePoolPath_carriesTheSection() {
        var factory = new UndemotedShareable();
        var provider = providerOf(configs(FakeConfig.perSlice("pg://h/db")), factory);

        acquire(provider, SECTION, "slice-a");

        assertThat(factory.contexts()).hasSize(1);
        assertThat(sectionOf(factory.contexts().getFirst())).isEqualTo(SECTION);
    }
}
