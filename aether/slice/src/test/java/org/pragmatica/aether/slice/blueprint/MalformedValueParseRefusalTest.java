// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.Map;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1098 at the blueprint level: `StreamConfigParser` and `BlueprintParser` read through
/// `TomlDocument`'s typed getters, whose `.or(default)` applied the default to a mistyped value
/// (`partitions = "8x"`, `max_latency_ms = "fast"`). Each parse must refuse naming key and value.
class MalformedValueParseRefusalTest {
    private static void assertRefused(Result<?> result, String key, String raw) {
        assertThat(result.isFailure()).describedAs("%s = \"%s\" must refuse the parse, parsed %s", key, raw, result)
                                      .isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains(key)
                                                             .contains(raw));
    }

    private static final String STREAM_MALFORMED = """
        [streams.orders]
        version = "1.0.0"
        partitions = "8x"
        """;

    private static final String STREAM_WELL_FORMED = """
        [streams.orders]
        version = "1.0.0"
        partitions = 8
        """;

    @Test
    void streamConfigParser_parseResources_malformedPartitions_refusesNamingKeyAndValue() {
        assertRefused(StreamConfigParser.parseResources(STREAM_MALFORMED), "streams.orders.partitions", "8x");
        assertThat(StreamConfigParser.parseResources(STREAM_WELL_FORMED).isSuccess()).isTrue();
    }

    @Test
    void streamConfigParser_parseResourcesAggregating_malformedPartitions_refuses() {
        assertRefused(StreamConfigParser.parseResourcesAggregating(STREAM_MALFORMED, Map.of()), "streams.orders.partitions", "8x");
        assertThat(StreamConfigParser.parseResourcesAggregating(STREAM_WELL_FORMED, Map.of()).isSuccess()).isTrue();
    }

    @Test
    void streamConfigParser_parseResourcesPartitioned_malformedPartitions_refuses() {
        assertRefused(StreamConfigParser.parseResourcesPartitioned(STREAM_MALFORMED, Map.of()), "streams.orders.partitions", "8x");
        assertThat(StreamConfigParser.parseResourcesPartitioned(STREAM_WELL_FORMED, Map.of()).isSuccess()).isTrue();
    }

    @Test
    void streamConfigParser_parse_malformedPartitions_refuses() {
        assertRefused(StreamConfigParser.parse(STREAM_MALFORMED), "streams.orders.partitions", "8x");
        assertThat(StreamConfigParser.parse(STREAM_WELL_FORMED).isSuccess()).isTrue();
    }

    @Test
    void blueprintParser_malformedDeploymentLatency_refusesNamingKeyAndValue() {
        assertRefused(BlueprintParser.parse("""
            id = "org.example:minimal:1.0.0"

            [deployment]
            strategy = "canary"
            max_latency_ms = "fast"

            [[slices]]
            artifact = "org.example:service:1.0.0"
            """), "deployment.max_latency_ms", "fast");
    }

    @Test
    void blueprintParser_wellFormedDeploymentLatency_parses() {
        assertThat(BlueprintParser.parse("""
            id = "org.example:minimal:1.0.0"

            [deployment]
            strategy = "canary"
            max_latency_ms = 250

            [[slices]]
            artifact = "org.example:service:1.0.0"
            """).isSuccess()).isTrue();
    }
}
