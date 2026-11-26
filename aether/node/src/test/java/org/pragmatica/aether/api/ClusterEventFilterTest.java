// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;


/// #1631: the `code` / `severity` filter of `GET /api/v1/events`. The route and CLI tests prove the wiring; this
/// pins the matching rules themselves, one case per rule the docs state.
class ClusterEventFilterTest {
    private static final ClusterEvent SWIM_WARNING = warning("swim-kill-gate-held", Severity.WARNING);
    private static final ClusterEvent FENCE_CRITICAL = warning("core-absence-fence", Severity.CRITICAL);
    private static final ClusterEvent REPAIRED_INFO = warning("stream-consumer-state-repaired", Severity.INFO);
    private static final ClusterEvent JOINED = new ClusterEvent.NodeJoined(HlcTimestamp.ZERO, Severity.INFO, "joined", Map.of());
    private static final ClusterEvent FUTURE_LEVEL = warning("swim-kill-gate-held", Severity.UNKNOWN);

    private static final List<ClusterEvent> ALL = List.of(SWIM_WARNING, FENCE_CRITICAL, REPAIRED_INFO, JOINED, FUTURE_LEVEL);

    private static ClusterEvent warning(String code, Severity severity) {
        return new ClusterEvent.OperatorWarning(HlcTimestamp.ZERO, severity, code, Map.of("code", code));
    }

    private static List<ClusterEvent> filtered(String code, String severity) {
        return ClusterEventFilter.clusterEventFilter(Option.option(code), Option.option(severity))
                                 .map(filter -> filter.apply(ALL))
                                 .unwrap();
    }

    @Test
    void apply_keepsEverything_whenNeitherFilterIsGiven() {
        assertThat(filtered(null, null)).containsExactlyElementsOf(ALL);
    }

    @Test
    void apply_keepsOnlyTheEventsCarryingTheCode_whenCodeIsGiven() {
        assertThat(filtered("swim-kill-gate-held", null)).containsExactly(SWIM_WARNING, FUTURE_LEVEL);
    }

    @Test
    void apply_answersEmpty_whenNoEventCarriesTheCode() {
        assertThat(filtered("no-such-code", null)).isEmpty();
    }

    @Test
    void apply_matchesTheCodeExactly_notCaseInsensitively() {
        assertThat(filtered("SWIM-KILL-GATE-HELD", null)).isEmpty();
        assertThat(filtered("swim-kill", null)).isEmpty();
    }

    @Test
    void apply_dropsEventsWithoutACode_whenCodeIsGiven() {
        assertThat(filtered("swim-kill-gate-held", null)).doesNotContain(JOINED);
    }

    @Test
    void apply_treatsSeverityAsAMinimum_warningKeepsWarningAndCritical() {
        assertThat(filtered(null, "WARNING")).containsExactly(SWIM_WARNING, FENCE_CRITICAL, FUTURE_LEVEL);
    }

    @Test
    void apply_keepsEverythingFromInfo_andOnlyCriticalFromCritical() {
        assertThat(filtered(null, "INFO")).containsExactlyElementsOf(ALL);
        assertThat(filtered(null, "CRITICAL")).containsExactly(FENCE_CRITICAL, FUTURE_LEVEL);
    }

    @Test
    void apply_readsSeverityCaseInsensitively_andIgnoresSurroundingBlanks() {
        assertThat(filtered(null, "warning")).isEqualTo(filtered(null, "WARNING"));
        assertThat(filtered(null, " Warning ")).isEqualTo(filtered(null, "WARNING"));
    }

    @Test
    void apply_letsAnEventOfUnknownSeverityThrough_whateverTheMinimum() {
        assertThat(filtered(null, "CRITICAL")).contains(FUTURE_LEVEL);
    }

    @Test
    void apply_combinesCodeAndSeverityWithAnd() {
        assertThat(filtered("swim-kill-gate-held", "CRITICAL")).containsExactly(FUTURE_LEVEL);
        assertThat(filtered("core-absence-fence", "WARNING")).containsExactly(FENCE_CRITICAL);
        assertThat(filtered("stream-consumer-state-repaired", "WARNING")).isEmpty();
    }

    @Test
    void apply_keepsTheOriginalOrder() {
        assertThat(filtered(null, "WARNING")).containsExactlyElementsOf(ALL.stream().filter(e -> e != REPAIRED_INFO && e != JOINED).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"loud", "", " ", "UNKNOWN", "unknown", "warn", "WARNING,CRITICAL"})
    void clusterEventFilter_failsWith400Cause_whenSeverityNamesNoSelectableLevel(String severity) {
        var cause = ClusterEventFilter.clusterEventFilter(Option.none(), Option.option(severity))
                                      .fold(failure -> failure, accepted -> Causes.cause("accepted: " + accepted));

        assertThat(cause).isInstanceOf(ManagementServerError.InvalidRequest.class);
        assertThat(((ManagementServerError) cause).httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cause.message()).contains("severity").contains("INFO, WARNING, CRITICAL");
    }

    @Test
    void clusterEventFilter_acceptsEveryLevelButTheSentinel() {
        for (var level : Severity.values()) {
            Result<ClusterEventFilter> result = ClusterEventFilter.clusterEventFilter(Option.none(), Option.some(level.name()));

            assertThat(result.isSuccess()).isEqualTo(level != Severity.UNKNOWN);
        }
    }
}
