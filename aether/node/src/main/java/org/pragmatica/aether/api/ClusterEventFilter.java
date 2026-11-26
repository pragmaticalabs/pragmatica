// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The `code` and `severity` filters of `GET /api/v1/events` and `aether events` (#1631).
///
/// `code` keeps the events whose `details.code` equals it. The match is exact and case-sensitive, because a code is a
/// stable kebab identifier from the `OperatorWarningCode` catalogue (`swim-kill-gate-held`); an event without a
/// `details.code`, and a code no event carries, match nothing, so an unknown code answers an empty list and not an
/// error. `severity` is a MINIMUM: it keeps the events at that level or above (`WARNING` keeps `WARNING` and
/// `CRITICAL`). It is read case-insensitively, and any value other than `INFO`, `WARNING` or `CRITICAL` (`UNKNOWN`
/// included, which is a wire sentinel for a level this node cannot name, not a level an operator can ask for) is a
/// caller's mistake that answers 400. An event whose own severity is `UNKNOWN` passes every severity filter: the node
/// cannot say it is below the minimum, and a filter must not hide what it cannot rank.
///
/// Both filters combine with AND, and with the `sinceEpoch`/`sinceSeq` cursor, which still selects the events first.
public record ClusterEventFilter(Option<String> code, Option<Severity> minimumSeverity) {
    static final String CODE_KEY = "code";

    private static final List<Severity> SELECTABLE = Stream.of(Severity.values())
                                                           .filter(level -> level != Severity.UNKNOWN)
                                                           .toList();

    private static final Cause INVALID_SEVERITY = new ManagementServerError.InvalidRequest("Invalid 'severity' query parameter: expected one of " + SELECTABLE.stream()
                                                                                                                                                              .map(Severity::name)
                                                                                                                                                              .collect(Collectors.joining(", "))
                                                                                          + " (case-insensitive)");

    /// The filter for the raw `code` and `severity` query parameters; fails with a 400 cause when `severity` names no level.
    public static Result<ClusterEventFilter> clusterEventFilter(Option<String> code, Option<String> severity) {
        return parseSeverity(severity).map(minimum -> new ClusterEventFilter(code, minimum));
    }

    /// The events this filter keeps, in their original order.
    public List<ClusterEvent> apply(List<ClusterEvent> events) {
        return events.stream()
                     .filter(this::matches)
                     .toList();
    }

    boolean matches(ClusterEvent event) {
        return codeMatches(event) && severityMatches(event);
    }

    private boolean codeMatches(ClusterEvent event) {
        return code.map(wanted -> wanted.equals(event.details().get(CODE_KEY)))
                   .or(true);
    }

    private boolean severityMatches(ClusterEvent event) {
        return minimumSeverity.map(minimum -> event.severity()
                                                   .compareTo(minimum) >= 0)
                              .or(true);
    }

    private static Result<Option<Severity>> parseSeverity(Option<String> raw) {
        return raw.fold(() -> Result.success(Option.none()),
                        value -> parseLevel(value).map(Option::some));
    }

    private static Result<Severity> parseLevel(String raw) {
        return Option.from(SELECTABLE.stream().filter(level -> level.name()
                                                                    .equalsIgnoreCase(raw.trim())).findFirst()).toResult(INVALID_SEVERITY);
    }
}
