// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.stream.StreamSectionBinding;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Streams audit E-02 (#2201): stream configuration keys that are parsed and accepted but have no runtime effect. The deploy validator
/// ([StreamResourceValidator#partition], the production publish path) knows the list and drops the alias that declares one, naming the rule. The
/// activation path ([StreamSectionBinding#bindStreamSection], what a node provisions a stream from) has no such guard: the same section binds, the
/// stream is created with the inert value, and nothing says it does nothing. A configuration pushed through the KV layer, which
/// `config-provisioning.md` ranks highest, reaches activation without passing the validator.
///
/// Two halves of the finding, each a TRIPWIRE (enabled, GREEN) beside a REAL assertion (`@Disabled`, RED today), both over the real validator and the
/// real binder for every inert key the validator names:
///   - parity: whatever the validator rejects as inert, the activation binder must not accept silently (refuse it, or make the key effective, in which
///     case the validator stops rejecting it and the implication holds vacuously);
///   - the validator side is a control (GREEN now and after a refuse-side fix): it still names the rule.
class InertStreamConfigParityTripwireTest {
    private static final Artifact APP_ARTIFACT = Artifact.artifact("com.example:my-app:1.0.0").unwrap();
    private static final ProvisioningContext ACTIVATION = ProvisioningContext.provisioningContext()
                                                                             .withExtension(ReplicationContext.Source.class,
                                                                                            ReplicationContext.Source.fixed(ReplicationContext.BUILT_IN));
    private static final String ENABLE = "E-02 tripwire: the activation binder now refuses a configuration key the validator rejects as inert; delete this tripwire and enable the real assertion beside it (#2201)";

    private record InertKey(String name, String line, String rule) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<InertKey> inertStreamKeys() {
        return Stream.of(new InertKey("compression", "compression = \"lz4\"", StreamResourceValidator.RULE_INERT_STREAM_CONFIG),
                         new InertKey("encryption-key-id", "encryption-key-id = \"orders-key\"", StreamResourceValidator.RULE_INERT_STREAM_CONFIG),
                         new InertKey("auto-offset-reset", "auto-offset-reset = \"latest\"", StreamResourceValidator.RULE_INERT_STREAM_CONFIG));
    }

    static Stream<InertKey> inertConsumerKeys() {
        return Stream.of("batch-size = 50",
                         "processing = \"parallel\"",
                         "on-failure = \"skip\"",
                         "checkpoint-interval = \"5s\"",
                         "max-retries = 9",
                         "dead-letter = \"orders-dlq\"",
                         "read-preference = \"replica\"")
                     .map(line -> new InertKey(line.substring(0, line.indexOf(' ')), line, StreamResourceValidator.RULE_INERT_CONSUMER_CONFIG));
    }

    @ParameterizedTest
    @MethodSource("inertStreamKeys")
    void inertStreamKey_isRejectedByTheValidator_butBoundSilentlyAtActivation_tripwire(InertKey key) {
        var toml = streamSection(key.line());

        assertThat(rejectedRules(toml)).as("control: the deploy validator drops the alias, naming the rule").contains("[streams.orders]::" + key.rule());
        assertThat(StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess()).as(ENABLE + " [" + key + "]").isTrue();
    }

    @Disabled("E-02 real assertion, RED today: a key the validator rejects as inert must not bind silently at activation (#2201)")
    @ParameterizedTest
    @MethodSource("inertStreamKeys")
    void inertStreamKey_isNotBoundSilentlyAtActivation(InertKey key) {
        var toml = streamSection(key.line());

        assertThat(rejectedRules(toml).contains("[streams.orders]::" + key.rule()) && StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess())
            .as("the validator rejects `%s` as inert, yet the activation binder accepts it", key.line())
            .isFalse();
    }

    /// `consistency = "strong"` is refused by the separate whole-publish gate ([StreamResourceValidator#ensureHonourableConsistency], #1262), not by
    /// the per-section inert rule; the activation binder takes it all the same.
    @Test
    void strongConsistency_isRefusedByThePublishGate_butBoundSilentlyAtActivation_tripwire() {
        var toml = streamSection("consistency = \"strong\"");

        assertThat(StreamResourceValidator.ensureHonourableConsistency(Option.some(toml)).isFailure()).as("control: the publish gate refuses STRONG").isTrue();
        assertThat(StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess()).as(ENABLE + " [consistency]").isTrue();
    }

    @Disabled("E-02 real assertion, RED today: a STRONG declaration the publish gate refuses must not bind silently at activation (#2201)")
    @Test
    void strongConsistency_isNotBoundSilentlyAtActivation() {
        var toml = streamSection("consistency = \"strong\"");

        assertThat(StreamResourceValidator.ensureHonourableConsistency(Option.some(toml)).isFailure()
                   && StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess())
            .as("the publish gate refuses STRONG, yet the activation binder accepts it")
            .isFalse();
    }

    @ParameterizedTest
    @MethodSource("inertConsumerKeys")
    void inertConsumerKey_isRejectedByTheValidator_butIgnoredSilentlyAtActivation_tripwire(InertKey key) {
        var toml = consumerSection(key.line());

        assertThat(rejectedRules(toml)).as("control: the deploy validator drops the stream, naming the consumer section")
                                       .contains("[streams.orders.consumers.billing]::" + key.rule());
        assertThat(StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess()).as(ENABLE + " [" + key + "]").isTrue();
    }

    @Disabled("E-02 real assertion, RED today: a consumer key the validator rejects as inert must not be ignored silently at activation (#2201)")
    @ParameterizedTest
    @MethodSource("inertConsumerKeys")
    void inertConsumerKey_isNotIgnoredSilentlyAtActivation(InertKey key) {
        var toml = consumerSection(key.line());

        assertThat(rejectedRules(toml).contains("[streams.orders.consumers.billing]::" + key.rule())
                   && StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess())
            .as("the validator rejects `%s` as inert, yet the activation binder accepts the section", key.line())
            .isFalse();
    }

    private static String streamSection(String line) {
        return """
               [streams.orders]
               version = "1.0.0"
               %s
               """.formatted(line);
    }

    private static String consumerSection(String line) {
        return """
               [streams.orders]
               version = "1.0.0"

               [streams.orders.consumers.billing]
               %s
               """.formatted(line);
    }

    private static List<String> rejectedRules(String toml) {
        return StreamResourceValidator.partition(Option.some(toml), APP_ARTIFACT, Map.of(), ReplicationContext.BUILT_IN)
                                      .onFailure(cause -> fail("Expected a partition, got: " + cause.message()))
                                      .unwrap()
                                      .rejected()
                                      .stream()
                                      .map(failure -> failure.field() + "::" + failure.rule())
                                      .toList();
    }

    private static ConfigurationProvider providerOf(String toml) {
        return ConfigurationProvider.builder()
                                    .withSource(TomlConfigSource.tomlConfigSource(toml).unwrap())
                                    .build();
    }
}
