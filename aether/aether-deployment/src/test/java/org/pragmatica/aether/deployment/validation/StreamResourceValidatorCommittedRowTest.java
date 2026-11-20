// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #3334 (section 2.5): a redeploy that changes an immutable key of a committed stream is refused by
/// [StreamResourceValidator#partition] before any commit, by a named rule. The committed row is the stream's
/// `StreamConfigKey` value, looked up by engine key (`namespace:alias:version`).
class StreamResourceValidatorCommittedRowTest {
    private static final Artifact APP_ARTIFACT = Artifact.artifact("com.example:my-app:1.0.0").unwrap();
    private static final String ENGINE_KEY = "com.example.my-app:orders:1.0.0";
    private static final String FIELD = "[streams.orders]";

    private static final String DECLARED_FOUR = """
            [streams.orders]
            version = "1.0.0"
            partitions = 4
            """;

    /// T-154 (U arm): the partition count of a committed stream cannot change, in either direction.
    @Nested
    class PartitionCount {
        @Test
        void aDifferentPartitionCount_refusesThePublish_namingTheRule() {
            assertThat(refusals(DECLARED_FOUR, committed(16))).containsExactly(FIELD + "::" + StreamResourceValidator.RULE_PARTITION_COUNT_IMMUTABLE);
        }

        @Test
        void aSmallerPartitionCount_refusesThePublish_namingTheRule() {
            assertThat(refusals(DECLARED_FOUR, committed(2))).containsExactly(FIELD + "::" + StreamResourceValidator.RULE_PARTITION_COUNT_IMMUTABLE);
        }
    }

    /// T-155 (U arm): the replication and confirmation factors may only rise.
    @Nested
    class Durability {
        @Test
        void aLowerReplicationFactor_refusesThePublish_namingTheRule() {
            var toml = factors(2, 2);

            assertThat(refusals(toml, committedFactors(3, 2))).containsExactly(FIELD + "::" + StreamResourceValidator.RULE_DURABILITY_ONLY_INCREASES);
        }

        @Test
        void aLowerConfirmationFactor_refusesThePublish_namingTheRule() {
            var toml = factors(3, 2);

            assertThat(refusals(toml, committedFactors(3, 3))).containsExactly(FIELD + "::" + StreamResourceValidator.RULE_DURABILITY_ONLY_INCREASES);
        }

        @Test
        void aRaisedFactor_isAccepted() {
            var toml = factors(3, 3);

            assertThat(refusals(toml, committedFactors(3, 2))).isEmpty();
        }
    }

    /// T-156 (U arm, on model objects): the truncation policy may not change in either direction.
    @Nested
    class TruncationPolicyRule {
        @Test
        void windowToUntilConsumed_isRefused() {
            assertThat(rules(StreamResourceValidator.committedRowFailures("orders",
                                                                         withPolicy(TruncationPolicy.UNTIL_CONSUMED),
                                                                         withPolicy(TruncationPolicy.WINDOW)))).containsExactly(StreamResourceValidator.RULE_TRUNCATION_POLICY_IMMUTABLE);
        }

        @Test
        void untilConsumedToWindow_isRefused() {
            assertThat(rules(StreamResourceValidator.committedRowFailures("orders",
                                                                         withPolicy(TruncationPolicy.WINDOW),
                                                                         withPolicy(TruncationPolicy.UNTIL_CONSUMED)))).containsExactly(StreamResourceValidator.RULE_TRUNCATION_POLICY_IMMUTABLE);
        }

        @Test
        void anUnchangedPolicy_isAccepted() {
            assertThat(StreamResourceValidator.committedRowFailures("orders",
                                                                    withPolicy(TruncationPolicy.WINDOW),
                                                                    withPolicy(TruncationPolicy.WINDOW))).isEmpty();
        }
    }

