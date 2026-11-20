// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #3333: the `[truncation]` refusals at deploy validation. Each new declaration refusal of the stream parser reports its
/// own rule id, and T-156's redeploy arms (section 2.5) wait behind the T-146 gate until S3.
class StreamResourceValidatorTruncationPolicyTest {
    private static final Artifact APP_ARTIFACT = Artifact.artifact("com.example:my-app:1.0.0").unwrap();
    private static final String ENGINE_KEY = "com.example.my-app:orders:1.0.0";
    private static final String FIELD = "[streams.orders]";
    private static final String ORDERS = "[streams.orders]\nversion = \"1.0.0\"\npartitions = 4\n";
    private static final String UNTIL_CONSUMED = "[streams.orders.truncation]\npolicy = \"until_consumed\"\n";
    private static final String WINDOW = "[streams.orders.truncation]\npolicy = \"window\"\n";

    /// R-4: each refusal added by this step is reported under its own rule, never the generic section rule.
    @Nested
    class DeclarationRuleIds {
        @Test
        void untilConsumedWithoutMaxBytes_reportsUntilConsumedUncapped() {
            assertThat(rules(ORDERS + UNTIL_CONSUMED + "[streams.orders.retention]\nmax_age = \"1h\"\n"))
                    .containsExactly(StreamResourceValidator.RULE_UNTIL_CONSUMED_UNCAPPED);
        }

        @Test
        void untilConsumedWithModeAll_reportsUntilConsumedModeAll() {
            assertThat(rules(ORDERS + UNTIL_CONSUMED + "[streams.orders.retention]\nmax_bytes = \"2GB\"\nmode = \"all\"\n"))
                    .containsExactly(StreamResourceValidator.RULE_UNTIL_CONSUMED_MODE_ALL);
        }

        @Test
        void untilConsumedOnAnEntityStream_reportsUntilConsumedUnsupported() {
            var toml = "[\"streams.entity:orders\"]\nversion = \"1.0.0\"\n"
                       + "[\"streams.entity:orders.truncation\"]\npolicy = \"until_consumed\"\n";

            assertThat(rules(toml)).containsExactly(StreamResourceValidator.RULE_UNTIL_CONSUMED_UNSUPPORTED);
        }

        @Test
        void groupIdleExpiryUnderWindow_reportsGroupIdleExpiryWithoutUntilConsumed() {
            assertThat(rules(ORDERS + WINDOW + "group_idle_expiry = \"1h\"\n"))
                    .containsExactly(StreamResourceValidator.RULE_GROUP_IDLE_EXPIRY_WITHOUT_UNTIL_CONSUMED);
        }

        @Test
        void bothDeclarationModels_reportConflictingDeclarationModels() {
            assertThat(rules(ORDERS + "retention_value = \"5\"\n[streams.orders.retention]\nmax_bytes = \"2GB\"\n"))
                    .containsExactly(StreamResourceValidator.RULE_CONFLICTING_DECLARATION_MODELS);
        }
    }

    /// T-156 (W arms): `[truncation] policy` is immutable on a running stream, both directions. SRV parses before it
    /// compares, so until S3 a redeploy declaring `until_consumed` is refused by the T-146 gate, not by the immutable rule.
    @Nested
    class TruncationPolicyImmutable {
        /// Tripwire (asserts CURRENT behaviour, enabled): a running `window` stream redeployed with `until_consumed` is
        /// refused `not-yet-available` and not accepted, so no command changes its committed row.
        @Test
        void windowToUntilConsumed_isRefusedByTheGate_untilS3() {
            var message = "S3 landed: enable T-156's arms";

            assertThat(refusals(ORDERS + UNTIL_CONSUMED, committedWith(TruncationPolicy.WINDOW))).as(message)
                                                                                               .containsExactly(FIELD + "::" + StreamResourceValidator.RULE_NOT_YET_AVAILABLE);
            assertThat(accepted(ORDERS + UNTIL_CONSUMED, committedWith(TruncationPolicy.WINDOW))).as(message)
                                                                                              .doesNotContain("orders");
        }

