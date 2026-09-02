// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.config.TomlConfigService;
import org.pragmatica.lang.parse.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Verifies [TopicConfig#address] resolution: explicit `namespace:topic:version` declarations
/// round-trip verbatim, while bare/legacy names are lifted to the placeholder `default` namespace
/// (the deploy path later swaps in the blueprint-derived namespace).
///
/// The durable-pubsub half (spec §3, D1) pins the parse-time constraint: the [TopicConfig#topicConfig]
/// factory is what the TOML binder invokes, so the TOML round-trip tests are the ones that prove
/// "rejected at parse" — a factory-only test would pass even if the binder bypassed it.
class TopicConfigTest {

    @Test
    void explicitlyNamespacedTopicRoundTrips() {
        var config = new TopicConfig("org.example.shop:order-events:2.1.0");

        var address = config.address().unwrap();

        assertThat(address.namespace().value()).isEqualTo("org.example.shop");
        assertThat(address.name().value()).isEqualTo("order-events");
        assertThat(address.version().asString()).isEqualTo("2.1.0");
        assertThat(address.asString()).isEqualTo("org.example.shop:order-events:2.1.0");
    }

    @Test
    void explicitSystemNamespaceParsesForFrameworkTopics() {
        var config = new TopicConfig("system:cluster-events:1.0.0");

        var address = config.address().unwrap();

        assertThat(address.isSystem()).isTrue();
        assertThat(address.asString()).isEqualTo("system:cluster-events:1.0.0");
    }

    @Test
    void bareNameResolvesToDefaultNamespaceAndDefaultVersion() {
        var config = new TopicConfig("order-events");

        var address = config.address().unwrap();

        assertThat(address.namespace().value()).isEqualTo(ResourceAddress.DEFAULT_NAMESPACE);
        assertThat(address.name().value()).isEqualTo("order-events");
        assertThat(address.version().asString()).isEqualTo("1.0.0");
    }

    @Test
    void malformedExplicitAddressIsRejected() {
        var config = new TopicConfig("Bad Namespace:order-events:1.0.0");

        assertThat(config.address().isFailure()).isTrue();
    }

    @Test
    void singleArgConstructor_staysEphemeralWithNoKnobs() {
        var config = new TopicConfig("order-events");

        assertThat(config.durability()).isEqualTo(TopicDurability.EPHEMERAL);
        assertThat(config.durableSpec().unwrap().isPresent()).isFalse();
    }

    @Test
    void topicConfig_rejectsBlankTopicName() {
        TopicConfig.topicConfig("", TopicDurability.EPHEMERAL, none(), none(), none(), none())
                   .onSuccess(_ -> fail("blank topic name must not bind"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(TopicConfigError.MissingTopicName.class));
    }

    @Test
    void topicConfig_rejectsInertStreamKeys_onEphemeralTopic() {
        TopicConfig.topicConfig("order-events", TopicDurability.EPHEMERAL, some(4), none(), none(), none())
                   .onSuccess(_ -> fail("inert partitions key must be rejected"))
                   .onFailure(cause -> assertThat(cause.message()).contains("partitions"));
    }

    @Test
    void topicConfig_appliesDurableDefaults() {
        var spec = TopicConfig.topicConfig("order-events", TopicDurability.DURABLE, none(), none(), none(), none())
                              .flatMap(TopicConfig::durableSpec)
                              .unwrap()
                              .unwrap();

        assertThat(spec.partitions()).isEqualTo(DurableTopicSpec.DEFAULT_PARTITIONS);
        assertThat(spec.replicas()).isEqualTo(DurableTopicSpec.DEFAULT_REPLICAS);
        assertThat(spec.minSyncReplicas()).isEqualTo(DurableTopicSpec.DEFAULT_REPLICAS);
        assertThat(spec.retention().duration()).isEqualTo(DurableTopicSpec.DEFAULT_RETENTION.duration());
    }

    @Test
    void topicConfig_defaultsMinSyncToDeclaredReplicas() {
        var spec = TopicConfig.topicConfig("order-events", TopicDurability.DURABLE, none(), some(3), none(), none())
                              .flatMap(TopicConfig::durableSpec)
                              .unwrap()
                              .unwrap();

        assertThat(spec.replicas()).isEqualTo(3);
        assertThat(spec.minSyncReplicas()).isEqualTo(3);
    }

    @Test
    void topicConfig_rejectsSingleReplicaDurable() {
        TopicConfig.topicConfig("order-events", TopicDurability.DURABLE, none(), some(1), none(), none())
                   .onSuccess(_ -> fail("replicas=1 provides no failover durability"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(TopicConfigError.OutsideProvenDurableConfig.class));
    }

    @Test
    void topicConfig_rejectsMinSyncBelowReplicas_until411() {
        TopicConfig.topicConfig("order-events", TopicDurability.DURABLE, none(), some(3), some(2), none())
                   .onSuccess(_ -> fail("min-sync < replicas is outside the proven config"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(TopicConfigError.OutsideProvenDurableConfig.class));
    }

    @Test
    void topicConfig_rejectsNonPositivePartitions() {
        TopicConfig.topicConfig("order-events", TopicDurability.DURABLE, some(0), none(), none(), none())
                   .onSuccess(_ -> fail("partitions=0 must be rejected"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(TopicConfigError.InvalidPartitions.class));
    }

    @Test
    void durableSpec_failsLoudly_forConstructorBypassedInvalidConfig() {
        var bypassed = new TopicConfig("order-events", TopicDurability.DURABLE, none(), some(1), none(), none());

        assertThat(bypassed.durableSpec().isFailure()).isTrue();
    }

    @Test
    void tomlBinding_bindsLegacyDeclaration_asEphemeral() {
        var config = bind("""
                          [orders]
                          topic_name = "order-events"
                          """);

        assertThat(config.topicName()).isEqualTo("order-events");
        assertThat(config.durability()).isEqualTo(TopicDurability.EPHEMERAL);
        assertThat(config.durableSpec().unwrap().isPresent()).isFalse();
    }

    @Test
    void tomlBinding_bindsDurableDeclaration_resolvingDefaults() {
        var config = bind("""
                          [orders]
                          topic_name = "order-events"
                          durability = "durable"
                          retention = "14d"
                          """);

        var spec = config.durableSpec().unwrap().unwrap();

        assertThat(spec.partitions()).isEqualTo(1);
        assertThat(spec.replicas()).isEqualTo(2);
        assertThat(spec.minSyncReplicas()).isEqualTo(2);
        assertThat(spec.retention().duration()).isEqualTo(TimeSpan.timeSpan("14d").unwrap().duration());
    }

    @Test
    void tomlBinding_rejectsDurableOutsideProvenConstraint() {
        TomlConfigService.tomlConfigService("""
                                            [orders]
                                            topic_name = "order-events"
                                            durability = "durable"
                                            replicas = 3
                                            min_sync_replicas = 2
                                            """)
                         .flatMap(svc -> svc.config("orders", TopicConfig.class))
                         .onSuccess(_ -> fail("min-sync < replicas must be rejected at parse"))
                         .onFailure(cause -> assertThat(cause.message()).contains("durable-pubsub-spec"));
    }

    @Test
    void tomlBinding_rejectsStreamKeysOnEphemeralTopic() {
        TomlConfigService.tomlConfigService("""
                                            [orders]
                                            topic_name = "order-events"
                                            partitions = 4
                                            """)
                         .flatMap(svc -> svc.config("orders", TopicConfig.class))
                         .onSuccess(_ -> fail("inert keys on an ephemeral topic must be rejected"))
                         .onFailure(cause -> assertThat(cause.message()).contains("partitions"));
    }

    @Test
    void tomlBinding_missingTopicName_staysLoud() {
        TomlConfigService.tomlConfigService("""
                                            [orders]
                                            durability = "durable"
                                            """)
                         .flatMap(svc -> svc.config("orders", TopicConfig.class))
                         .onSuccess(_ -> fail("missing topic_name must not bind via DEFAULT"))
                         .onFailure(cause -> assertThat(cause).isInstanceOf(TopicConfigError.MissingTopicName.class));
    }

    private static TopicConfig bind(String toml) {
        return TomlConfigService.tomlConfigService(toml)
                                .flatMap(svc -> svc.config("orders", TopicConfig.class))
                                .unwrap();
    }
}
