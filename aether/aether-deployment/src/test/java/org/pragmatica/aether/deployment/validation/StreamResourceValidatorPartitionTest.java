// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// #1336 — [StreamResourceValidator#partition], the deploy path's pass: a per-section rule costs
/// only its own alias and is reported by field and rule; a rule that leaves nothing to bind is the
/// `Result` failure. [StreamResourceValidatorTest] covers [StreamResourceValidator#validate], the
/// all-or-nothing pass, and is left to the PRs editing it.
class StreamResourceValidatorPartitionTest {
    private static final Artifact APP_ARTIFACT = Artifact.artifact("com.example:my-app:1.0.0").unwrap();
    private static final Artifact RESERVED_ARTIFACT = Artifact.artifact("system.framework:audit:1.0.0").unwrap();

    private static final String ONE_VALID_ONE_INVALID = """
            [streams.orders]
            version = "1.0.0"
            partitions = 4

            [streams.audit]
            source = "com.other:audit:1.0.0"
            version = "1.0.0"
            """;

    @Nested
    class PerSectionRules {
        @Test
        void aParserRule_dropsOnlyItsOwnSection() {
            var partition = partition(ONE_VALID_ONE_INVALID, APP_ARTIFACT);

            assertThat(partition.accepted()).containsOnlyKeys("orders");
            assertThat(fieldsAndRules(partition.rejected())).containsExactly("[streams.audit]::version-and-source-mutually-exclusive");
        }

        @Test
        void everyFailingSection_isReported_andEveryPassingOneKept() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"

                    [streams.audit]
                    source = "com.other:audit:1.0.0"
                    version = "1.0.0"

                    [streams.billing]
                    role = "producer"
                    version = "latest"

                    [streams.shipping]
                    version = "2.0.0"
                    """;

            var partition = partition(toml, APP_ARTIFACT);

            assertThat(partition.accepted()).containsOnlyKeys("orders", "shipping");
            assertThat(fieldsAndRules(partition.rejected())).containsExactlyInAnyOrder("[streams.audit]::version-and-source-mutually-exclusive",
                                                                                       "[streams.billing]::producer-version-must-be-exact");
        }

        /// #576's inert keys are per-alias too: the section parses, so the parser keeps it, and the
        /// partition drops it here.
        @Test
        void anInertStreamKey_dropsOnlyThatAlias() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    compression = "lz4"

                    [streams.shipping]
                    version = "1.0.0"
                    """;

            var partition = partition(toml, APP_ARTIFACT);

            assertThat(partition.accepted()).containsOnlyKeys("shipping");
            assertThat(fieldsAndRules(partition.rejected())).containsExactly("[streams.orders]::" + StreamResourceValidator.RULE_INERT_STREAM_CONFIG);
        }

        @Test
        void anInertConsumerKey_dropsTheStreamItSitsUnder_namingTheConsumerSection() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"

                    [streams.orders.consumers.billing]
                    batch-size = 50

