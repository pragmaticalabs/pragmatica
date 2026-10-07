// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.terra.http.TerraHttpConfig;


record LaunchHttp(TerraHttpConfig config, Fn0<HttpAuthenticator> authenticator) {
    private record Security(SecurityPolicy policy, Fn0<HttpAuthenticator> authenticator) {}

    static Result<LaunchHttp> launchHttp(Path directory, ConfigurationProvider settings) {
        return settingsKeys(settings).flatMap(valid -> parsedHttp(directory, valid));
    }

    private static Result<ConfigurationProvider> settingsKeys(ConfigurationProvider settings) {
        var httpKeys = Set.of("http.port",
                              "http.max_content_length",
                              "http.version_mode",
                              "http.version_header",
                              "http.security_mode",
                              "http.tls_certificate",
                              "http.tls_key");
        var jwtKeys = Set.of("jwt.jwks_url",
                             "jwt.issuer",
                             "jwt.audience",
                             "jwt.role_claim",
                             "jwt.cache_ttl_seconds",
                             "jwt.clock_skew_seconds");

        for (var key : settings.keys()) {
            if ((key.startsWith("http.") && !httpKeys.contains(key)) || (key.startsWith("jwt.") && !jwtKeys.contains(key)) || (key.startsWith("api_keys.") && !key.matches("api_keys\\.[^.]+\\.(key|roles|authorization_role)"))) {
                return new TerraLaunchError("Unknown host setting: " + key).result();
            }
        }

        return Result.success(settings);
    }

    private static Result<LaunchHttp> parsedHttp(Path directory, ConfigurationProvider settings) {
        return Result.all(settings.getInt("http.port").map(value -> value.or(8080)),
                          settings.getInt("http.max_content_length").map(value -> value.or(10 * 1024 * 1024)),
                          mountMode(settings),
                          security(settings),
                          tls(directory, settings))
                     .flatMap((port, maxSize, mode, security, tls) -> config(port, maxSize, mode, security, tls));
    }

    private static Result<LaunchHttp> config(int port,
                                             int maxSize,
                                             RouteMountMode mode,
                                             Security security,
                                             Option<TlsConfig> tls) {
        var transport = HttpServerConfig.httpServerConfig("terra", port).withMaxContentLength(maxSize);

        return TerraHttpConfig.terraHttpConfig(tls.map(transport::withTls).or(transport),
                                               mode,
                                               security.policy())
                              .map(config -> new LaunchHttp(config,
                                                            security.authenticator()));
    }

    private static Result<RouteMountMode> mountMode(ConfigurationProvider settings) {
        return switch (settings.getString("http.version_mode")
                               .or("path")) {
            case "path" -> Result.success(RouteMountMode.pathMode());
            case "header" -> Result.success(RouteMountMode.headerMode(settings.getString("http.version_header").or("API-Version")));
            default -> new TerraLaunchError("http.version_mode must be path or header").result();
        };
    }

    private static Result<Security> security(ConfigurationProvider settings) {
        return switch (settings.getString("http.security_mode")
                               .or("api-key")) {
            case "none" -> Result.success(new Security(SecurityPolicy.publicRoute(),
                                                       HttpAuthenticator::denyUnlessPublicValidator));
            case "api-key" -> apiKeys(settings).map(keys -> new Security(SecurityPolicy.apiKeyRequired(),
                                                                         () -> HttpAuthenticator.apiKeyValidator(keys)));
            case "jwt" -> jwt(settings).map(jwt -> new Security(SecurityPolicy.bearerTokenRequired(),
                                                                () -> HttpAuthenticator.jwtValidator(jwt)));
            default -> new TerraLaunchError("http.security_mode must be none, api-key, or jwt").result();
        };
    }

    private static Result<Map<String, ApiKeyEntry>> apiKeys(ConfigurationProvider settings) {
        var names = settings.keys()
                            .stream()
                            .filter(key -> key.startsWith("api_keys."))
                            .map(key -> key.substring(9)
                                           .split("\\.", 2) [0])
                            .distinct()
                            .toList();
        var entries = new LinkedHashMap<String, ApiKeyEntry>();

        for (var name : names) {
            var section = "api_keys." + name;
            var value = settings.getString(section + ".key");
            var role = settings.getString(section + ".authorization_role").or("VIEWER").toUpperCase(Locale.ROOT);

            if (value.isEmpty() || value.unwrap().isBlank() || !Set.of("ADMIN", "OPERATOR", "VIEWER").contains(role)) {
                return new TerraLaunchError("Invalid API-key configuration for entry " + name).result();
            }

            var roles = Arrays.stream(settings.getString(section + ".roles")
                                              .or("service")
                                              .replace("[", "")
                                              .replace("]", "")
                                              .split(","))
                              .map(String::trim)
                              .filter(text -> !text.isBlank())
                              .collect(Collectors.toUnmodifiableSet());

            if (entries.putIfAbsent(value.unwrap(), ApiKeyEntry.apiKeyEntry(name, roles, role)) != null) {
                return new TerraLaunchError("Duplicate API-key credential in configuration").result();
            }
        }

        return Result.success(Map.copyOf(entries));
    }

    private static Result<JwtConfig> jwt(ConfigurationProvider settings) {
        return Result.all(settings.getString("jwt.jwks_url")
                                  .filter(value -> !value.isBlank())
                                  .toResult(new TerraLaunchError("JWT mode requires jwt.jwks_url")),
                          settings.getLong("jwt.cache_ttl_seconds").map(value -> value.or(3600L)),
                          settings.getLong("jwt.clock_skew_seconds").map(value -> value.or(30L)))
                     .flatMap((url, ttl, skew) -> JwtConfig.jwtConfig(url,
                                                                      settings.getString("jwt.issuer"),
                                                                      settings.getString("jwt.audience"),
                                                                      settings.getString("jwt.role_claim").or("role"),
                                                                      ttl,
                                                                      skew));
    }

    private static Result<Option<TlsConfig>> tls(Path directory, ConfigurationProvider settings) {
        var certificate = settings.getString("http.tls_certificate");
        var key = settings.getString("http.tls_key");

        if (certificate.isEmpty() && key.isEmpty()) {
            return Result.success(Option.empty());
        }

        if (certificate.isEmpty() || key.isEmpty()) {
            return new TerraLaunchError("TLS requires both http.tls_certificate and http.tls_key").result();
        }

        return Result.success(Option.some(TlsConfig.server(directory.resolve(certificate.unwrap()),
                                                           directory.resolve(key.unwrap()))));
    }
}
