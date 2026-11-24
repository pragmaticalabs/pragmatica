// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Management settings under Spring Boot's keys and defaults (#3651). Recognised keys:
/// `management.endpoints.web.exposure.include` / `.exclude` (comma lists, `*` for all; default include `health`),
/// `management.endpoints.web.base-path` (default `/actuator`), `management.endpoints.web.discovery.enabled` (default
/// true), `management.endpoints.enabled-by-default` (default true), `management.endpoint.<id>.enabled`,
/// `management.endpoint.health.show-details` / `.show-components` (`never` | `when-authorized` | `always`; details
/// default `never`, components default to the details value), `management.server.port` (absent: the application
/// listener; `-1`: no HTTP management), `management.info.env.enabled` (default false) and free-form `info.*`.
///
/// Unlike Spring, an unrecognised `management.*` key is refused rather than ignored: the Terra host refuses unknown
/// sections the same way, and a mistyped exposure key would otherwise silently expose nothing.
public record ManagementConfig(Set<String> include,
                               Set<String> exclude,
                               String basePath,
                               boolean discovery,
                               boolean enabledByDefault,
                               Map<String, Boolean> enabled,
                               Show showDetails,
                               Show showComponents,
                               Port port,
                               Map<String, Object> info) {
    public enum Show {
        NEVER,
        WHEN_AUTHORIZED,
        ALWAYS;
        boolean shown(boolean authorized) {
            return this == ALWAYS || (this == WHEN_AUTHORIZED && authorized);
        }
    }

    public sealed interface Port {
        record Shared() implements Port {}

        record Separate(int port) implements Port {}

        record Disabled() implements Port {}
    }

    public record InvalidManagementConfig(String message) implements Cause {}

    private static final String ENDPOINT = "management.endpoint.";

    private static final Set<String> FIXED = Set.of("management.endpoints.web.exposure.include",
                                                    "management.endpoints.web.exposure.exclude",
                                                    "management.endpoints.web.base-path",
                                                    "management.endpoints.web.discovery.enabled",
                                                    "management.endpoints.enabled-by-default",
                                                    "management.endpoint.health.show-details",
                                                    "management.endpoint.health.show-components",
                                                    "management.server.port",
                                                    "management.info.env.enabled");

    /// `keys` holds the `management.*` and `info.*` settings; any other key is ignored.
    public static Result<ManagementConfig> managementConfig(Map<String, String> keys) {
        var unknown = keys.keySet()
                          .stream()
                          .filter(key -> key.startsWith("management."))
                          .filter(key -> !FIXED.contains(key) && !isEndpointEnabled(key))
                          .sorted()
                          .findFirst();

        if (unknown.isPresent()) {
            return invalid("Unsupported management setting: " + unknown.get());
        }

        var details = show(keys, "management.endpoint.health.show-details", Show.NEVER);

        return Result.all(details,
                          details.flatMap(fallback -> show(keys, "management.endpoint.health.show-components", fallback)),
                          flag(keys, "management.endpoints.web.discovery.enabled", true),
                          flag(keys, "management.endpoints.enabled-by-default", true),
                          enabled(keys),
                          port(keys),
                          info(keys))
                     .map((showDetails, showComponents, discovery, enabledByDefault, enabled, port, info) -> new ManagementConfig(list(keys,
                                                                                                                                       "management.endpoints.web.exposure.include",
                                                                                                                                       "health"),
                                                                                                                                  list(keys,
                                                                                                                                       "management.endpoints.web.exposure.exclude",
                                                                                                                                       ""),
                                                                                                                                  basePath(keys),
                                                                                                                                  discovery,
                                                                                                                                  enabledByDefault,
                                                                                                                                  enabled,
                                                                                                                                  showDetails,
                                                                                                                                  showComponents,
                                                                                                                                  port,
                                                                                                                                  info));
    }

    /// Exposed over HTTP: included (by id or `*`) and not excluded (by id or `*`).
    public boolean exposed(String id) {
        return (include.contains("*") || include.contains(id)) && !(exclude.contains("*") || exclude.contains(id));
    }

    public boolean enabled(String id) {
        return Option.option(enabled.get(id)).or(enabledByDefault);
    }

    private static boolean isEndpointEnabled(String key) {
        return key.startsWith(ENDPOINT)
               && key.endsWith(".enabled")
               && key.length() > ENDPOINT.length() + ".enabled".length()
               && key.substring(ENDPOINT.length(),
                                key.length() - ".enabled".length())
                     .matches("[a-z][a-zA-Z0-9]*");
    }

    private static Set<String> list(Map<String, String> keys, String key, String fallback) {
        return Arrays.stream(Option.option(keys.get(key)).or(fallback).split(","))
                     .map(String::trim)
                     .filter(item -> !item.isEmpty())
                     .collect(Collectors.toUnmodifiableSet());
    }

    // Spring strips a trailing slash and reads an empty base path as the root.
    private static String basePath(Map<String, String> keys) {
        var path = Option.option(keys.get("management.endpoints.web.base-path")).map(String::trim).or("/actuator");
        var trimmed = path.endsWith("/")
                      ? path.substring(0, path.length() - 1)
                      : path;

        return trimmed.isEmpty()
               ? "/"
               : trimmed;
    }

    private static Result<Show> show(Map<String, String> keys, String key, Show fallback) {
        return Option.option(keys.get(key))
                     .map(value -> parseShow(key, value))
                     .or(Result.success(fallback));
    }

    private static Result<Show> parseShow(String key, String value) {
        return Arrays.stream(Show.values())
                     .filter(show -> show.name()
                                         .equals(value.trim().toUpperCase(Locale.ROOT).replace('-', '_')))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(() -> invalid(key + " must be never, when-authorized or always: " + value));
    }

    private static Result<Boolean> flag(Map<String, String> keys, String key, boolean fallback) {
        return Option.option(keys.get(key))
                     .map(value -> parseFlag(key, value))
                     .or(Result.success(fallback));
    }

    private static Result<Boolean> parseFlag(String key, String value) {
        return switch (value.trim()
                            .toLowerCase(Locale.ROOT)) {
            case "true" -> Result.success(true);
            case "false" -> Result.success(false);
            default -> invalid(key + " must be true or false: " + value);
        };
    }

    private static Result<Map<String, Boolean>> enabled(Map<String, String> keys) {
        return Result.allOf(keys.keySet()
                                .stream()
                                .filter(ManagementConfig::isEndpointEnabled)
                                .map(key -> parseFlag(key,
                                                      keys.get(key)).map(flag -> Map.entry(key.substring(ENDPOINT.length(),
                                                                                                         key.length() - ".enabled".length()),
                                                                                           flag)))).map(entries -> Map.copyOf(entries.stream()
                                                                                                                                     .collect(Collectors.toMap(Map.Entry::getKey,
                                                                                                                                                               Map.Entry::getValue))));
    }

    private static Result<Port> port(Map<String, String> keys) {
        return Option.option(keys.get("management.server.port"))
                     .map(String::trim)
                     .map(ManagementConfig::parsePort)
                     .or(Result.success(new Port.Shared()));
    }

    private static Result<Port> parsePort(String value) {
        return value.matches("-?[0-9]{1,5}")
               ? portNumber(Integer.parseInt(value))
               : invalid("management.server.port must be -1 or 0..65535: " + value);
    }

    private static Result<Port> portNumber(int port) {
        if (port == -1) {
            return Result.success(new Port.Disabled());
        }

        return port >= 0 && port <= 65535
               ? Result.success(new Port.Separate(port))
               : invalid("management.server.port must be -1 or 0..65535: " + port);
    }

    // `info.app.name = x` becomes {"app": {"name": "x"}}; a key that is both a value and a parent is refused.
    // `info.*` reaches the info endpoint only with management.info.env.enabled = true (Spring Boot 2.6+ default false).
    private static Result<Map<String, Object>> info(Map<String, String> keys) {
        return flag(keys, "management.info.env.enabled", false).flatMap(env -> env
                                                                               ? nest(keys)
                                                                               : Result.success(Map.of()));
    }

    private static Result<Map<String, Object>> nest(Map<String, String> keys) {
        var root = new TreeMap<String, Object>();

        for (var entry : new TreeMap<>(keys).entrySet()) {
            if (entry.getKey().startsWith("info.") && !place(root,
                                                             entry.getKey().substring(5).split("\\."),
                                                             entry.getValue())) {
                return invalid("info key is both a value and a parent: " + entry.getKey());
            }
        }

        return Result.success(root);
    }

    @SuppressWarnings("unchecked")
    private static boolean place(Map<String, Object> root, String[] path, String value) {
        var node = root;

        for (int i = 0; i < path.length - 1; i++) {
            var child = node.computeIfAbsent(path[i], _ -> new LinkedHashMap<String, Object>());

            if (! (child instanceof Map<?, ?>)) {
                return false;
            }

            node = (Map<String, Object>) child;
        }

        return node.putIfAbsent(path[path.length - 1], value) == null;
    }

    private static <T> Result<T> invalid(String message) {
        return new InvalidManagementConfig(message).result();
    }
}
