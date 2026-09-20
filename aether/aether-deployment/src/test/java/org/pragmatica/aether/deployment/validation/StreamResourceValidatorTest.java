// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class StreamResourceValidatorTest {

    private static final Artifact APP_ARTIFACT = Artifact.artifact("com.example:my-app:1.0.0").unwrap();

    /// Reserved-namespace fixture: groupId `system.framework` + artifactId `audit` derives to
    /// `system.framework.audit`, which fails the `system.*` prefix reservation per spec §4.3.
    /// `Artifact.artifact` only enforces Maven-coord shape (groupId must contain a dot); the
    /// stream-namespace reservation check fires inside the validator.
    private static final Artifact RESERVED_ARTIFACT = Artifact.artifact("system.framework:audit:1.0.0").unwrap();

    @Nested
    class HappyPath {
        @Test
        void emptyResourcesYieldsSuccess() {
            var result = StreamResourceValidator.validate(Option.none(), APP_ARTIFACT);

            result.onFailure(cause -> fail("Expected success but got: " + cause.message()))
                  .onSuccess(validated -> {
                      assertThat(validated.resources()).isEmpty();
                      assertThat(validated.warnings()).isEmpty();
                  });
        }

        @Test
        void validBlueprintYieldsSuccess() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    partitions = 4
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onFailure(cause -> fail("Expected success: " + cause.message()))
                  .onSuccess(validated -> assertThat(validated.resources()).containsKey("orders"));
        }

        @Test
        void manifestRoleHintInfersConsumerLatest() {
            var toml = """
                    [streams.inventory]
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml),
                                                            APP_ARTIFACT,
                                                            Map.of("inventory", "consumer"));

            result.onFailure(cause -> fail("Expected success: " + cause.message()))
                  .onSuccess(validated -> assertThat(validated.resources()).containsKey("inventory"));
        }
    }

    @Nested
    class CompositeFailures {
        @Test
        void multipleErrorsAggregateIntoCompositeWithDistinctRules() {
            var toml = """
                    [streams.orders]
                    role = "producer"
                    version = "latest"

                    [streams.bad-source]
                    source = "not-a-valid-address"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), RESERVED_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> {
                      assertThat(cause).isInstanceOf(StreamValidationFailures.class);
                      var failures = ((StreamValidationFailures) cause).failures();
                      assertThat(failures).hasSizeGreaterThanOrEqualTo(3);
                      var rules = failures.stream().map(StreamValidationFailure::rule).toList();
                      assertThat(rules).contains(StreamResourceValidator.RULE_NAMESPACE_RESERVED,
                                                  "producer-version-must-be-exact");
                  });
        }

        @Test
        void reservedNamespaceProducesNamespaceReservedFailure() {
            var result = StreamResourceValidator.validate(Option.none(), RESERVED_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> {
                      var failures = ((StreamValidationFailures) cause).failures();
                      assertThat(failures).extracting(StreamValidationFailure::rule)
                                          .contains(StreamResourceValidator.RULE_NAMESPACE_RESERVED);
                  });
        }

        /// #1282: a blueprint `External` source naming a runtime-provisioned stream kind is refused at the
        /// deploy gate with its own rule, pointing at the offending section — the typed parser error, not a
        /// message-text guess (the message says "stream name", which would otherwise classify it wrong).
        @Test
        void externalSourceWithReservedKindProducesSourceReservedKindFailure() {
            var toml = "[streams.inbox]\nsource = \"entity:orders:1.0.0\"\nrole = \"consumer\"\n";
            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> {
                      var failures = ((StreamValidationFailures) cause).failures();
                      assertThat(failures).extracting(StreamValidationFailure::rule)
                                          .containsExactly(StreamResourceValidator.RULE_SOURCE_RESERVED_KIND);
                      assertThat(failures).extracting(StreamValidationFailure::field)
                                          .containsExactly("[streams.inbox]");
                  });
        }

        @Test
        void producerLatestProducesProducerExactFailure() {
            var toml = """
                    [streams.orders]
                    role = "producer"
                    version = "latest"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(((StreamValidationFailures) cause).failures())
                                              .extracting(StreamValidationFailure::rule)
                                              .contains("producer-version-must-be-exact"));
        }

        @Test
        void inferredProducerWithLatestExplicitVersionIsRejected() {
            var toml = """
                    [streams.orders]
                    version = "latest"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml),
                                                            APP_ARTIFACT,
                                                            Map.of("orders", "producer"));

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(((StreamValidationFailures) cause).failures())
                                              .extracting(StreamValidationFailure::rule)
                                              .contains("producer-version-must-be-exact"));
        }

        @Test
        void bothRoleWithLatestVersionIsRejected() {
            var toml = """
                    [streams.orders]
                    role = "both"
                    version = "latest"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(((StreamValidationFailures) cause).failures())
                                              .extracting(StreamValidationFailure::rule)
                                              .contains("producer-version-must-be-exact"));
        }
    }

    @Nested
    class FailureFieldShape {
        @Test
        void failureFieldPointsToOffendingTomlSection() {
            var toml = """
                    [streams.orders]
                    role = "producer"
                    version = "latest"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> {
                      var failures = ((StreamValidationFailures) cause).failures();
                      assertThat(failures).extracting(StreamValidationFailure::field)
                                          .contains("[streams.orders]");
                  });
        }
    }

    @Nested
    class Warnings {
        @Test
        void allValidBlueprintHasNoWarnings() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onFailure(cause -> fail("Expected success: " + cause.message()))
                  .onSuccess(validated -> assertThat(validated.warnings()).isEmpty());
        }
    }

    @Nested
    class ConvenienceApi {
        @Test
        void validateResourcesReturnsParsedMap() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    """;

            var result = StreamResourceValidator.validateResources(Option.some(toml), APP_ARTIFACT, Map.of());

            result.onFailure(cause -> fail("Expected success: " + cause.message()))
                  .onSuccess(map -> assertThat(map).containsKey("orders"));
        }

        @Test
        void flattenFailuresExposesIndividualEntries() {
            var toml = """
                    [streams.orders]
                    role = "producer"
                    version = "latest"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onFailure(cause -> {
                var flattened = StreamResourceValidator.flattenFailures(cause);
                assertThat(flattened).isNotEmpty();
                assertThat(flattened).extracting(StreamValidationFailure::rule)
                                      .contains("producer-version-must-be-exact");
            });
        }

        @Test
        void emptyWarningsListIsMutable() {
            var warnings = StreamResourceValidator.emptyWarnings();
            warnings.add(new StreamValidationWarning("[streams.x]", "rule", "msg"));
            assertThat(warnings).hasSize(1);
        }

        @Test
        void isReservedNamespaceMatchesSystemPrefix() {
            assertThat(StreamResourceValidator.isReservedNamespace("system")).isTrue();
            assertThat(StreamResourceValidator.isReservedNamespace("system.audit")).isTrue();
            assertThat(StreamResourceValidator.isReservedNamespace("com.example")).isFalse();
        }
    }

    @Nested
    class StructuredFailureCarriesCount {
        @Test
        void compositeMessageReportsErrorCount() {
            var toml = """
                    [streams.orders]
                    role = "producer"
                    version = "latest"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onFailure(cause -> {
                assertThat(cause.message()).contains("validation failed");
                assertThat(((StreamValidationFailures) cause).failures()).isNotEmpty();
            });
        }

        @Test
        void unusedRecordExists() {
            assertThat(List.of(new StreamResourceValidator.unused())).hasSize(1);
        }
    }

    /// #576: `[streams.X]`/`[streams.X.consumers.Y]` keys that parse cleanly but never reach the
    /// runtime (encryption-key-id, compression, a non-"earliest" auto-offset-reset, and every
    /// per-consumer tuning key) are rejected here instead of silently accepted — see
    /// [StreamResourceValidator#guardInertConfig]. Only non-default values trip the guard; a key
    /// equal to the hardcoded default asserts nothing false.
    @Nested
    class InertConfigRejection {
        @Test
        void encryptionKeyIdIsRejected() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    encryption-key-id = "kms-key-7"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(((StreamValidationFailures) cause).failures())
                                              .extracting(StreamValidationFailure::rule)
                                              .contains(StreamResourceValidator.RULE_INERT_STREAM_CONFIG));
        }

        @Test
        void lz4CompressionIsRejected() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    compression = "lz4"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(((StreamValidationFailures) cause).failures())
                                              .extracting(StreamValidationFailure::rule)
                                              .contains(StreamResourceValidator.RULE_INERT_STREAM_CONFIG));
        }

        @Test
        void zstdCompressionIsRejected() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    compression = "zstd"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(((StreamValidationFailures) cause).failures())
                                              .extracting(StreamValidationFailure::rule)
                                              .contains(StreamResourceValidator.RULE_INERT_STREAM_CONFIG));
        }

        @Test
        void explicitAutoOffsetResetLatestIsRejected() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    auto-offset-reset = "latest"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(((StreamValidationFailures) cause).failures())
                                              .extracting(StreamValidationFailure::rule)
                                              .contains(StreamResourceValidator.RULE_INERT_STREAM_CONFIG));
        }

        @Test
        void nonDefaultConsumerBatchSizeIsRejected() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"

                    [streams.orders.consumers.billing]
                    batch-size = 50
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(((StreamValidationFailures) cause).failures())
                                              .extracting(StreamValidationFailure::rule)
                                              .contains(StreamResourceValidator.RULE_INERT_CONSUMER_CONFIG));
        }

        @Test
        void everyNonDefaultConsumerKeyIsRejected() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"

                    [streams.orders.consumers.billing]
                    batch-size = 50
                    processing = "parallel"
                    on-failure = "skip"
                    checkpoint-interval = "5s"
                    max-retries = 10
                    dead-letter = "orders-dlq"
                    read-preference = "nearest"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(cause -> {
                      var failures = ((StreamValidationFailures) cause).failures();
                      assertThat(failures.stream()
                                         .filter(f -> f.rule().equals(StreamResourceValidator.RULE_INERT_CONSUMER_CONFIG))
                                         .count()).isEqualTo(7);
                  });
        }

        @Test
        void allDefaultsPresentExplicitlyIsAccepted() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    compression = "none"
                    auto-offset-reset = "earliest"

                    [streams.orders.consumers.billing]
                    batch-size = 1
                    processing = "ordered"
                    on-failure = "retry"
                    checkpoint-interval = "1s"
                    max-retries = 3
                    dead-letter = ""
                    read-preference = "governor"
                    """;

            var result = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT);

            result.onFailure(cause -> fail("Expected success: " + cause.message()))
                  .onSuccess(validated -> assertThat(validated.resources()).containsKey("orders"));
        }
    }

    /// #677 descope: the two refusals whose wording changed when these keys were descoped from 1.0
    /// carry a contract, and this pins the CONTRACT rather than the sentence. Three properties: the
    /// refusal NAMES the offending key (an operator has to know what to remove), the two descoped
    /// refusals STATE the 1.0 descope, and they do NOT tell the operator to wait for pending wiring
    /// — which is exactly what `guardInertConfig` said before #677 ("Remove it until #576's runtime
    /// wiring lands"). An exact-string assertion on the whole message was considered and rejected:
    /// it fails on any rewording, so it gets deleted, and the contract leaves with it.
    ///
    /// SCOPE — this bounds every assertion below. These tests pin the VALIDATOR'S OUTPUT, not what
    /// an operator sees. [org.pragmatica.aether.deployment.cluster.BlueprintService] is the only
    /// production caller of [StreamResourceValidator#validate] and ends `.or(List.of())`, so on
    /// failure it discards the `Cause` and publishes an EMPTY bindings entry instead — no deploy
    /// path surfaces these messages today. A consuming slice fails later with
    /// [org.pragmatica.aether.slice.stream.StreamAddressError.UnboundStreamAlias], whose wording is
    /// generic. Pinning this as user-facing would specify a defect rather than probe behaviour.
    @Nested
    class DescopedRefusalWording {
        /// Every key `guardInertConfig` refuses, enumerated from the validator's own branches:
        /// 3 stream-level (`guardStreamConfig`) + 7 per-consumer (`guardConsumerConfig`).
        private static final List<String> ALL_INERT_KEYS = List.of("encryption-key-id",
                                                                   "compression",
                                                                   "auto-offset-reset",
                                                                   "batch-size",
                                                                   "processing",
                                                                   "on-failure",
                                                                   "checkpoint-interval",
                                                                   "max-retries",
                                                                   "dead-letter",
                                                                   "read-preference");

        /// Wording that would tell an operator the feature is merely unimplemented-for-now. The
        /// first two entries are verbatim fragments of what the consumer refusal said before #677,
        /// so reverting that hunk turns `descopedRefusalsPromiseNoPendingWiring` red.
        private static final List<String> PENDING_WIRING_PROMISES = List.of("until #576",
                                                                            "wiring lands",
                                                                            "not yet supported",
                                                                            "coming soon",
                                                                            "will be supported",
                                                                            "in a future release");

        /// One blueprint tripping all ten guards at once, so the enumeration above is checked
        /// against the validator rather than against itself.
        private static final String EVERY_INERT_KEY = """
                [streams.orders]
                version = "1.0.0"
                encryption-key-id = "kms-key-7"
                compression = "lz4"
                auto-offset-reset = "latest"

                [streams.orders.consumers.billing]
                batch-size = 50
                processing = "parallel"
                on-failure = "skip"
                checkpoint-interval = "5s"
                max-retries = 10
                dead-letter = "orders-dlq"
                read-preference = "nearest"
                """;

        @Test
        void everyInertRefusalNamesItsOffendingKey() {
            var messages = inertMessages(EVERY_INERT_KEY);

            assertThat(messages).hasSize(ALL_INERT_KEYS.size());
            ALL_INERT_KEYS.forEach(key -> assertThat(messages).as("an inert refusal naming '%s'", key)
                                                              .anySatisfy(message -> assertThat(message).contains(key)));
        }

        @Test
        void descopedCompressionRefusalStatesNotSupportedInOneZero() {
            assertThat(refusalNaming("compression")).contains("not supported in 1.0");
        }

        @Test
        void descopedConsumerRefusalStatesNotSupportedInOneZero() {
            assertThat(refusalNaming("batch-size")).contains("not supported in 1.0");
        }

        /// The #478 `auto-offset-reset` refusal is permanent, not descoped, so it must not claim a
        /// 1.0 descope — the control that keeps the two assertions above from being satisfied by a
        /// blanket sentence appended to every refusal.
        @Test
        void permanentAutoOffsetResetRefusalClaimsNoDescope() {
            assertThat(refusalNaming("auto-offset-reset")).doesNotContain("not supported in 1.0")
                                                          .contains("#478");
        }

        @Test
        void descopedRefusalsPromiseNoPendingWiring() {
            List.of(refusalNaming("compression"), refusalNaming("batch-size"))
                .forEach(message -> PENDING_WIRING_PROMISES.forEach(promise -> assertThat(message).as("pending-wiring promise '%s'", promise)
                                                                                                  .doesNotContain(promise)));
        }

        private static List<String> inertMessages(String toml) {
            var collected = new ArrayList<String>();

            StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT)
                                   .onSuccessRun(() -> fail("Expected failure"))
                                   .onFailure(cause -> ((StreamValidationFailures) cause).failures()
                                                                                         .stream()
                                                                                         .filter(failure -> isInert(failure.rule()))
                                                                                         .map(StreamValidationFailure::message)
                                                                                         .forEach(collected::add));
            return List.copyOf(collected);
        }

        private static boolean isInert(String rule) {
            return rule.equals(StreamResourceValidator.RULE_INERT_STREAM_CONFIG)
                || rule.equals(StreamResourceValidator.RULE_INERT_CONSUMER_CONFIG);
        }

        private static String refusalNaming(String key) {
            return inertMessages(EVERY_INERT_KEY).stream()
                                                 .filter(message -> message.contains(key))
                                                 .findFirst()
                                                 .orElseGet(() -> fail("No inert refusal names '" + key + "'"));
        }
    }
}
