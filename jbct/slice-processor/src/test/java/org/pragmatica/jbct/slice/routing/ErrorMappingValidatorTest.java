// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.slice.routing.ErrorMappingValidator.IssueKind.DEAD_PATTERN;
import static org.pragmatica.jbct.slice.routing.ErrorMappingValidator.IssueKind.DEAD_REFERENCE;
import static org.pragmatica.jbct.slice.routing.ErrorMappingValidator.IssueKind.UNMAPPED_CAUSE;

/// Unit tests for the pure totality + dead-mapping check (#385). Mirrors the pure-validator style
/// of [VersionSchemaValidatorTest]/[ErrorTypeMatcherTest]: descriptors and configuration are built
/// directly, no compile-fail harness needed.
class ErrorMappingValidatorTest {

    private static final String PATH = "com/acme/seat/routes.toml";

    /// The sealed interface itself - a non-leaf catch-all, never returned as a value.
    private static final ErrorMappingValidator.CauseDescriptor SEAT_ERROR =
        new ErrorMappingValidator.CauseDescriptor("SeatError", "com.acme.seat.SeatError", false);

    private static ErrorMappingValidator.CauseDescriptor leaf(String simpleName) {
        return new ErrorMappingValidator.CauseDescriptor(simpleName, "com.acme.seat.SeatError." + simpleName, true);
    }

    private static ErrorPatternConfig config(Map<Integer, List<String>> statusPatterns) {
        return ErrorPatternConfig.errorPatternConfig(500, statusPatterns, Map.of());
    }

    private static ErrorPatternConfig config(Map<Integer, List<String>> statusPatterns,
                                             Map<String, Integer> explicit) {
        return ErrorPatternConfig.errorPatternConfig(500, statusPatterns, explicit);
    }

    private static List<String> messagesOf(List<ErrorMappingValidator.Issue> issues,
                                           ErrorMappingValidator.IssueKind kind) {
        return issues.stream()
                     .filter(issue -> issue.kind() == kind)
                     .map(ErrorMappingValidator.Issue::message)
                     .toList();
    }

    @Nested
    class Totality {

        @Test
        void validate_flagsUnmappedLeafCauses() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"), leaf("InvalidSeat"), leaf("General"));
            var config = config(Map.of(404, List.of("SeatError.SeatNotFound")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            var unmapped = messagesOf(issues, UNMAPPED_CAUSE);
            assertThat(unmapped).hasSize(2);
            assertThat(unmapped).anyMatch(m -> m.contains("com.acme.seat.SeatError.InvalidSeat") && m.contains(PATH));
            assertThat(unmapped).anyMatch(m -> m.contains("com.acme.seat.SeatError.General"));
        }

        @Test
        void validate_doesNotFlagNonLeafInterface() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"));
            var config = config(Map.of());

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            // Only the leaf is flagged; the sealed interface (non-leaf) is exempt from totality.
            var unmapped = messagesOf(issues, UNMAPPED_CAUSE);
            assertThat(unmapped).hasSize(1);
            assertThat(unmapped.getFirst()).contains("SeatNotFound");
        }

        @Test
        void validate_flagsUnmappedEnumLeaf() {
            var causes = List.of(SEAT_ERROR, leaf("General"));
            var config = config(Map.of(404, List.of("*NotFound*")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(messagesOf(issues, UNMAPPED_CAUSE)).hasSize(1);
            assertThat(messagesOf(issues, UNMAPPED_CAUSE).getFirst()).contains("General");
        }

        @Test
        void validate_passesWhenAllLeavesMapped() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"), leaf("InvalidSeat"));
            var config = config(Map.of(404, List.of("*NotFound*"), 400, List.of("*Invalid*")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(issues).isEmpty();
        }
    }

    @Nested
    class ExactReferences {

        @Test
        void validate_treatsLeafMappedByExactReferenceAsMapped() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"));
            var config = config(Map.of(404, List.of("SeatError.SeatNotFound")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(issues).isEmpty();
        }

        @Test
        void validate_treatsLeafMappedByFullyQualifiedReferenceAsMapped() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"));
            var config = config(Map.of(404, List.of("com.acme.seat.SeatError.SeatNotFound")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(issues).isEmpty();
        }

        @Test
        void validate_flagsRenamedCauseAsDeadReference() {
            // All real leaves mapped; the reference to a removed/renamed Cause is dead.
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"), leaf("InvalidSeat"), leaf("General"));
            var config = config(Map.of(400, List.of("SeatError.SeatNotFound",
                                                    "SeatError.InvalidSeat",
                                                    "SeatError.General",
                                                    "SeatError.SeatRemoved")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(messagesOf(issues, UNMAPPED_CAUSE)).isEmpty();
            var dead = messagesOf(issues, DEAD_REFERENCE);
            assertThat(dead).hasSize(1);
            assertThat(dead.getFirst()).contains("SeatError.SeatRemoved");
            assertThat(dead.getFirst()).contains(PATH);
        }
    }

    @Nested
    class DeadGlobs {

        @Test
        void validate_flagsGlobMatchingNoCause() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"), leaf("InvalidSeat"), leaf("General"));
            var config = config(Map.of(400, List.of("*Seat*", "*General*", "*PriceNotFound*")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(messagesOf(issues, UNMAPPED_CAUSE)).isEmpty();
            var dead = messagesOf(issues, DEAD_PATTERN);
            assertThat(dead).hasSize(1);
            assertThat(dead.getFirst()).contains("*PriceNotFound*");
            assertThat(dead.getFirst()).contains(PATH);
        }

        @Test
        void validate_doesNotFlagGlobMatchingInterfaceOnly() {
            // A catch-all glob matching only the sealed interface is legitimately "used".
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"));
            var config = config(Map.of(404, List.of("*NotFound*"), 500, List.of("*Error")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(messagesOf(issues, DEAD_PATTERN)).isEmpty();
        }
    }

    @Nested
    class BackwardCompatGlobs {

        @Test
        void validate_existingGlobStillMapsLeafAndIsNotDead() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"));
            var config = config(Map.of(404, List.of("*NotFound*")));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(issues).isEmpty();
        }
    }

    @Nested
    class ExplicitMappings {

        @Test
        void validate_flagsDeadExplicitMapping() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"), leaf("InvalidSeat"), leaf("General"));
            var config = config(Map.of(400, List.of("*Seat*", "*General*")),
                                Map.of("Ghost", 410));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(messagesOf(issues, UNMAPPED_CAUSE)).isEmpty();
            var dead = messagesOf(issues, DEAD_REFERENCE);
            assertThat(dead).hasSize(1);
            assertThat(dead.getFirst()).contains("Ghost");
        }

        @Test
        void validate_explicitMappingCoversLeaf() {
            var causes = List.of(SEAT_ERROR, leaf("SeatNotFound"));
            var config = config(Map.of(), Map.of("SeatNotFound", 404));

            var issues = ErrorMappingValidator.validate(causes, config, PATH);

            assertThat(issues).isEmpty();
        }
    }
}
