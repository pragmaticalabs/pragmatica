// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record NodeConfig(String heap,
                         String gc,
                         TimeSpan metricsInterval,
                         TimeSpan reconciliation,
                         Option<ResourcesConfig> resources) {
    public static final String DEFAULT_GC = "zgc";
    public static final TimeSpan DEFAULT_METRICS_INTERVAL = timeSpan(1).seconds();
    public static final TimeSpan DEFAULT_RECONCILIATION = timeSpan(5).seconds();

    public static Result<NodeConfig> nodeConfig(String heap,
                                                String gc,
                                                TimeSpan metricsInterval,
                                                TimeSpan reconciliation,
                                                Option<ResourcesConfig> resources) {
        return success(new NodeConfig(heap, gc, metricsInterval, reconciliation, resources));
    }

    public static NodeConfig nodeConfig(Environment env) {
        return nodeConfig(env.defaultHeap(),
                          DEFAULT_GC,
                          DEFAULT_METRICS_INTERVAL,
                          DEFAULT_RECONCILIATION,
                          resourcesFor(env)).unwrap();
    }

    public NodeConfig withHeap(String heap) {
        return nodeConfig(heap, gc, metricsInterval, reconciliation, resources).unwrap();
    }

    public NodeConfig withGc(String gc) {
        return nodeConfig(heap, gc, metricsInterval, reconciliation, resources).unwrap();
    }

    public NodeConfig withResources(Option<ResourcesConfig> resources) {
        return nodeConfig(heap, gc, metricsInterval, reconciliation, resources).unwrap();
    }

    public String javaOpts() {
        var gcOpt = switch (gc.toLowerCase()) {
            case "zgc" -> "-XX:+UseZGC";
            case "g1" -> "-XX:+UseG1GC";
            default -> "-XX:+UseZGC";
        };

        return "-Xmx" + heap + " " + gcOpt;
    }

    private static Option<ResourcesConfig> resourcesFor(Environment env) {
        return env == Environment.KUBERNETES
               ? some(ResourcesConfig.resourcesConfig())
               : none();
    }
}
