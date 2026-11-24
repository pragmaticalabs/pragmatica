// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.management.HealthIndicator.Health;
import org.pragmatica.terra.management.HealthIndicator.Status;
import org.pragmatica.terra.management.ManagementConfig.Show;
import org.pragmatica.terra.management.ManagementEndpoint.Call;
import org.pragmatica.terra.management.ManagementEndpoint.Reply;


/// Spring Boot's `health` endpoint (#3651). Components: `livenessState` (UP while the host serves), `readinessState`
/// (UP while it admits slice requests, OUT_OF_SERVICE while it drains), `ping`, `diskSpace` (DOWN below 10 MiB free
/// in the working directory) and the supplied indicators. `/health` aggregates all of them and lists the `liveness`
/// and `readiness` groups; `/health/liveness` holds livenessState, `/health/readiness` readinessState;
/// `/health/<component>` answers one component, 404 while components are not shown. Components and their details
/// appear as `show-components` / `show-details` allow for the caller.
final class HealthEndpoint {
    private static final long DISK_THRESHOLD = 10L * 1024 * 1024;

    private static final Map<String, List<String>> GROUPS = Map.of("liveness",
                                                                   List.of("livenessState"),
                                                                   "readiness",
                                                                   List.of("readinessState"));

    private HealthEndpoint() {}

    static ManagementEndpoint healthEndpoint(Show showDetails,
                                             Show showComponents,
                                             Map<String, HealthIndicator> indicators) {
        var all = new TreeMap<>(indicators);

        all.put("ping",
                () -> Promise.success(Health.up()));
        all.put("diskSpace", HealthEndpoint::diskSpace);
        var checked = Map.copyOf(all);

        return ManagementEndpoint.managementEndpoint("health",
                                                     false,
                                                     Option.some("{*path}"),
                                                     call -> answer(call, showDetails, showComponents, checked));
    }

    private static Promise<Reply> answer(Call call,
                                         Show showDetails,
                                         Show showComponents,
                                         Map<String, HealthIndicator> indicators) {
        var components = showComponents.shown(call.authorized());
        var details = showDetails.shown(call.authorized());

        return switch (call.selectors()
                           .size()) {
            case 0 -> check(call,
                            indicators,
                            indicators.keySet().stream().toList()).map(checked -> composite(checked,
                                                                                            components,
                                                                                            details,
                                                                                            true));
            case 1 -> one(call,
                          indicators,
                          call.selectors().getFirst(),
                          components,
                          details);
            default -> Promise.success(Reply.empty(404));
        };
    }

    private static Promise<Reply> one(Call call,
                                      Map<String, HealthIndicator> indicators,
                                      String name,
                                      boolean components,
                                      boolean details) {
        if (GROUPS.containsKey(name)) {
            return check(call, indicators, GROUPS.get(name)).map(checked -> composite(checked,
                                                                                      components,
                                                                                      details,
                                                                                      false));
        }

        return components && (indicators.containsKey(name) || stateHealth(call, name).isPresent())
               ? check(call, indicators, List.of(name)).map(checked -> single(checked.get(name), details))
               : Promise.success(Reply.empty(404));
    }

    private static Promise<Map<String, Health>> check(Call call,
                                                      Map<String, HealthIndicator> indicators,
                                                      List<String> names) {
        var ordered = new ArrayList<>(names);

        ordered.addAll(call.selectors().isEmpty()
                       ? List.of("livenessState", "readinessState")
                       : List.of());
        var sorted = ordered.stream().sorted().distinct().toList();

        return Promise.allOf(sorted.stream().map(name -> component(call, indicators, name)).toList()).map(results -> zip(sorted,
                                                                                                                         results));
    }

    private static Map<String, Health> zip(List<String> names, List<Result<Health>> results) {
        var checked = new TreeMap<String, Health>();
        // Never taken: component() answers a state from the call and recovers every indicator failure to DOWN.
        for (int i = 0; i < names.size(); i++) {
            checked.put(names.get(i),
                        results.get(i).or(Health.health(Status.DOWN, Map.of())));
        }

        return checked;
    }

    private static Promise<Health> component(Call call, Map<String, HealthIndicator> indicators, String name) {
        return stateHealth(call, name).map(health -> Promise.success(health))
                          .or(() -> invoke(indicators.get(name)));
    }

    private static Option<Health> stateHealth(Call call, String name) {
        return switch (name) {
            case "livenessState" -> Option.some(Health.up());
            case "readinessState" -> Option.some(Health.health(call.ready()
                                                               ? Status.UP
                                                               : Status.OUT_OF_SERVICE,
                                                               Map.of()));
            default -> Option.empty();
        };
    }

    // FER: a failed or throwing indicator degrades to its component reading DOWN with Spring's `error` detail, so one
    // broken check answers 503 instead of failing the whole health request.
    private static Promise<Health> invoke(HealthIndicator indicator) {
        return Promise.lift(Causes::fromThrowable, indicator::health)
                      .flatMap(promise -> promise)
                      .recover(cause -> Health.health(Status.DOWN,
                                                      Map.of("error",
                                                             cause.message())));
    }

    private static Reply composite(Map<String, Health> checked, boolean components, boolean details, boolean root) {
        var status = aggregate(checked.values().stream().map(Health::status).toList());
        var body = new LinkedHashMap<String, Object>();

        body.put("status", status.name());
        if (components) {
            var rendered = new LinkedHashMap<String, Object>();

            checked.forEach((name, health) -> rendered.put(name, render(health, details)));
            body.put("components", rendered);
        }

        if (root) {
            body.put("groups", List.of("liveness", "readiness"));
        }

        return Reply.json(status.httpStatus(), body);
    }

    private static Reply single(Health health, boolean details) {
        return Reply.json(health.status().httpStatus(),
                          render(health, details));
    }

    private static Map<String, Object> render(Health health, boolean details) {
        var body = new LinkedHashMap<String, Object>();

        body.put("status",
                 health.status().name());
        if (details && !health.details().isEmpty()) {
            body.put("details", new TreeMap<>(health.details()));
        }

        return body;
    }

    static Status aggregate(List<Status> statuses) {
        return Arrays.stream(Status.values())
                     .filter(statuses::contains)
                     .findFirst()
                     .orElse(Status.UNKNOWN);
    }

    private static Promise<Health> diskSpace() {
        return Promise.lift(Causes::fromThrowable, () -> diskHealth(new File(".").getAbsoluteFile()));
    }

    private static Health diskHealth(File path) {
        var free = path.getUsableSpace();
        var details = new LinkedHashMap<String, Object>();

        details.put("total", path.getTotalSpace());
        details.put("free", free);
        details.put("threshold", DISK_THRESHOLD);
        details.put("path", path.getPath());
        details.put("exists", path.exists());

        return Health.health(free >= DISK_THRESHOLD
                             ? Status.UP
                             : Status.DOWN,
                             details);
    }
}
