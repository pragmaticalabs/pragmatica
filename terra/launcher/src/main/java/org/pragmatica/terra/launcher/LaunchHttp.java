// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.AuthSchemes;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.JwtKeyMode;
import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.terra.http.HttpAuthenticatorFactory;
import org.pragmatica.terra.http.TerraHttpConfig;


record LaunchHttp(TerraHttpConfig config, Fn0<HttpAuthenticator> authenticator) {
    private record Security(SecurityPolicy policy, Fn0<HttpAuthenticator> authenticator) {}

    static Result<LaunchHttp> launchHttp(Path directory, ConfigurationProvider settings) {
        return launchHttp(directory, settings, LaunchHttp::installedAuthenticators);
    }

    /// With the authenticator factories supplied (#2880), so a test can stand for any number of installed providers.
    /// `discovery` is consulted only when `http.security_mode` is `custom`.
    static Result<LaunchHttp> launchHttp(Path directory,
                                         ConfigurationProvider settings,
                                         Fn0<Result<List<HttpAuthenticatorFactory>>> discovery) {
        return settingsKeys(settings).flatMap(valid -> parsedHttp(directory, valid, discovery));
    }

    private static Result<List<HttpAuthenticatorFactory>> installedAuthenticators() {
        return Result.lift(Causes::fromThrowable,
                           () -> ServiceLoader.load(HttpAuthenticatorFactory.class)
                                              .stream()
                                              .map(ServiceLoader.Provider::get)
                                              .toList());
    }

    private static Result<ConfigurationProvider> settingsKeys(ConfigurationProvider settings) {
        var httpKeys = Set.of("http.port",
                              "http.max_content_length",
                              "http.version_mode",
                              "http.version_header",
                              "http.security_mode",
                              "http.authenticator",
                              "http.tls_certificate",
                              "http.tls_key");
        var jwtKeys = Set.of("jwt.jwks_url",
                             "jwt.issuer",
                             "jwt.audience",
                             "jwt.role_claim",
                             "jwt.cache_ttl_seconds",
                             "jwt.clock_skew_seconds",
                             "jwt.auth_schemes",
                             "jwt.algorithm",
                             "jwt.secret");

        for (var key : settings.keys()) {
            if ((key.startsWith("http.") && !httpKeys.contains(key)) || (key.startsWith("jwt.") && !jwtKeys.contains(key)) || (key.startsWith("api_keys.") && !key.matches("api_keys\\.[^.]+\\.(key|roles|authorization_role)"))) {
                return new TerraLaunchError("Unknown host setting: " + key).result();
            }
        }

        return Result.success(settings);
    }

