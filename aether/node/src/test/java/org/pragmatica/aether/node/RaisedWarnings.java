// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// "A warning with code X was raised on this node's aggregator", read from the aggregator's raised history, which a later
/// warning cannot overwrite. The single-slot `lastRaisedOperatorWarning()` answers "which warning was raised last", and a
/// test that waits for a specific code on it loses to any other producer that raises afterwards (#3103, #3403).
final class RaisedWarnings {
    private RaisedWarnings() {}

    /// Every warning raised so far whose code equals `code`.
    static List<ClusterEvent.OperatorWarning> withCode(ClusterEventAggregator aggregator, String code) {
        return matching(aggregator, warning -> code.equals(codeOf(warning)));
    }

    /// Every warning raised so far whose code starts with `prefix`.
    static List<ClusterEvent.OperatorWarning> withCodePrefix(ClusterEventAggregator aggregator, String prefix) {
        return matching(aggregator, warning -> codeOf(warning).startsWith(prefix));
    }

    /// True when a warning with this code and subject was raised.
    static boolean raised(ClusterEventAggregator aggregator, String code, String subject) {
        return !matching(aggregator, warning -> code.equals(codeOf(warning)) && subject.equals(warning.details().get("subject"))).isEmpty();
    }

    /// Waits until a warning with code `code` was raised; the failure names the code and the codes raised instead.
    static ClusterEvent.OperatorWarning awaitCode(ClusterEventAggregator aggregator, String code, Duration bound) {
        await().atMost(bound)
               .untilAsserted(() -> assertThat(codesRaised(aggregator)).as("a warning with code '%s' was raised", code)
                                                                      .contains(code));
        return withCode(aggregator, code).getFirst();
    }

    /// Waits until a warning whose code starts with `prefix` was raised; the failure names the prefix and the codes raised.
    static ClusterEvent.OperatorWarning awaitCodePrefix(ClusterEventAggregator aggregator, String prefix, Duration bound) {
        await().atMost(bound)
               .untilAsserted(() -> assertThat(withCodePrefix(aggregator, prefix)).as("a warning with a code starting with '%s' was raised; raised codes: %s",
                                                                                      prefix,
                                                                                      codesRaised(aggregator))
                                                                                   .isNotEmpty());
        return withCodePrefix(aggregator, prefix).getFirst();
    }

    static List<String> codesRaised(ClusterEventAggregator aggregator) {
        return matching(aggregator, _ -> true).stream()
                                              .map(RaisedWarnings::codeOf)
                                              .toList();
    }

    private static List<ClusterEvent.OperatorWarning> matching(ClusterEventAggregator aggregator, Predicate<ClusterEvent.OperatorWarning> filter) {
        return aggregator.raisedOperatorWarnings()
                         .stream()
                         .filter(ClusterEvent.OperatorWarning.class::isInstance)
                         .map(ClusterEvent.OperatorWarning.class::cast)
                         .filter(filter)
                         .toList();
    }

    private static String codeOf(ClusterEvent.OperatorWarning warning) {
        return String.valueOf(warning.details().get("code"));
    }
}
