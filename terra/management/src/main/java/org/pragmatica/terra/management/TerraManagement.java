// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.terra.http.TerraHttpMount;
import org.pragmatica.terra.http.TerraHttpServer;
import org.pragmatica.terra.management.ManagementConfig.Port;
import org.pragmatica.terra.management.ManagementEndpoint.Call;
import org.pragmatica.terra.management.ManagementEndpoint.Reply;


/// Actuator-compatible management endpoints for a Terra host (#3651); Terra-only by construction (this module is
/// banned from every Aether module's dependency tree). Requests under the base path dispatch to the endpoints that are
/// enabled and exposed (any other id answers 404); `<base-path>` itself answers HAL discovery of those endpoints unless
/// discovery is disabled or the base path is `/`. Every endpoint except `health` and `info`, and discovery, requires
/// an authenticated caller through the host's authenticator. A JSON answer is
/// `application/vnd.spring-boot.actuator.v3+json` unless the `Accept` header prefers `application/json`; an `Accept`
/// that admits neither answers 406.
public final class TerraManagement {
    public static final String ACTUATOR_V3 = "application/vnd.spring-boot.actuator.v3+json";
    private static final String JSON = "application/json";
    private static final String ROOT_BASE = "/";

    private final ManagementConfig config;
    private final List<ManagementEndpoint> endpoints;
    private final JsonMapper mapper = JsonMapper.defaultJsonMapper();

    private TerraManagement(ManagementConfig config, List<ManagementEndpoint> endpoints) {
        this.config = config;
        this.endpoints = List.copyOf(endpoints);
    }

    /// The standard endpoints: `health` with `indicators` as extra components, `info` with `build`, and `metrics` plus
    /// `prometheus` when `metrics` is present.
    public static Result<TerraManagement> terraManagement(ManagementConfig config,
                                                          Map<String, HealthIndicator> indicators,
                                                          Map<String, Object> build,
                                                          Option<ManagementMetrics> metrics) {
        var standard = new ArrayList<ManagementEndpoint>();

        standard.add(HealthEndpoint.healthEndpoint(config.showDetails(), config.showComponents(), indicators));
        standard.add(InfoEndpoint.infoEndpoint(build, config.info()));
        metrics.onPresent(present -> standard.add(MetricsEndpoint.metricsEndpoint(present)));
        metrics.onPresent(present -> standard.add(MetricsEndpoint.prometheusEndpoint(present)));

        return terraManagement(config, standard);
    }

    /// Endpoint ids are Spring's form (`[a-z][a-zA-Z0-9]*`) and unique.
    public static Result<TerraManagement> terraManagement(ManagementConfig config, List<ManagementEndpoint> endpoints) {
        var ids = endpoints.stream()
                           .map(ManagementEndpoint::id)
                           .toList();

        if (ids.stream().anyMatch(id -> !id.matches("[a-z][a-zA-Z0-9]*")) || ids.stream().distinct().count() != ids.size()) {
            return new ManagementConfig.InvalidManagementConfig("Management endpoint ids must be unique and match [a-z][a-zA-Z0-9]*: "
                                                                + ids).result();
        }

        return Result.success(new TerraManagement(config, endpoints));
    }

    /// The `build.*` entries of `META-INF/build-info.properties` on `loader`, for [#terraManagement]; empty when absent.
    public static Result<Map<String, Object>> buildInfo(ClassLoader loader) {
        return InfoEndpoint.buildInfo(loader);
    }

    /// The host mount, or none when `management.server.port` is `-1`. A management port equal to a non-zero
    /// `applicationPort` shares the application listener, as Spring does.
    public Result<Option<TerraHttpMount>> mount(int applicationPort) {
        return switch (config.port()) {
            case Port.Disabled() -> Result.success(Option.empty());
            case Port.Shared() -> mount(Option.empty());
            case Port.Separate(var port) when port == applicationPort && port != 0 -> mount(Option.empty());
            case Port.Separate(var port) -> mount(Option.some(HttpServerConfig.httpServerConfig("terra-management", port)));
        };
    }

    private Result<Option<TerraHttpMount>> mount(Option<HttpServerConfig> listener) {
        return TerraHttpMount.terraHttpMount(config.basePath(), listener, this::policy, this::handle)
                             .map(Option::some);
    }

    /// Discovery and sensitive endpoints require authentication; health under `when-authorized` reads a presented
    /// credential without demanding one; anything else is public.
    SecurityPolicy policy(HttpRequestContext request) {
        return target(request.path()).fold(SecurityPolicy::publicRoute, this::policyFor);
    }

    private SecurityPolicy policyFor(Target target) {
        return switch (target) {
            case Target.Discovery() -> SecurityPolicy.authenticated();
            case Target.Endpoint(var endpoint, _) when endpoint.sensitive() -> SecurityPolicy.authenticated();
            case Target.Endpoint(var endpoint, _) when endpoint.id().equals("health") && whenAuthorized() -> SecurityPolicy.optional();
            case Target.Endpoint _ -> SecurityPolicy.publicRoute();
        };
    }

    private boolean whenAuthorized() {
        return config.showDetails() == ManagementConfig.Show.WHEN_AUTHORIZED
               || config.showComponents() == ManagementConfig.Show.WHEN_AUTHORIZED;
    }

    Promise<HttpResponseData> handle(HttpRequestContext request, TerraHttpServer.Status status) {
        return target(request.path()).fold(() -> Promise.success(HttpResponseData.httpResponseData(404)),
                                           target -> answer(target, request, status));
    }

