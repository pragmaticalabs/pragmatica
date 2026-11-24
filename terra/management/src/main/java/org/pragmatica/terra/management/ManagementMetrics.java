// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import io.micrometer.core.instrument.MeterRegistry;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;


/// The metrics the `metrics` and `prometheus` endpoints read (#3651): the shape of Terra's metrics provider (#3650),
/// so wiring one is `managementMetrics(metrics.registry(), metrics::prometheusScrape)`. An empty scrape means no
/// Prometheus registry is present; the `prometheus` endpoint then answers 404.
public record ManagementMetrics(MeterRegistry registry, Fn0<Option<String>> prometheusScrape) {
    /// Prometheus text exposition format 0.0.4, the type Spring Boot's `prometheus` endpoint answers with by default.
    public static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    public static ManagementMetrics managementMetrics(MeterRegistry registry, Fn0<Option<String>> prometheusScrape) {
        return new ManagementMetrics(registry, prometheusScrape);
    }
}