        @Disabled("S3: the T-146 gate refuses until_consumed before the committed row is compared; enable when S3 lifts it")
        @Test
        void windowToUntilConsumed_isRefusedImmutable() {
            assertThat(refusals(ORDERS + UNTIL_CONSUMED, committedWith(TruncationPolicy.WINDOW)))
                    .containsExactly(FIELD + "::" + StreamResourceValidator.RULE_TRUNCATION_POLICY_IMMUTABLE);
            assertThat(accepted(ORDERS + UNTIL_CONSUMED, committedWith(TruncationPolicy.WINDOW))).doesNotContain("orders");
        }

        @Disabled("S3: no deploy can commit an until_consumed stream while the T-146 gate refuses it; enable when S3 lifts it")
        @Test
        void untilConsumedToWindow_isRefusedImmutable() {
            assertThat(refusals(ORDERS + WINDOW, committedWith(TruncationPolicy.UNTIL_CONSUMED)))
                    .containsExactly(FIELD + "::" + StreamResourceValidator.RULE_TRUNCATION_POLICY_IMMUTABLE);
            assertThat(accepted(ORDERS + WINDOW, committedWith(TruncationPolicy.UNTIL_CONSUMED))).doesNotContain("orders");
        }

        /// A stream committed before the declaration existed carries no stored policy; it is a `window` stream, so a
        /// `window` redeploy is accepted and an `until_consumed` one is still not.
        @Test
        void aRowWithNoDeclaration_isAWindowStream() {
            Function<String, Option<StreamConfig>> legacy = key -> ENGINE_KEY.equals(key)
                                                                   ? Option.some(StreamConfig.DEFAULT.withName(ENGINE_KEY)
                                                                                                     .withPartitions(4))
                                                                   : Option.none();

            assertThat(refusals(ORDERS + WINDOW, legacy)).isEmpty();
        }

        /// Control: an unchanged `window` redeploy is accepted, so the tripwire's refusal is the gate's, not the row's.
        @Test
        void windowToWindow_isAccepted() {
            assertThat(refusals(ORDERS + WINDOW, committedWith(TruncationPolicy.WINDOW))).isEmpty();
            assertThat(accepted(ORDERS + WINDOW, committedWith(TruncationPolicy.WINDOW))).contains("orders");
        }
    }

    private static List<String> rules(String toml) {
        return StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT)
                                      .fold(cause -> ((StreamValidationFailures) cause).failures()
                                                                                        .stream()
                                                                                        .map(StreamValidationFailure::rule)
                                                                                        .toList(),
                                            _ -> fail("expected a refusal for: " + toml));
    }

    private static List<String> refusals(String toml, Function<String, Option<StreamConfig>> committedRows) {
        return partitioned(toml, committedRows).fold(cause -> ((StreamValidationFailures) cause).failures(),
                                                     partition -> partition.rejected())
                                               .stream()
                                               .map(failure -> failure.field() + "::" + failure.rule())
                                               .toList();
    }

    private static List<String> accepted(String toml, Function<String, Option<StreamConfig>> committedRows) {
        return partitioned(toml, committedRows).fold(_ -> List.of(),
                                                     partition -> List.copyOf(partition.accepted().keySet()));
    }

    private static Result<StreamValidationPartition> partitioned(String toml,
                                                                 Function<String, Option<StreamConfig>> committedRows) {
        return StreamResourceValidator.partition(Option.some(toml),
                                                 APP_ARTIFACT,
                                                 Map.of(),
                                                 ReplicationContext.BUILT_IN,
                                                 committedRows);
    }

    private static Function<String, Option<StreamConfig>> committedWith(TruncationPolicy policy) {
        var truncation = TruncationDeclaration.truncationDeclaration(Option.some(policy), Option.none());
        var declaration = StreamDeclaration.streamDeclaration(Option.none(), Option.some(truncation), Option.none(), Option.none());
        var row = StreamConfig.DEFAULT.withName(ENGINE_KEY)
                                      .withPartitions(4)
                                      .withDeclaration(declaration);

        return key -> ENGINE_KEY.equals(key)
                      ? Option.some(row)
                      : Option.none();
    }
}