    private static Result<LaunchHttp> parsedHttp(Path directory,
                                                 ConfigurationProvider settings,
                                                 Fn0<Result<List<HttpAuthenticatorFactory>>> discovery) {
        return Result.all(settings.getInt("http.port").map(value -> value.or(8080)),
                          settings.getInt("http.max_content_length").map(value -> value.or(10 * 1024 * 1024)),
                          mountMode(settings),
                          security(settings, discovery),
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

    private static Result<Security> security(ConfigurationProvider settings,
                                             Fn0<Result<List<HttpAuthenticatorFactory>>> discovery) {
        return refuseStrayAuthenticator(settings).flatMap(_ -> modeSecurity(settings, discovery));
    }

    /// A name in `http.authenticator` beside any other mode would read as "use this one" while the launcher uses
    /// another: refused, never ignored.
    private static Result<Unit> refuseStrayAuthenticator(ConfigurationProvider settings) {
        return settings.getString("http.authenticator")
                       .isPresent() && !"custom".equals(settings.getString("http.security_mode").or(""))
               ? new TerraLaunchError("http.authenticator is set but http.security_mode is not custom").result()
               : Result.unitResult();
    }

    private static Result<Security> modeSecurity(ConfigurationProvider settings,
                                                 Fn0<Result<List<HttpAuthenticatorFactory>>> discovery) {
        return switch (settings.getString("http.security_mode")
                               .or("api-key")) {
            case "custom" -> custom(settings, discovery);
            case "none" -> Result.success(new Security(SecurityPolicy.publicRoute(),
                                                       HttpAuthenticator::denyUnlessPublicValidator));
            case "api-key" -> apiKeys(settings).map(keys -> new Security(SecurityPolicy.apiKeyRequired(),
                                                                         () -> HttpAuthenticator.apiKeyValidator(keys)));
            case "jwt" -> jwt(settings).map(jwt -> new Security(SecurityPolicy.bearerTokenRequired(),
                                                                () -> HttpAuthenticator.jwtValidator(jwt)));
            default -> new TerraLaunchError("http.security_mode must be none, api-key, jwt, or custom").result();
        };
    }

    /// #2880: `http.security_mode = "custom"` selects, by `http.authenticator`, one installed [HttpAuthenticatorFactory].
    private static Result<Security> custom(ConfigurationProvider settings,
                                           Fn0<Result<List<HttpAuthenticatorFactory>>> discovery) {
        return settings.getString("http.authenticator")
                       .filter(name -> !name.isBlank())
                       .toResult(new TerraLaunchError("http.security_mode = custom requires http.authenticator, the name of an installed authenticator"))
                       .flatMap(name -> discovery.apply()
                                                 .flatMap(installed -> selected(name, installed)))
                       .flatMap(factory -> factory.authenticator(settings)
                                                  .mapError(LaunchHttp::invalidAuthenticator))
                       .map(LaunchHttp::customSecurity);
    }

    private static Security customSecurity(HttpAuthenticator authenticator) {
        return new Security(SecurityPolicy.authenticated(), () -> authenticator);
    }

    private static TerraLaunchError invalidAuthenticator(Cause cause) {
        return new TerraLaunchError("Invalid authenticator: " + cause.message());
    }

    /// Zero factories of the name refuse the launch naming the installed ones; two refuse it naming both classes.
    private static Result<HttpAuthenticatorFactory> selected(String name, List<HttpAuthenticatorFactory> installed) {
        var matching = installed.stream().filter(factory -> name.equals(factory.name())).toList();

        return switch (matching.size()) {
            case 0 -> new TerraLaunchError("No authenticator named '" + name + "' is installed (installed: " + installedNames(installed) + ")").result();
            case 1 -> Result.success(matching.getFirst());
            default -> new TerraLaunchError("Authenticator name '" + name + "' is provided by more than one factory: " + classNames(matching)).result();
        };
    }

    private static String installedNames(List<HttpAuthenticatorFactory> installed) {
        return installed.isEmpty()
               ? "none"
               : installed.stream()
                          .map(HttpAuthenticatorFactory::name)
                          .collect(Collectors.joining(", "));
    }

    private static String classNames(List<HttpAuthenticatorFactory> factories) {
        return factories.stream()
                        .map(factory -> factory.getClass()
                                               .getName())
                        .collect(Collectors.joining(" and "));
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
        return Result.all(keyMode(settings),
                          secret(settings),
                          settings.getLong("jwt.cache_ttl_seconds").map(value -> value.or(3600L)),
                          settings.getLong("jwt.clock_skew_seconds").map(value -> value.or(30L)))
                     .flatMap((mode, secret, ttl, skew) -> jwksUrl(settings, mode, secret).flatMap(url -> jwtConfig(settings,
                                                                                                                    url,
                                                                                                                    mode,
                                                                                                                    secret,
                                                                                                                    ttl,
                                                                                                                    skew)));
    }

    private static Result<JwtConfig> jwtConfig(ConfigurationProvider settings,
                                               String url,
                                               JwtKeyMode mode,
                                               Option<JwtSecret> secret,
                                               long ttl,
                                               long skew) {
        return JwtConfig.jwtConfig(url,
                                   settings.getString("jwt.issuer"),
                                   settings.getString("jwt.audience"),
                                   settings.getString("jwt.role_claim").or("role"),
                                   ttl,
                                   skew,
                                   authSchemes(settings),
                                   mode,
                                   secret)
                        .mapError(LaunchHttp::invalidJwt);
    }

    /// #2880: `[jwt] algorithm = "HS256"` selects the shared-secret mode; absent, the mode is JWKS. Any other value
    /// refuses the launch. The mode is the configuration's, never a token's.
    private static Result<JwtKeyMode> keyMode(ConfigurationProvider settings) {
        return settings.getString("jwt.algorithm")
                       .fold(() -> Result.success(JwtKeyMode.JWKS),
                             raw -> JwtKeyMode.jwtKeyMode(raw).toResult(new TerraLaunchError("Invalid [jwt]: algorithm must be HS256 (omit it for JWKS)")));
    }

    /// `[jwt] secret`: the HS256 shared secret, an environment or property value (`TERRA_JWT__SECRET`) per
    /// `terra/SPEC.md`, because Terra has no secrets provider. Length is checked by [JwtSecret]; the value is never
    /// part of any message.
    private static Result<Option<JwtSecret>> secret(ConfigurationProvider settings) {
        return settings.getString("jwt.secret")
                       .fold(() -> Result.success(Option.<JwtSecret> none()),
                             text -> JwtSecret.jwtSecret(text)
                                              .map(Option::some)
                                              .mapError(LaunchHttp::invalidJwt));
    }

    private static Result<String> jwksUrl(ConfigurationProvider settings, JwtKeyMode mode, Option<JwtSecret> secret) {
        return switch (mode) {
            case JWKS -> settings.getString("jwt.jwks_url").filter(value -> !value.isBlank()).toResult(new TerraLaunchError("JWT mode requires jwt.jwks_url"));
            case HS256 -> secret.isPresent()
                          ? Result.success(settings.getString("jwt.jwks_url").or(""))
                          : new TerraLaunchError("JWT HS256 mode requires jwt.secret").result();
        };
    }

    private static TerraLaunchError invalidJwt(Cause cause) {
        return new TerraLaunchError("Invalid [jwt]: " + cause.message());
    }

    /// #2156: `[jwt] auth_schemes`, the `Authorization` schemes JWT mode accepts. Absent, the default `["Bearer"]`; a
    /// present list is checked by [AuthSchemes#authSchemes], so an empty list or a duplicate refuses the launch.
    private static List<String> authSchemes(ConfigurationProvider settings) {
        return settings.getString("jwt.auth_schemes")
                       .map(LaunchHttp::listEntries)
                       .or(AuthSchemes.DEFAULT);
    }

    /// Blank entries are kept so [AuthSchemes] refuses them, as Aether does; only a wholly blank list is empty.
    private static List<String> listEntries(String raw) {
        var body = raw.replace("[", "").replace("]", "");

        return body.isBlank()
               ? List.of()
               : Arrays.stream(body.split(",", -1))
                       .map(String::trim)
                       .toList();
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
