// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.terra.management.ManagementEndpoint.Call;
import org.pragmatica.terra.management.ManagementEndpoint.Reply;

import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.search.Search;


/// Spring Boot's `metrics` and `prometheus` endpoints over [ManagementMetrics] (#3651). `/metrics` lists the sorted
/// meter names; `/metrics/{name}?tag=key:value` merges every matching meter's measurements per statistic (MAX by
/// maximum, the rest by sum), lists the tags not filtered on, and answers 404 for no match and 400 for a tag filter
/// without `:`. `/prometheus` answers the registry scrape, 404 without a Prometheus registry. Both are sensitive.
final class MetricsEndpoint {
    private MetricsEndpoint() {}

    static ManagementEndpoint metricsEndpoint(ManagementMetrics metrics) {
        return ManagementEndpoint.managementEndpoint("metrics",
                                                     true,
                                                     Option.some("{requiredMetricName}"),
                                                     call -> Promise.success(answer(metrics, call)));
    }

    static ManagementEndpoint prometheusEndpoint(ManagementMetrics metrics) {
        return ManagementEndpoint.managementEndpoint("prometheus",
                                                     true,
                                                     Option.empty(),
                                                     call -> Promise.success(scrape(metrics, call)));
    }

    private static Reply scrape(ManagementMetrics metrics, Call call) {
        return call.selectors()
                   .isEmpty()
               ? metrics.prometheusScrape()
                        .apply()
                        .map(text -> Reply.text(text, ManagementMetrics.PROMETHEUS_CONTENT_TYPE))
                        .or(Reply.empty(404))
               : Reply.empty(404);
    }

    private static Reply answer(ManagementMetrics metrics, Call call) {
        return switch (call.selectors()
                           .size()) {
            case 0 -> Reply.json(200,
                                 Map.of("names",
                                        names(metrics.registry().getMeters())));
            case 1 -> describe(metrics,
                               call.selectors().getFirst(),
                               call.query().getOrDefault("tag", List.of()));
            default -> Reply.empty(404);
        };
    }

    private static List<String> names(Collection<Meter> meters) {
        return meters.stream()
                     .map(meter -> meter.getId()
                                        .getName())
                     .sorted()
                     .distinct()
                     .toList();
    }

    private static Reply describe(ManagementMetrics metrics, String name, List<String> filters) {
        if (filters.stream().anyMatch(filter -> filter.indexOf(':') < 1)) {
            return Reply.empty(400);
        }

        var tags = filters.stream()
                          .map(filter -> Tag.of(filter.substring(0,
                                                                 filter.indexOf(':')),
                                                filter.substring(filter.indexOf(':') + 1)))
                          .toList();
        var meters = Search.in(metrics.registry()).name(name).tags(tags).meters();

        return meters.isEmpty()
               ? Reply.empty(404)
               : Reply.json(200, descriptor(name, meters, tags));
    }

    private static Map<String, Object> descriptor(String name, Collection<Meter> meters, List<Tag> filters) {
        var first = meters.iterator().next().getId();
        var body = new LinkedHashMap<String, Object>();

        body.put("name", name);
        Option.option(first.getDescription()).onPresent(description -> body.put("description", description));
        Option.option(first.getBaseUnit()).onPresent(unit -> body.put("baseUnit", unit));
        body.put("measurements", measurements(meters));
        body.put("availableTags", availableTags(meters, filters));

        return body;
    }

    private static List<Map<String, Object>> measurements(Collection<Meter> meters) {
        var merged = new LinkedHashMap<Statistic, Double>();

        for (var meter : meters) {
            for (Measurement measurement : meter.measure()) {
                merged.merge(measurement.getStatistic(),
                             measurement.getValue(),
                             measurement.getStatistic() == Statistic.MAX
                             ? Double::max
                             : Double::sum);
            }
        }

        var rendered = new ArrayList<Map<String, Object>>();

        merged.forEach((statistic, value) -> rendered.add(Map.of("statistic", statistic.name(), "value", value)));

        return rendered;
    }

    private static List<Map<String, Object>> availableTags(Collection<Meter> meters, List<Tag> filters) {
        var filtered = filters.stream().map(Tag::getKey).toList();
        var available = new TreeMap<String, TreeSet<String>>();

        meters.forEach(meter -> meter.getId()
                                     .getTags()
                                     .stream()
                                     .filter(tag -> !filtered.contains(tag.getKey()))
                                     .forEach(tag -> available.computeIfAbsent(tag.getKey(),
                                                                               _ -> new TreeSet<>())
                                                              .add(tag.getValue())));

        return available.entrySet()
                        .stream()
                        .<Map<String, Object>> map(entry -> Map.of("tag",
                                                                   entry.getKey(),
                                                                   "values",
                                                                   List.copyOf(entry.getValue())))
                        .toList();
    }
}
