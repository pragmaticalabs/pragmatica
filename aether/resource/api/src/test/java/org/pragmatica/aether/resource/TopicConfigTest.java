// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.config.ConfigError;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.config.source.TomlConfigSource;
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
///
/// The TOML round-trip tests bind through the actual production path
/// (`TomlConfigSource` -> `ConfigurationProvider` -> `ProviderBasedConfigService`), not a
/// test-only shortcut — `TomlConfigService` has no production caller, so a test built on it would
/// prove nothing about runtime behavior (#738 post-GO correction).
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
        serviceFrom("""
                    [orders]
                    topic_name = "order-events"
                    durability = "durable"
                    replicas = 3
                    min_sync_replicas = 2
                    """).config("orders", TopicConfig.class)
                        .onSuccess(_ -> fail("min-sync < replicas must be rejected at parse"))
                        .onFailure(cause -> assertThat(cause.message()).contains("durable-pubsub-spec"));
    }

    @Test
    void tomlBinding_rejectsStreamKeysOnEphemeralTopic() {
        serviceFrom("""
                    [orders]
                    topic_name = "order-events"
                    partitions = 4
                    """).config("orders", TopicConfig.class)
                        .onSuccess(_ -> fail("inert keys on an ephemeral topic must be rejected"))
                        .onFailure(cause -> assertThat(cause.message()).contains("partitions"));
    }

    @Test
    void tomlBinding_missingTopicName_staysLoud() {
        serviceFrom("""
                    [orders]
                    durability = "durable"
                    """).config("orders", TopicConfig.class)
                        .onSuccess(_ -> fail("missing topic_name must not bind via DEFAULT"))
                        .onFailure(cause -> assertThat(cause).isInstanceOf(TopicConfigError.MissingTopicName.class));
    }

    /// #738: a dashed key where the binder expects an underscore (`min-sync-replicas` vs
    /// `min_sync_replicas`) must be rejected, not silently resolved to `none()` — before the fix
    /// this was byte-indistinguishable from the key never having been written.
    @Test
    void tomlBinding_rejectsDashedKey_namingCorrectSpelling() {
        serviceFrom("""
                    [orders]
                    topic_name = "order-events"
                    durability = "durable"
                    retention = "14d"
                    min-sync-replicas = 2
                    """).config("orders", TopicConfig.class)
                        .onSuccess(_ -> fail("dashed min-sync-replicas must be rejected, not silently ignored"))
                        .onFailure(cause -> {
                            assertThat(cause).isInstanceOf(ConfigError.UnknownKey.class);
                            assertThat(cause.message()).contains("min_sync_replicas");
                        });
    }

    /// A nonsense/typo key is rejected naming the nearest of [TopicConfig]'s known component names
    /// (Levenshtein distance 1 from `partitions`) — proves the suggestion is computed, not just a
    /// fixed "unknown key" message.
    @Test
    void tomlBinding_rejectsTypoKey_namingNearestKnownComponent() {
        serviceFrom("""
                    [orders]
                    topic_name = "order-events"
                    partitons = 4
                    """).config("orders", TopicConfig.class)
                        .onSuccess(_ -> fail("typo'd key 'partitons' must be rejected"))
                        .onFailure(cause -> {
                            assertThat(cause).isInstanceOf(ConfigError.UnknownKey.class);
                            assertThat(cause.message()).contains("partitions");
                        });
    }

    /// Guards against a false positive from the opt-in [org.pragmatica.config.StrictKeys] check:
    /// every durable-tier key, correctly spelled, still binds clean.
    @Test
    void tomlBinding_correctlySpelledDurableSection_bindsWithNoFalsePositive() {
        var config = bind("""
                          [orders]
                          topic_name = "order-events"
                          durability = "durable"
                          partitions = 4
                          replicas = 3
                          min_sync_replicas = 3
                          retention = "14d"
                          """);

        var spec = config.durableSpec().unwrap().unwrap();

        assertThat(spec.partitions()).isEqualTo(4);
        assertThat(spec.replicas()).isEqualTo(3);
        assertThat(spec.minSyncReplicas()).isEqualTo(3);
    }

    /// Team-lead condition 2: the strict check is scoped to exactly the keys [TopicConfig] itself
    /// binds — a nested sub-section (owned by the dashed-by-convention `StreamConfigParser`) is
    /// never inspected, however it is spelled. `provider.keys()` returns one flat merged key set
    /// for the whole document, so nested-table isolation has to be filtered explicitly rather than
    /// falling out of a per-section data structure — this is the test that would catch a regression
    /// removing that filter.
    @Test
    void tomlBinding_acceptsDashedKeyInsideNestedConsumerSubsection() {
        var config = bind("""
                          [orders]
                          topic_name = "order-events"
                          durability = "durable"
                          retention = "14d"

                          [orders.consumers.handler]
                          batch-size = 100
                          """);

        assertThat(config.topicName()).isEqualTo("order-events");
        assertThat(config.durability()).isEqualTo(TopicDurability.DURABLE);
    }

    /// Companion to the above: a dashed key at the topic level is still rejected — naming its own
    /// correct spelling, never anything from the nested consumer sub-section — even though a
    /// dashed key inside that nested sub-section is present and accepted in the same document.
    @Test
    void tomlBinding_rejectsDashedTopicLevelKey_evenWithNestedConsumerSubsectionPresent() {
        serviceFrom("""
                    [orders]
                    topic_name = "order-events"
                    durability = "durable"
                    retention = "14d"
                    min-sync-replicas = 2

                    [orders.consumers.handler]
                    batch-size = 100
                    """).config("orders", TopicConfig.class)
                        .onSuccess(_ -> fail("dashed min-sync-replicas at topic level must be rejected"))
                        .onFailure(cause -> {
                            assertThat(cause).isInstanceOf(ConfigError.UnknownKey.class);
                            assertThat(cause.message()).contains("min_sync_replicas");
                            assertThat(cause.message()).doesNotContain("batch");
                        });
    }

    /// Team-lead condition 1 (BLOCKING): `provider.keys()` was the merged composite of every
    /// layer — system properties ([org.pragmatica.config.source.SystemPropertyConfigSource]),
    /// environment ([org.pragmatica.config.source.EnvironmentConfigSource]), and the KV overlay
    /// replayed at startup — so a system property landing at `<section>.<one segment>` used to
    /// hard-fail a bind the file alone would have accepted, with no file change at all. The check
    /// is now scoped to [org.pragmatica.config.ConfigurationProvider#staticKeys()], which the
    /// builder-composed provider implements by excluding [org.pragmatica.config.source.SystemPropertyConfigSource]
    /// by type: red before the fix (the property alone failed this exact bind), green after.
    @Test
    void tomlBinding_ignoresSystemPropertyKeyAtTopicSection_neverFailsStrictBind() {
        System.setProperty("aether.orders.deploy_trace_id", "abc123");

        try {
            var config = bind("""
                              [orders]
                              topic_name = "order-events"
                              """, "aether.");

            assertThat(config.topicName()).isEqualTo("order-events");
        } finally {
            System.clearProperty("aether.orders.deploy_trace_id");
        }
    }

    /// Companion to the above, in the SAME document: a system-property key at the topic section is
    /// invisible to the strict check while a dashed FILE key is still rejected — proving the
    /// scoping is to the static/file layer specifically, not merely "system properties never break
    /// anything."
    @Test
    void tomlBinding_rejectsDashedFileKey_whileSystemPropertyKeyAtSameSectionIsIgnored() {
        System.setProperty("aether.orders.deploy_trace_id", "abc123");

        try {
            serviceFrom("""
                        [orders]
                        topic_name = "order-events"
                        durability = "durable"
                        retention = "14d"
                        min-sync-replicas = 2
                        """, "aether.").config("orders", TopicConfig.class)
                            .onSuccess(_ -> fail("dashed file key must still be rejected"))
                            .onFailure(cause -> {
                                assertThat(cause).isInstanceOf(ConfigError.UnknownKey.class);
                                assertThat(((ConfigError.UnknownKey) cause).keys()).containsExactly("min-sync-replicas");
                            });
        } finally {
            System.clearProperty("aether.orders.deploy_trace_id");
        }
    }

    /// Team-lead re-check condition (future-proofing): the static/dynamic split inside the
    /// builder-composed provider used to be a DENYLIST by type ([org.pragmatica.config.source.EnvironmentConfigSource]
    /// / [org.pragmatica.config.source.SystemPropertyConfigSource]) — any OTHER `ConfigSource`
    /// implementation, present or added later, defaulted to "static" and was therefore checked,
    /// silently reopening the #738 hole for a dynamic source type nobody had named yet. Inverted to
    /// an ALLOWLIST of exactly the one genuinely file-backed source ([TomlConfigSource]); every
    /// other `ConfigSource` now defaults to excluded. [MapConfigSource] stands in here for "some
    /// future dynamic source type nobody has named yet" — it is neither of the two denylisted types,
    /// so under the old code its key was treated as static and failed this exact bind (red before);
    /// under the allowlist it is excluded like any unrecognized type (green after).
    @Test
    void tomlBinding_ignoresUnknownConfigSourceTypeKeyAtTopicSection_neverFailsStrictBind() {
        var source = TomlConfigSource.tomlConfigSource("""
                                                        [orders]
                                                        topic_name = "order-events"
                                                        """).unwrap();
        var extra = MapConfigSource.mapConfigSource("probe", Map.of("orders.deploy_trace_id", "abc123")).unwrap();
        var provider = ConfigurationProvider.builder()
                                            .withSource(source)
                                            .withSource(extra)
                                            .build();
        var config = ProviderBasedConfigService.providerBasedConfigService(provider)
                                                .config("orders", TopicConfig.class)
                                                .unwrap();

        assertThat(config.topicName()).isEqualTo("order-events");
    }

    /// FOLD-IN 3: a quoted key with a literal dot (`"a.b" = 1`) is, once [TomlConfigSource]
    /// flattens it, byte-identical to a genuine nested sub-table (`[orders.a]` / `b = 1`) — no
    /// `ConfigSource`/`ConfigurationProvider` retains section structure past parse time to tell
    /// them apart. The strict check's `indexOf('.') < 0` scoping — the same guard that protects
    /// real nested sub-sections such as `[orders.consumers.handler]` above — therefore never flags
    /// this key. Documented as an intentional escape hatch on [TopicConfig]'s class Javadoc, not a
    /// defect: this test pins the current (unchanged) behavior rather than proving a fix.
    @Test
    void tomlBinding_ignoresQuotedDottedKey_indistinguishableFromNestedSubsection() {
        var config = bind("""
                          [orders]
                          topic_name = "order-events"
                          "a.b" = 1
                          """);

        assertThat(config.topicName()).isEqualTo("order-events");
    }

    /// FOLD-IN 4a: every unrecognized key in the section is named in one error, not just the
    /// first — `findFirst()` silently hid all but one of several typos, cheapening the diagnostic
    /// exactly where an operator needs the full list.
    @Test
    void tomlBinding_reportsAllUnknownKeys_inOneError_notJustFirst() {
        serviceFrom("""
                    [orders]
                    topic_name = "order-events"
                    partitons = 4
                    replicaas = 3
                    """).config("orders", TopicConfig.class)
                        .onSuccess(_ -> fail("both typo'd keys must be rejected together"))
                        .onFailure(cause -> {
                            assertThat(cause).isInstanceOf(ConfigError.UnknownKey.class);
                            assertThat(((ConfigError.UnknownKey) cause).keys())
                                    .containsExactlyInAnyOrder("partitons", "replicaas");
                            assertThat(cause.message()).contains("partitions").contains("replicas");
                        });
    }

    /// FOLD-IN 4b: a key bearing no real resemblance to any known component (team-lead's own
    /// `zzzzzzzzzzqqqq` example) gets no suggestion at all — an unbounded nearest-match search
    /// always names SOME component as the argmin, which reads as a real suggestion rather than the
    /// noise it is.
    @Test
    void tomlBinding_omitsSuggestion_forKeyBeyondDistanceThreshold() {
        serviceFrom("""
                    [orders]
                    topic_name = "order-events"
                    zzzzzzzzzzqqqq = 4
                    """).config("orders", TopicConfig.class)
                        .onSuccess(_ -> fail("unknown key must still be rejected"))
                        .onFailure(cause -> {
                            assertThat(cause).isInstanceOf(ConfigError.UnknownKey.class);
                            assertThat(cause.message()).doesNotContain("did you mean");
                        });
    }

    private static ConfigService serviceFrom(String toml) {
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();

        return ProviderBasedConfigService.providerBasedConfigService(provider);
    }

    /// Layers a system-property source (priority 200, wins over the TOML file) atop the same TOML
    /// source `serviceFrom(String)` uses — mirrors the production `node.toml` + env + system-property
    /// composite closely enough to prove [org.pragmatica.config.ConfigurationProvider#staticKeys()]
    /// scoping without dragging in the full node-composite construction path.
    private static ConfigService serviceFrom(String toml, String systemPropertyPrefix) {
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();
        var provider = ConfigurationProvider.builder()
                                            .withSource(source)
                                            .withSystemProperties(systemPropertyPrefix)
                                            .build();

        return ProviderBasedConfigService.providerBasedConfigService(provider);
    }

    private static TopicConfig bind(String toml) {
        return serviceFrom(toml).config("orders", TopicConfig.class)
                                 .unwrap();
    }

    private static TopicConfig bind(String toml, String systemPropertyPrefix) {
        return serviceFrom(toml, systemPropertyPrefix).config("orders", TopicConfig.class)
                                                       .unwrap();
    }
}