    private Promise<HttpResponseData> answer(Target target, HttpRequestContext request, TerraHttpServer.Status status) {
        if (!request.method().equalsIgnoreCase("GET")) {
            return Promise.success(HttpResponseData.httpResponseData(405, Map.of("Allow", "GET"), new byte[0]));
        }

        return switch (target) {
            case Target.Discovery() -> Promise.success(render(Reply.json(200, discovery(request)), request));
            case Target.Endpoint(var endpoint, var selectors) -> endpoint.handler()
                                                                         .apply(Call.call(selectors,
                                                                                          request.queryParams(),
                                                                                          request.security()
                                                                                                 .isAuthenticated(),
                                                                                          status.ready()))
                                                                         .map(reply -> render(reply, request));
        };
    }

    private sealed interface Target {
        record Discovery() implements Target {}

        record Endpoint(ManagementEndpoint endpoint, List<String> selectors) implements Target {}
    }

    private Option<Target> target(String path) {
        var relative = config.basePath().equals(ROOT_BASE)
                       ? path
                       : path.substring(Math.min(path.length(), config.basePath().length()));

        if (relative.isEmpty() || relative.equals("/")) {
            return discoveryEnabled()
                   ? Option.some(new Target.Discovery())
                   : Option.empty();
        }

        var segments = List.of(relative.substring(1).split("/", -1));

        if (segments.stream().anyMatch(String::isEmpty)) {
            return Option.empty();
        }

        return Option.from(endpoints.stream()
                                    .filter(endpoint -> endpoint.id().equals(segments.getFirst()))
                                    .filter(endpoint -> config.enabled(endpoint.id()) && config.exposed(endpoint.id()))
                                    .findFirst())
                     .map(endpoint -> new Target.Endpoint(endpoint, segments.subList(1, segments.size())));
    }

    // Spring disables discovery when the base path is the root.
    private boolean discoveryEnabled() {
        return config.discovery() && !config.basePath().equals(ROOT_BASE);
    }

    private Map<String, Object> discovery(HttpRequestContext request) {
        var base = header(request, "Host").map(host -> header(request, "X-Forwarded-Proto").or("http") + "://" + host)
                                         .or("") + config.basePath();
        var links = new LinkedHashMap<String, Object>();

        links.put("self", link(base, false));

        for (var endpoint : endpoints) {
            if (config.enabled(endpoint.id()) && config.exposed(endpoint.id())) {
                endpoint.selector()
                        .onPresent(template -> links.put(endpoint.id() + "-" + endpoint.selectorName().or(""),
                                                         link(base + "/" + endpoint.id() + "/" + template, true)));
                links.put(endpoint.id(), link(base + "/" + endpoint.id(), false));
            }
        }

        return Map.of("_links", links);
    }

    private static Map<String, Object> link(String href, boolean templated) {
        var link = new LinkedHashMap<String, Object>();

        link.put("href", href);
        link.put("templated", templated);

        return link;
    }

    private HttpResponseData render(Reply reply, HttpRequestContext request) {
        return switch (reply) {
            case Reply.Empty(var status) -> HttpResponseData.httpResponseData(status);
            case Reply.Text(var status, var body, var type) -> HttpResponseData.httpResponseData(status,
                                                                                                Map.of("Content-Type", type),
                                                                                                body.getBytes(StandardCharsets.UTF_8));
            case Reply.Json(var status, var body) -> negotiate(header(request, "Accept")).map(type -> json(status, body, type))
                                                                                        .or(() -> HttpResponseData.httpResponseData(406));
        };
    }

    private HttpResponseData json(int status, Object body, String type) {
        return mapper.writeAsBytes(body)
                     .fold(_ -> HttpResponseData.httpResponseData(500, "Internal server error"),
                           bytes -> HttpResponseData.httpResponseData(status, Map.of("Content-Type", type), bytes));
    }

    /// The first media range by descending quality that admits v3 or plain JSON; v3 when there is no `Accept`.
    static Option<String> negotiate(Option<String> accept) {
        return accept.filter(value -> !value.isBlank())
                     .map(TerraManagement::choose)
                     .or(Option.some(ACTUATOR_V3));
    }

    private static Option<String> choose(String accept) {
        return Option.from(Arrays.stream(accept.split(","))
                                 .map(MediaRange::mediaRange)
                                 .filter(range -> range.quality() > 0)
                                 .sorted(Comparator.comparingDouble(MediaRange::quality).reversed())
                                 .flatMap(range -> range.produces().stream())
                                 .findFirst());
    }

    private record MediaRange(String type, double quality) {
        static MediaRange mediaRange(String text) {
            var parts = text.split(";");
            var quality = Arrays.stream(parts)
                                .skip(1)
                                .map(String::trim)
                                .filter(param -> param.startsWith("q="))
                                .map(param -> param.substring(2))
                                .filter(value -> value.matches("[01](\\.[0-9]{0,3})?"))
                                .mapToDouble(Double::parseDouble)
                                .findFirst()
                                .orElse(1.0);

            return new MediaRange(parts[0].trim().toLowerCase(Locale.ROOT), quality);
        }

        Option<String> produces() {
            return switch (type) {
                case "*/*", "application/*", ACTUATOR_V3 -> Option.some(ACTUATOR_V3);
                case JSON -> Option.some(JSON);
                default -> Option.empty();
            };
        }
    }

    private static Option<String> header(HttpRequestContext request, String name) {
        return Option.from(request.headers()
                                  .entrySet()
                                  .stream()
                                  .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                                  .flatMap(entry -> entry.getValue().stream())
                                  .findFirst());
    }
}
