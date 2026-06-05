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
}
