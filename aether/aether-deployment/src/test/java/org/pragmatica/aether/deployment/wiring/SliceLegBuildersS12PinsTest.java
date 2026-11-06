// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.machine.LegCommand;
import org.pragmatica.aether.deployment.node.machine.LegCommand.LegKind;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.FakeSliceStore;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue.NamedAddress;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.ORDERS;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.SELF;

/// F1 mutation survivor pins (run s2, `wiring-SliceLegBuilders-04/06/11/17/22`): the manifest readers honour the declared counts, a factory
/// that declares no config-update section names no factory, and a stream section without a name segment registers no consumer. The existing
/// fixtures never put an entry past a declared count, never declare a factory without sections, and never configure a dotless stream section,
/// so the golden suite cannot tell these mutants from the code. Each slice below carries its own `META-INF/slice/<Marker>.manifest`.
class SliceLegBuildersS12PinsTest {
    private static final Artifact BLUEPRINT = Artifact.artifact("org.example:orders-app:1.0.0").unwrap();
    private static final long CLOCK = 1_760_000_000_000L;
    private static final String TOML = """
        [streams.orders]
        partitions = 4

        [audit]
        retention = "1d"
        """;

    interface S12FactoryWithoutSectionsSlice extends Slice {}

    interface S12ConfigBeyondCountSlice extends Slice {}

    interface S12StreamBeyondCountSlice extends Slice {}

    interface S12PublisherBeyondCountSlice extends Slice {}

    interface S12NoSegmentConsumerSlice extends Slice {}

    static final class FactoryWithoutSections implements S12FactoryWithoutSectionsSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class ConfigBeyondCount implements S12ConfigBeyondCountSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class StreamBeyondCount implements S12StreamBeyondCountSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class PublisherBeyondCount implements S12PublisherBeyondCountSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class NoSegmentConsumer implements S12NoSegmentConsumerSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    private final KVStore<AetherKey, AetherValue> kv = WiringFixtures.store();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private SliceLegBuilders builders;

    @BeforeEach
    void setUp() {
        ConfigService.setInstance(ProviderBasedConfigService.providerBasedConfigService(ConfigurationProvider.builder()
                                                                                                             .withSource(TomlConfigSource.tomlConfigSource(TOML)
                                                                                                                                         .unwrap())
                                                                                                             .build()));
        builders = new SliceLegBuilders(new SliceLegBuilders.Sources(SELF,
                                                                     new FakeSliceStore(),
                                                                     kv,
                                                                     WiringFixtures.StubCluster::new,
                                                                     () -> CLOCK,
                                                                     () -> OperatorWarningSink.handingOffTo(warnings::add)));
    }

    @AfterEach
    void tearDown() {
        ConfigService.clear();
    }

    private List<LegCommand> legs(Slice slice, LegKind kind) {
        return builders.legs(ORDERS, slice)
                       .unwrap()
                       .stream()
                       .filter(leg -> leg.kind() == kind)
                       .toList();
    }

    /// `wiring-SliceLegBuilders-04`: red when a factory with no config-update section is taken as the factory (`&&` widened to `||`).
    @Test
    void configUpdates_namesNoFactory_whenTheFactoryDeclaresNoConfigUpdateSection() {
        var updates = builders.configUpdates(ORDERS, new FactoryWithoutSections());

        assertThat(updates.factoryClassName()).isEmpty();
        assertThat(updates.sections()).isEmpty();
    }

    /// `wiring-SliceLegBuilders-06`: red when the config-update reader reads the entry at index `reactive.count` (`<` widened to `<=`).
    @Test
    void configUpdates_readsOnlyTheDeclaredCount_ofConfigUpdateEntries() {
        var updates = builders.configUpdates(ORDERS, new ConfigBeyondCount());

        assertThat(updates.factoryClassName()).isEqualTo("org.example.ConfigFactory");
        assertThat(updates.sections()).containsExactly("database");
    }

    /// `wiring-SliceLegBuilders-22`: red when the reactive-binding reader reads the entry at index `reactive.count` (`<` widened to `<=`).
    @Test
    void legs_registerNoStreamConsumer_pastTheDeclaredReactiveCount() {
        assertThat(legs(new StreamBeyondCount(), LegKind.STREAM_REGISTRATION)).isEmpty();
    }

    /// `wiring-SliceLegBuilders-17`: red when the stream-role reader reads the role at index `stream.publishers.count` (`<` widened to `<=`).
    @Test
    void streamReferences_acquireOnlyTheDeclaredCount_ofPublisherRoles() {
        bindEventsAndAudit();

        var references = builders.streamReferences(ORDERS, new PublisherBeyondCount());

        assertThat(references).extracting(LegCommand::kind).containsOnly(LegKind.STREAM_REF);
        assertThat(references).extracting(leg -> leg.command().key())
                              .as("one holder key per declared role (#1978: each acquire also carries the entry template while none exists)")
                              .filteredOn(AetherKey.StreamRegistryHolderKey.class::isInstance)
                              .hasSize(1);
    }

    /// `wiring-SliceLegBuilders-11`: a configured section with no dot has no stream-name segment, so its consumer is not registered and is raised
    /// with that reason. Red when the guard's `||` is narrowed to `&&`, which takes the whole section name as the stream name.
    @Test
    void legs_registerNoStreamConsumer_whenTheSectionHasNoStreamNameSegment() {
        assertThat(legs(new NoSegmentConsumer(), LegKind.STREAM_REGISTRATION)).isEmpty();

        await().atMost(Duration.ofSeconds(5)).until(() -> !warnings.isEmpty());
        assertThat(warnings.getFirst().message()).contains("could NOT be registered").contains("has no stream name segment");
    }

    private void bindEventsAndAudit() {
        var blueprint = BlueprintId.blueprintId(BLUEPRINT);
        var events = ResourceAddress.resourceAddress("ticketing", "events", "1.0.0").unwrap();
        var audit = ResourceAddress.resourceAddress("ticketing", "audit", "1.0.0").unwrap();

        kv.process(kv.createBatch(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(ORDERS.base()),
                                                              SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, Option.some(blueprint))))));
        kv.process(kv.createBatch(List.of(new KVCommand.Put<>(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(blueprint),
                                                              BlueprintStreamBindingsValue.blueprintStreamBindingsValue(List.of(NamedAddress.namedAddress("events", events),
                                                                                                                                NamedAddress.namedAddress("audit", audit)))))));
    }
}
