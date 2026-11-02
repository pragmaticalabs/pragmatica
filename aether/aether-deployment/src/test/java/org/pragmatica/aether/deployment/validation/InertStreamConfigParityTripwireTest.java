// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

/// Streams audit E-02 (#2201), fixed by spec 3.2 item 9: stream configuration keys that are parsed and accepted but have no runtime effect. The
/// deploy validator ([StreamResourceValidator#partition], the production publish path) and the activation path
/// ([StreamSectionBinding#bindStreamSection], what a node provisions a stream from) now judge a declaration by one table, `InertKeyRules`, so a
/// configuration pushed through the KV layer, which `config-provisioning.md` ranks highest, cannot reach activation with a key the validator
/// refuses and bind silently. For every inert key the validator names, over the real validator and the real binder:
///   - parity: whatever the validator rejects as inert, the activation binder does not accept silently (refuse it, or make the key effective, in
///     which case the validator stops rejecting it and the implication holds vacuously);
///   - the validator still names the rule (the conjunction's first half is the control).
class InertStreamConfigParityTripwireTest {
    private static final Artifact APP_ARTIFACT = Artifact.artifact("com.example:my-app:1.0.0").unwrap();
    private static final ProvisioningContext ACTIVATION = ProvisioningContext.provisioningContext()
                                                                             .withExtension(ReplicationContext.Source.class,
                                                                                            ReplicationContext.Source.fixed(ReplicationContext.BUILT_IN));

    private record InertKey(String name, String line, String rule) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<InertKey> inertStreamKeys() {
        return Stream.of(new InertKey("compression", "compression = \"lz4\"", StreamResourceValidator.RULE_INERT_STREAM_CONFIG),
                         new InertKey("encryption_key_id", "encryption_key_id = \"orders-key\"", StreamResourceValidator.RULE_INERT_STREAM_CONFIG),
                         new InertKey("auto_offset_reset", "auto_offset_reset = \"latest\"", StreamResourceValidator.RULE_INERT_STREAM_CONFIG));
    }

    static Stream<InertKey> inertConsumerKeys() {
        return Stream.of("batch_size = 50",
                         "processing = \"parallel\"",
                         "on_failure = \"skip\"",
                         "checkpoint_interval = \"5s\"",
                         "max_retries = 9",
                         "dead_letter = \"orders-dlq\"",
                         "read_preference = \"replica\"")
                     .map(line -> new InertKey(line.substring(0, line.indexOf(' ')), line, StreamResourceValidator.RULE_INERT_CONSUMER_CONFIG));
    }

    @ParameterizedTest
    @MethodSource("inertStreamKeys")
    void inertStreamKey_isNotBoundSilentlyAtActivation(InertKey key) {
        var toml = streamSection(key.line());

        assertThat(rejectedRules(toml)).as("control: the deploy validator drops the alias, naming the rule").contains("[streams.orders]::" + key.rule());
        assertThat(rejectedRules(toml).contains("[streams.orders]::" + key.rule()) && StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess())
            .as("the validator rejects `%s` as inert, yet the activation binder accepts it; satisfied by making the key effective; deleting the validation also satisfies it and is NOT the fix", key.line())
            .isFalse();
    }

    @Test
    void strongConsistency_isNotBoundSilentlyAtActivation() {
        var toml = streamSection("consistency = \"strong\"");

        assertThat(StreamResourceValidator.ensureHonourableConsistency(Option.some(toml)).isFailure()).as("control: the publish gate refuses STRONG").isTrue();
        assertThat(StreamResourceValidator.ensureHonourableConsistency(Option.some(toml)).isFailure()
                   && StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess())
            .as("the publish gate refuses STRONG, yet the activation binder accepts it; satisfied by making the key effective; deleting the validation also satisfies it and is NOT the fix")
            .isFalse();
    }

    @ParameterizedTest
    @MethodSource("inertConsumerKeys")
    void inertConsumerKey_isNotIgnoredSilentlyAtActivation(InertKey key) {
        var toml = consumerSection(key.line());

        assertThat(rejectedRules(toml)).as("control: the deploy validator drops the stream, naming the consumer section")
                                       .contains("[streams.orders.consumers.billing]::" + key.rule());
        assertThat(rejectedRules(toml).contains("[streams.orders.consumers.billing]::" + key.rule())
                   && StreamSectionBinding.bindStreamSection(providerOf(toml), "streams.orders", ACTIVATION).isSuccess())
            .as("the validator rejects `%s` as inert, yet the activation binder accepts the section; satisfied by making the key effective; deleting the validation also satisfies it and is NOT the fix", key.line())
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