                    [streams.shipping]
                    version = "1.0.0"
                    """;

            var partition = partition(toml, APP_ARTIFACT);

            assertThat(partition.accepted()).containsOnlyKeys("shipping");
            assertThat(fieldsAndRules(partition.rejected())).containsExactly("[streams.orders.consumers.billing]::"
                                                                             + StreamResourceValidator.RULE_INERT_CONSUMER_CONFIG);
        }

        @Test
        void aCleanDocument_rejectsNothing() {
            var partition = partition("""
                                      [streams.orders]
                                      version = "1.0.0"
                                      """,
                                      APP_ARTIFACT);

            assertThat(partition.accepted()).containsOnlyKeys("orders");
            assertThat(partition.rejected()).isEmpty();
            assertThat(partition.warnings()).isEmpty();
        }

        @Test
        void noResources_acceptsNothing_rejectsNothing() {
            var partition = StreamResourceValidator.partition(Option.none(), APP_ARTIFACT, Map.of())
                                                   .onFailure(cause -> fail("Expected success: " + cause.message()))
                                                   .unwrap();

            assertThat(partition.accepted()).isEmpty();
            assertThat(partition.rejected()).isEmpty();
        }
    }

    @Nested
    class GatingRules {
        @Test
        void aDocumentThatDoesNotParse_isTheFailure_namingTheRule() {
            var result = StreamResourceValidator.partition(Option.some("[streams.orders\nversion = \"1.0.0\"\n"),
                                                           APP_ARTIFACT,
                                                           Map.of());

            result.onSuccess(_ -> fail("an unparseable document has no section to keep — it must gate"))
                  .onFailure(cause -> assertThat(cause.message()).contains(StreamResourceValidator.RULE_RESOURCES_PARSE));
        }

        @Test
        void aReservedBlueprintNamespace_isTheFailure_whenAStreamIsDeclared() {
            var result = StreamResourceValidator.partition(Option.some(ONE_VALID_ONE_INVALID), RESERVED_ARTIFACT, Map.of());

            result.onSuccess(_ -> fail("the namespace prefixes every owned address — nothing survives it"))
                  .onFailure(cause -> assertThat(cause.message()).contains(StreamResourceValidator.RULE_NAMESPACE_RESERVED)
                                                                 .contains("system.framework:audit:1.0.0")
                                                                 .as("every failure found rides the refusal")
                                                                 .contains("version-and-source-mutually-exclusive"));
        }

        @Test
        void aReservedBlueprintNamespace_isTheFailure_whenTheOnlyStreamIsItselfRejected() {
            var result = StreamResourceValidator.partition(Option.some("""
                                                                       [streams.audit]
                                                                       source = "com.other:audit:1.0.0"
                                                                       version = "1.0.0"
                                                                       """),
                                                           RESERVED_ARTIFACT,
                                                           Map.of());

            result.onSuccess(_ -> fail("a declared stream, even a rejected one, makes the namespace load-bearing"))
                  .onFailure(cause -> assertThat(cause.message()).contains(StreamResourceValidator.RULE_NAMESPACE_RESERVED));
        }

        /// The management-plane error funnel answers `httpStatus()` when the cause declares one and
        /// `500` otherwise (`ProblemResponses.writeProblem`). A gating refusal is the artifact author's
        /// content, not a server fault.
        @Test
        void aGatingRefusal_answers422() {
            StreamResourceValidator.partition(Option.some("[streams.orders\n"), APP_ARTIFACT, Map.of())
                                   .onSuccess(_ -> fail("instrument check: the fixture must gate"))
                                   .onFailure(cause -> assertThat(cause).isInstanceOf(HttpStatusAware.class)
                                                                        .extracting(c -> ((HttpStatusAware) c).httpStatus())
                                                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        void aReservedBlueprintNamespace_isReportedNotGating_whenNoStreamIsDeclared() {
            var partition = StreamResourceValidator.partition(Option.none(), RESERVED_ARTIFACT, Map.of())
                                                   .onFailure(cause -> fail("nothing to bind, nothing to gate: " + cause.message()))
                                                   .unwrap();

            assertThat(partition.accepted()).isEmpty();
            assertThat(fieldsAndRules(partition.rejected())).containsExactly("system.framework:audit:1.0.0::"
                                                                             + StreamResourceValidator.RULE_NAMESPACE_RESERVED);
        }
    }

    private static StreamValidationPartition partition(String toml, Artifact artifact) {
        return StreamResourceValidator.partition(Option.some(toml), artifact, Map.of())
                                      .onFailure(cause -> fail("Expected a partition, got: " + cause.message()))
                                      .unwrap();
    }

    private static List<String> fieldsAndRules(List<StreamValidationFailure> failures) {
        return failures.stream().map(failure -> failure.field() + "::" + failure.rule()).toList();
    }
}
