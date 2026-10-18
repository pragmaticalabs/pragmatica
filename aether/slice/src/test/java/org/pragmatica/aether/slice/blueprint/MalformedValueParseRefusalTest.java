// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.Map;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReplicationFactors;

import static org.assertj.core.api.Assertions.assertThat;


/// #1455 at the blueprint level: `StreamConfigParser` and `BlueprintParser` read through
/// `TomlDocument`'s typed getters, whose `.or(default)` applied the default to a mistyped value
/// (`batch-size = "many"`, `max_latency_ms = "fast"`). Each parse must refuse naming key and value.
/// The `partitions` cases pin the stream declaration's own refusal (#1336), which this fix does not
/// touch: they stay green with the type-mismatch gate removed.
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
        assertRefused(StreamConfigParser.parseResources(STREAM_MALFORMED, ReplicationFactors.BUILT_IN), "partitions", "8x");
        assertThat(StreamConfigParser.parseResources(STREAM_WELL_FORMED, ReplicationFactors.BUILT_IN).isSuccess()).isTrue();
    }

    @Test
    void streamConfigParser_parseResourcesAggregating_malformedPartitions_refuses() {
        assertRefused(StreamConfigParser.parseResourcesAggregating(STREAM_MALFORMED, Map.of(), ReplicationFactors.BUILT_IN), "partitions", "8x");
        assertThat(StreamConfigParser.parseResourcesAggregating(STREAM_WELL_FORMED, Map.of(), ReplicationFactors.BUILT_IN).isSuccess()).isTrue();
    }

    /// The partitioned path keeps a section the stream declaration refuses (#1336) as a per-section
    /// rejection that names alias, key and value; it is not a whole-document refusal.
    @Test
    void streamConfigParser_parseResourcesPartitioned_malformedPartitions_rejectsTheSection() {
        StreamConfigParser.parseResourcesPartitioned(STREAM_MALFORMED, Map.of(), ReplicationFactors.BUILT_IN)
                          .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
                          .onSuccess(partitioned -> {
                              assertThat(partitioned.accepted()).isEmpty();
                              assertThat(partitioned.rejected()).singleElement()
                                                                .satisfies(refused -> assertThat(refused.message()).contains("partitions")
                                                                                                                   .contains("8x"));
                          });
        assertThat(StreamConfigParser.parseResourcesPartitioned(STREAM_WELL_FORMED, Map.of(), ReplicationFactors.BUILT_IN).isSuccess())
            .isTrue();
    }

    /// Consumer keys are read through the document's typed reads, which default a wrong-typed value;
    /// the document records the read and the parse refuses naming key and value (#1455).
    @Test
    void streamConfigParser_parseConsumers_malformedBatchSize_refusesNamingKeyAndValue() {
        assertRefused(StreamConfigParser.parseConsumers("""
            [streams.orders.consumers.billing]
            batch-size = "many"
            """, "orders"), "batch-size", "many");
        assertThat(StreamConfigParser.parseConsumers("""
            [streams.orders.consumers.billing]
            batch-size = 10
            """, "orders").isSuccess()).isTrue();
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

    private static void assertRefusedWithAllowed(Result<?> result, String key, String raw, String allowed) {
        assertRefused(result, key, raw);
        result.onFailure(cause -> assertThat(cause.message()).contains(allowed));
    }

    /// Invalid VALUES are refused like invalid types (#1455): an unknown strategy used to read as
    /// ROLLING, an unknown override policy as STRENGTHEN_ONLY, an unknown consumer token as its default.
    @Test
    void blueprintParser_unknownStrategy_refusesNamingKeyValueAndAllowed() {
        var blueprint = """
            id = "org.example:minimal:1.0.0"

            [deployment]
            strategy = "%s"

            [[slices]]
            artifact = "org.example:service:1.0.0"
            """;

        assertRefusedWithAllowed(BlueprintParser.parse(blueprint.formatted("bluegreen")),
                                 "deployment.strategy",
                                 "bluegreen",
                                 "one of: rolling, canary, blue-green");
        assertThat(BlueprintParser.parse(blueprint.formatted("blue-green")).isSuccess()).isTrue();
        assertThat(BlueprintParser.parse(blueprint.formatted("rolling")).isSuccess()).isTrue();
    }

    @Test
    void blueprintParser_unknownOverridePolicy_refusesNamingKeyValueAndAllowed() {
        var blueprint = """
            id = "org.example:minimal:1.0.0"

            [security]
            override_policy = "%s"

            [[slices]]
            artifact = "org.example:service:1.0.0"
            """;

        assertRefusedWithAllowed(BlueprintParser.parse(blueprint.formatted("ful")),
                                 "security.override_policy",
                                 "ful",
                                 "one of: strengthen_only, full, none");
        assertThat(BlueprintParser.parse(blueprint.formatted("full")).isSuccess()).isTrue();
    }

    @Test
    void streamConfigParser_unknownConsumerTokens_areRefusedNamingKeyValueAndAllowed() {
        var consumer = """
            [streams.orders.consumers.billing]
            %s = "%s"
            """;

        assertRefusedWithAllowed(StreamConfigParser.parseConsumers(consumer.formatted("processing", "serial"), "orders"),
                                 "processing", "serial", "one of: ordered, parallel");
        assertRefusedWithAllowed(StreamConfigParser.parseConsumers(consumer.formatted("on-failure", "ignore"), "orders"),
                                 "on-failure", "ignore", "one of: retry, skip, stall");
        assertRefusedWithAllowed(StreamConfigParser.parseConsumers(consumer.formatted("read-preference", "closest"), "orders"),
                                 "read-preference", "closest", "governor, nearest, any-replica, linearizable");
        assertThat(StreamConfigParser.parseConsumers(consumer.formatted("processing", "parallel"), "orders").isSuccess()).isTrue();
        assertThat(StreamConfigParser.parseConsumers(consumer.formatted("on-failure", "skip"), "orders").isSuccess()).isTrue();
        assertThat(StreamConfigParser.parseConsumers(consumer.formatted("read-preference", "nearest"), "orders").isSuccess()).isTrue();
    }
}