    /// T-237 (U arm): `max_event_size` and `encryption_key_id` are storage keys; each is named in the message.
    @Nested
    class StorageKeys {
        @Test
        void aDifferentMaxEventSize_refusesThePublish_namingTheKey() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    partitions = 4
                    max_event_size = "2MB"
                    """;

            assertThat(refusals(toml, committed(4))).containsExactly(FIELD + "::" + StreamResourceValidator.RULE_STORAGE_KEY_IMMUTABLE);
            assertThat(messages(toml, committed(4))).allMatch(message -> message.contains("max_event_size"));
        }

        @Test
        void aDifferentEncryptionKey_onModelObjects_isRefused_namingTheKey() {
            var failures = StreamResourceValidator.committedRowFailures("orders",
                                                                        withEncryption(Option.some("key-b")),
                                                                        withEncryption(Option.some("key-a")));

            assertThat(rules(failures)).containsExactly(StreamResourceValidator.RULE_STORAGE_KEY_IMMUTABLE);
            assertThat(failures.getFirst().message()).contains("encryption_key_id");
        }
    }

    /// Controls: what must stay accepted, so a refusal that fires on everything cannot pass the suite.
    @Nested
    class Controls {
        @Test
        void anUnchangedRedeploy_isAccepted() {
            assertThat(refusals(DECLARED_FOUR, committed(4))).isEmpty();
        }

        @Test
        void aFirstDeploy_withNoCommittedRow_isAccepted() {
            assertThat(StreamResourceValidator.partition(Option.some(DECLARED_FOUR),
                                                         APP_ARTIFACT,
                                                         Map.of(),
                                                         ReplicationContext.BUILT_IN,
                                                         _ -> Option.none())
                                              .onFailure(cause -> fail("a first deploy must be accepted: " + cause.message()))
                                              .unwrap()
                                              .accepted()).containsOnlyKeys("orders");
        }

        @Test
        void aChangedCompression_isNotARule() {
            assertThat(StreamResourceValidator.committedRowFailures("orders", withCompression("lz4"), withCompression("none"))).isEmpty();
        }

        @Test
        void aRowOfAnotherStream_isNotCompared() {
            assertThat(refusals(DECLARED_FOUR, key -> "com.example.my-app:other:1.0.0".equals(key)
                                                      ? Option.some(StreamConfig.DEFAULT.withPartitions(16))
                                                      : Option.none())).isEmpty();
        }
    }

    /// R-12: while a key is inert the inert gate wins and the committed row is not compared; the alias is refused as inert.
    @Nested
    class InertGateWins {
        @Test
        void anInertKey_isRefusedAsInert_notAsAnImmutableChange() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    partitions = 4
                    compression = "lz4"
                    """;

            assertThat(refusals(toml, committed(16))).containsExactly(FIELD + "::" + StreamResourceValidator.RULE_INERT_STREAM_CONFIG);
        }
    }

    private static List<String> refusals(String toml, Function<String, Option<StreamConfig>> committedRows) {
        return failures(toml, committedRows).stream().map(failure -> failure.field() + "::" + failure.rule()).toList();
    }

    private static List<String> messages(String toml, Function<String, Option<StreamConfig>> committedRows) {
        return failures(toml, committedRows).stream().map(StreamValidationFailure::message).toList();
    }

    private static List<StreamValidationFailure> failures(String toml, Function<String, Option<StreamConfig>> committedRows) {
        var result = StreamResourceValidator.partition(Option.some(toml),
                                                       APP_ARTIFACT,
                                                       Map.of(),
                                                       ReplicationContext.BUILT_IN,
                                                       committedRows);

        return result.fold(cause -> ((StreamValidationFailures) cause).failures(),
                           partition -> partition.rejected());
    }

    private static List<String> rules(List<StreamValidationFailure> failures) {
        return failures.stream().map(StreamValidationFailure::rule).toList();
    }

    private static String factors(int replication, int confirmation) {
        return """
                [streams.orders]
                version = "1.0.0"
                partitions = 4
                replication_factor = %d
                confirmation_factor = %d
                """.formatted(replication, confirmation);
    }

    private static Function<String, Option<StreamConfig>> committed(int partitions) {
        return key -> ENGINE_KEY.equals(key)
                      ? Option.some(StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(partitions))
                      : Option.none();
    }

    private static Function<String, Option<StreamConfig>> committedFactors(int replication, int confirmation) {
        return key -> ENGINE_KEY.equals(key)
                      ? Option.some(model(4, replication, confirmation, "none", Option.none(), Option.none()))
                      : Option.none();
    }

    private static StreamConfig withPolicy(TruncationPolicy policy) {
        var truncation = TruncationDeclaration.truncationDeclaration(Option.some(policy), Option.none());
        var declaration = StreamDeclaration.streamDeclaration(Option.none(), Option.some(truncation), Option.none(), Option.none());

        return model(4, 3, 2, "none", Option.none(), Option.some(declaration));
    }

    private static StreamConfig withEncryption(Option<String> keyId) {
        return model(4, 3, 2, "none", keyId, Option.none());
    }

    private static StreamConfig withCompression(String compression) {
        return model(4, 3, 2, compression, Option.none(), Option.none());
    }

    /// No factory carries a declaration, so the model object is built from the canonical constructor here, in test code only.
    private static StreamConfig model(int partitions,
                                      int replication,
                                      int confirmation,
                                      String compression,
                                      Option<String> encryptionKeyId,
                                      Option<StreamDeclaration> declaration) {
        var base = StreamConfig.DEFAULT.withName(ENGINE_KEY).withPartitions(partitions);

        return new StreamConfig(base.name(),
                                base.partitions(),
                                base.retention(),
                                base.autoOffsetReset(),
                                base.maxEventSizeBytes(),
                                base.consistencyMode(),
                                replication,
                                confirmation,
                                StreamCompression.valueOf(compression.toUpperCase()),
                                encryptionKeyId,
                                base.incarnation(),
                                declaration);
    }
}
