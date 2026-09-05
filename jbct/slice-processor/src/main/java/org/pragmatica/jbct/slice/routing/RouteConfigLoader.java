// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Loader for route configuration from TOML files.
///
/// Supports loading configuration from:
///
///   - Single config file via {@link #load(Path)}
///   - Merged base + slice config via {@link #loadMerged(Path)}
///
///
/// TOML format:
/// ```{@code
/// prefix = "/api/v1"
///
/// [security]
/// default = "authenticated"
/// override_policy = "strengthen_only"
///
/// [routes]
/// getUser = "GET /{id:Long}"
/// createUser = "POST /"
///
/// [errors]
/// default = 500
/// HTTP_404 = ["*NotFound*", "*Missing*"]
/// HTTP_400 = ["*Invalid*"]
///
/// [errors.explicit]
/// SomeAmbiguousType = 404
/// }```
public final class RouteConfigLoader {
    public static final String CONFIG_FILE = "routes.toml";
    public static final String BASE_CONFIG_FILE = "routes-base.toml";
    private static final Cause FILE_NOT_FOUND = Causes.cause("Route configuration file not found");
    private static final Cause PARSE_ERROR = Causes.cause("Failed to parse route configuration");

    /// #763: an entirely absent `[security]` section is NOT the same as an explicit
    /// `default = "public"` — it means the slice never declared a stance, so the route must inherit
    /// the server's global security policy rather than being silently exempted from it.
    private static final SecuritySection DEFAULT_SECURITY = new SecuritySection(RouteSecurityLevel.UNSPECIFIED,
                                                                                OverridePolicy.STRENGTHEN_ONLY);

    /// Matches a `[vN.routes]` section header (the version-routes block) and captures the version number.
    private static final Pattern VERSION_ROUTES_SECTION = Pattern.compile("^v(\\d+)\\.routes$");

    private RouteConfigLoader() {}

    /// Load route configuration from a specific file.
    ///
    /// @param configPath path to the TOML configuration file
    /// @return Result containing RouteConfig or error
    public static Result<RouteConfig> load(Path configPath) {
        if (!Files.exists(configPath) || !Files.isRegularFile(configPath)) {
            return FILE_NOT_FOUND.result();
        }

        return TomlParser.parseFile(configPath)
                         .fold(_ -> PARSE_ERROR.<TomlDocument> result(),
                               Result::success)
                         .flatMap(RouteConfigLoader::buildRouteConfig);
    }

    private static Result<RouteConfig> buildRouteConfig(TomlDocument toml) {
        var errorsConfig = parseErrors(toml);
        var versionNumbers = discoverVersions(toml);
        var hasFlatRoutes = !toml.keys("routes").isEmpty();

        return VersionSchemaValidator.checkSchemaMixing(hasFlatRoutes,
                                                        versionNumbers.size())
                                     .map(Causes::cause)
                                     .map(Cause::<RouteConfig> result)
                                     .or(() -> versionNumbers.isEmpty()
                                               ? buildUnversionedConfig(toml, errorsConfig)
                                               : buildVersionedConfig(toml, errorsConfig, versionNumbers));
    }

    private static Result<RouteConfig> buildUnversionedConfig(TomlDocument toml, ErrorPatternConfig errorsConfig) {
        var prefix = toml.getString("", "prefix").or("");

        return parseSecurity(toml).flatMap(security -> parseRoutesWithSecurity(toml,
                                                                               "routes",
                                                                               security.securityDefault()).map(routesPair -> RouteConfig.routeConfig(prefix,
                                                                                                                                                     routesPair.routes(),
                                                                                                                                                     errorsConfig,
                                                                                                                                                     security.securityDefault(),
                                                                                                                                                     security.overridePolicy(),
                                                                                                                                                     routesPair.routeSecurity())));
    }

    /// Build a versioned [RouteConfig] (#198 §4, §6.4). Each `[vN.routes]` block is resolved (D8
    /// bind-key binding) and its `[vN]` metadata parsed. Routes are flattened into the standard
    /// `routes` map keyed by resolved `getV{N}` method names, but their paths stay UN-versioned
    /// (no baked `/v{N}/`): the per-handler version is recorded in `routeVersions` and the mounted
    /// path `{apiPrefix}/v{N}/{path}` is composed at route-registration time so the same compiled
    /// slice can be exposed in either path mode or header mode. The `prefix` is left empty for
    /// versioned slices; `apiPrefix` carries the version-agnostic base prefix. The per-version
    /// metadata is retained in the `versions` map for later phases.
    private static Result<RouteConfig> buildVersionedConfig(TomlDocument toml,
                                                            ErrorPatternConfig errorsConfig,
                                                            List<Integer> versionNumbers) {
        var apiPrefix = toml.getString("api", "prefix").or("");
        var requireVersionHeader = toml.getBoolean("api", "requireVersionHeader").or(false);

        return parseSecurity(toml).flatMap(security -> parseVersions(toml, versionNumbers, security.securityDefault()).map(versions -> assembleVersionedConfig(apiPrefix,
                                                                                                                                                               requireVersionHeader,
                                                                                                                                                               versions,
                                                                                                                                                               errorsConfig,
                                                                                                                                                               security)));
    }

    private static RouteConfig assembleVersionedConfig(String apiPrefix,
                                                       boolean requireVersionHeader,
                                                       List<VersionConfig> versions,
                                                       ErrorPatternConfig errorsConfig,
                                                       SecuritySection security) {
        var flatRoutes = new LinkedHashMap<String, RouteDsl>();
        var flatRouteSecurity = new LinkedHashMap<String, RouteSecurityLevel>();
        var versionMap = new LinkedHashMap<Integer, VersionConfig>();
        var routeVersions = new LinkedHashMap<String, Integer>();

        for (var version : versions) {
            flatRoutes.putAll(version.routes());
            flatRouteSecurity.putAll(version.routeSecurity());
            versionMap.put(version.version(), version);
            version.routes().keySet().forEach(methodName -> routeVersions.put(methodName, version.version()));
        }

        return RouteConfig.routeConfig("",
                                       flatRoutes,
                                       errorsConfig,
                                       security.securityDefault(),
                                       security.overridePolicy(),
                                       flatRouteSecurity,
                                       apiPrefix,
                                       requireVersionHeader,
                                       versionMap,
                                       routeVersions);
    }

    /// Discover the version numbers declared by `[vN.routes]` blocks, sorted ascending.
    private static List<Integer> discoverVersions(TomlDocument toml) {
        var versions = new TreeSet<Integer>();

        for (var section : toml.sectionNames()) {
            var matcher = VERSION_ROUTES_SECTION.matcher(section);

            if (matcher.matches()) {
                versions.add(Integer.parseInt(matcher.group(1)));
            }
        }

        return new ArrayList<>(versions);
    }

    private static Result<List<VersionConfig>> parseVersions(TomlDocument toml,
                                                             List<Integer> versionNumbers,
                                                             RouteSecurityLevel defaultSecurity) {
        var results = versionNumbers.stream().map(version -> parseVersion(toml, version, defaultSecurity)).toList();

        return Result.allOf(results).flatMap(RouteConfigLoader::checkSingleDefaultAcrossVersions);
    }

    private static Result<List<VersionConfig>> checkSingleDefaultAcrossVersions(List<VersionConfig> versions) {
        var defaults = versions.stream()
                               .filter(VersionConfig::defaultIfMissing)
                               .map(VersionConfig::version)
                               .collect(Collectors.toCollection(TreeSet::new));

        return VersionSchemaValidator.checkSingleDefault(defaults)
                                     .map(Causes::cause)
                                     .map(Cause::<List<VersionConfig>> result)
                                     .or(Result.success(versions));
    }

    /// Parse one `[vN.routes]` block plus its `[vN]` metadata into a [VersionConfig].
    private static Result<VersionConfig> parseVersion(TomlDocument toml,
                                                      int version,
                                                      RouteSecurityLevel defaultSecurity) {
        var routesSection = "v" + version + ".routes";
        var bindKeys = new ArrayList<>(toml.keys(routesSection));

        return VersionSchemaValidator.checkDuplicateBindKey(version, bindKeys)
                                     .map(Causes::cause)
                                     .map(Cause::<VersionConfig> result)
                                     .or(() -> buildVersion(toml, version, bindKeys, defaultSecurity));
    }

    private static Result<VersionConfig> buildVersion(TomlDocument toml,
                                                      int version,
                                                      List<String> bindKeys,
                                                      RouteSecurityLevel defaultSecurity) {
        var routeResults = bindKeys.stream()
                                   .map(bindKey -> parseVersionedRoute(toml, version, bindKey, defaultSecurity))
                                   .toList();

        return Result.allOf(routeResults).flatMap(parsed -> assembleVersion(toml, version, parsed));
    }

    private static Result<VersionConfig> assembleVersion(TomlDocument toml, int version, List<VersionedRoute> parsed) {
        var routes = new LinkedHashMap<String, RouteDsl>();
        var security = new LinkedHashMap<String, RouteSecurityLevel>();
        var bindKeyToMethod = new LinkedHashMap<String, String>();

        for (var route : parsed) {
            routes.put(route.methodName(), route.dsl());
            route.security().onPresent(sec -> security.put(route.methodName(), sec));
            bindKeyToMethod.put(route.bindKey(), route.methodName());
        }

        var metaSection = "v" + version;
        var deprecated = toml.getBoolean(metaSection, "deprecated").or(false);
        var defaultIfMissing = toml.getBoolean(metaSection, "defaultIfMissing").or(false);
        var sunset = toml.getString(metaSection, "sunset");

        return validateSunset(version, sunset).map(_ -> new VersionConfig(version,
                                                                          Collections.unmodifiableMap(routes),
                                                                          Collections.unmodifiableMap(security),
                                                                          Collections.unmodifiableMap(bindKeyToMethod),
                                                                          deprecated,
                                                                          sunset,
                                                                          defaultIfMissing));
    }

    private static Result<Option<String>> validateSunset(int version, Option<String> sunset) {
        return sunset.map(value -> VersionSchemaValidator.checkSunsetFormat(version, value)
                                                         .map(Causes::cause)
                                                         .map(Cause::<Option<String>> result)
                                                         .or(Result.success(sunset)))
                     .or(Result.success(sunset));
    }

    /// Parse one bind key in a `[vN.routes]` block: resolve the slice method name (D8: `get` →
    /// `getV{N}`, unless an inline-table `method = "..."` override). The route path is kept
    /// un-versioned; the `/v{N}` segment is composed at route-registration time (#198 §6.4) from
    /// the recorded per-handler version, not baked into the path here.
    private static Result<VersionedRoute> parseVersionedRoute(TomlDocument toml,
                                                              int version,
                                                              String bindKey,
                                                              RouteSecurityLevel defaultSecurity) {
        return parseRouteWithSecurity(toml, "v" + version + ".routes", bindKey, defaultSecurity).map(parsed -> toVersionedRoute(version,
                                                                                                                                parsed));
    }

    private static VersionedRoute toVersionedRoute(int version, ParsedRoute parsed) {
        var methodName = parsed.methodOverride().or(() -> parsed.name() + "V" + version);

        return new VersionedRoute(parsed.name(), methodName, parsed.dsl(), parsed.security());
    }

    /// Load and merge base configuration with slice-specific configuration.
    ///
    /// Looks for:
    ///
    ///   - `routes-base.toml` - base configuration (optional)
    ///   - `routes.toml` - slice-specific configuration (optional)
    ///
    ///
    /// If neither file exists, returns empty configuration.
    ///
    /// @param slicePackagePath path to the slice package directory
    /// @return Result containing merged RouteConfig (empty if no config files found)
    public static Result<RouteConfig> loadMerged(Path slicePackagePath) {
        var basePath = slicePackagePath.resolve(BASE_CONFIG_FILE);
        var slicePath = slicePackagePath.resolve(CONFIG_FILE);
        var baseConfig = Files.exists(basePath)
                         ? load(basePath).or(RouteConfig.EMPTY)
                         : RouteConfig.EMPTY;

        if (!Files.exists(slicePath)) {
            return Result.success(baseConfig);
        }

        return load(slicePath).map(sliceConfig -> baseConfig.merge(Option.some(sliceConfig)));
    }

    private static Result<SecuritySection> parseSecurity(TomlDocument toml) {
        if (!toml.hasSection("security")) {
            return Result.success(DEFAULT_SECURITY);
        }

        var secDefault = toml.getString("security", "default")
                             .map(RouteSecurityLevel::parse)
                             .or(Result.success(RouteSecurityLevel.AUTHENTICATED));
        var policy = toml.getString("security", "override_policy")
                         .map(OverridePolicy::parse)
                         .or(Result.success(OverridePolicy.STRENGTHEN_ONLY));

        return Result.all(secDefault, policy).map(SecuritySection::new);
    }

    private static Result<RoutesPair> parseRoutesWithSecurity(TomlDocument toml,
                                                              String section,
                                                              RouteSecurityLevel defaultSecurity) {
        var routeKeys = toml.keys(section);
        var routeResults = routeKeys.stream()
                                    .map(key -> parseRouteWithSecurity(toml, section, key, defaultSecurity))
                                    .toList();

        return Result.allOf(routeResults).map(RouteConfigLoader::buildRoutesPair);
    }

    private static Result<ParsedRoute> parseRouteWithSecurity(TomlDocument toml,
                                                              String section,
                                                              String key,
                                                              RouteSecurityLevel defaultSecurity) {
        return toml.getInlineTable(section, key)
                   .map(table -> parseInlineTableRoute(key, table))
                   .or(() -> toml.getStringList(section, key)
                                 .map(list -> parseArrayRoute(key, list))
                                 .or(() -> parseStringRoute(toml, section, key, defaultSecurity)));
    }

    /// Parse the inline-table route form (decision D2):
    /// `{ route = "POST /", consumes = "application/json", produces = "text/csv", security = "public", method = "fetchById" }`.
    /// `route` is required; `consumes`/`produces` default to [MediaType#JSON]; `security` is an optional override;
    /// `method` is an optional slice-method override (D8) used only by versioned `[vN.routes]` bind keys.
    private static Result<ParsedRoute> parseInlineTableRoute(String key, Map<String, Object> table) {
        return Option.option(table.get("route"))
                     .map(Object::toString)
                     .toResult(Causes.cause("Inline-table route must have a 'route' field: " + key))
                     .flatMap(routeStr -> buildInlineRoute(key, table, routeStr));
    }

    private static Result<ParsedRoute> buildInlineRoute(String key, Map<String, Object> table, String routeStr) {
        var methodOverride = Option.option(table.get("method")).map(Object::toString);

        return Result.all(resolveMedia(table, "consumes"), resolveMedia(table, "produces"), resolveInlineSecurity(table)).flatMap((consumes, produces, security) -> RouteDsl.parse(routeStr,
                                                                                                                                                                                   consumes,
                                                                                                                                                                                   produces).map(dsl -> new ParsedRoute(key,
                                                                                                                                                                                                                        dsl,
                                                                                                                                                                                                                        security,
                                                                                                                                                                                                                        methodOverride)));
    }

    private static Result<MediaType> resolveMedia(Map<String, Object> table, String field) {
        return Option.option(table.get(field))
                     .map(Object::toString)
                     .map(MediaType::mediaType)
                     .or(Result.success(MediaType.JSON));
    }

    private static Result<Option<RouteSecurityLevel>> resolveInlineSecurity(Map<String, Object> table) {
        return Option.option(table.get("security"))
                     .map(Object::toString)
                     .map(value -> RouteSecurityLevel.parse(value).map(Option::some))
                     .or(Result.success(Option.none()));
    }

    private static Result<ParsedRoute> parseArrayRoute(String key, List<String> parts) {
        if (parts.size() < 2) {
            return Causes.cause("Route array must have [dsl, security]: " + key).result();
        }

        var dslResult = RouteDsl.parse(parts.getFirst());
        var secResult = RouteSecurityLevel.parse(parts.get(1));

        return Result.all(dslResult, secResult).map((dsl, sec) -> new ParsedRoute(key,
                                                                                  dsl,
                                                                                  Option.some(sec),
                                                                                  Option.none()));
    }

    private static Result<ParsedRoute> parseStringRoute(TomlDocument toml,
                                                        String section,
                                                        String key,
                                                        RouteSecurityLevel defaultSecurity) {
        return toml.getString(section, key)
                   .map(RouteDsl::parse)
                   .or(Causes.cause("Missing route value: " + key).result())
                   .map(dsl -> new ParsedRoute(key,
                                               dsl,
                                               Option.none(),
                                               Option.none()));
    }

    private static RoutesPair buildRoutesPair(List<ParsedRoute> parsed) {
        var routes = new LinkedHashMap<String, RouteDsl>();
        var security = new LinkedHashMap<String, RouteSecurityLevel>();

        for (var entry : parsed) {
            routes.put(entry.name(), entry.dsl());
            entry.security().onPresent(sec -> security.put(entry.name(), sec));
        }

        return new RoutesPair(Collections.unmodifiableMap(routes), Collections.unmodifiableMap(security));
    }

    private static ErrorPatternConfig parseErrors(TomlDocument toml) {
        var defaultStatus = toml.getInt("errors", "default").or(500);
        var statusPatterns = parseStatusPatterns(toml);
        var explicitMappings = parseExplicitMappings(toml);
        var strict = toml.getBoolean("errors", "strict").or(false);

        return ErrorPatternConfig.errorPatternConfig(defaultStatus, statusPatterns, explicitMappings, strict);
    }

    /// Parse the status-to-patterns entries of `[errors]`. A status key is EITHER the legacy
    /// `HTTP_<code>` form (e.g. `HTTP_404`) OR a bare numeric code (e.g. `404`, #385); both may hold
    /// glob patterns (`*NotFound*`) and/or exact type references (`SeatError.SeatNotFound`). The
    /// `default`, `strict`, and any other non-numeric keys are handled elsewhere and skipped here.
    private static Map<Integer, List<String>> parseStatusPatterns(TomlDocument toml) {
        var patterns = new HashMap<Integer, List<String>>();
        var errorsSection = toml.getSection("errors");

        for (var entry : errorsSection.entrySet()) {
            var key = entry.getKey();
            var statusCode = parseStatusKey(key);

            if (statusCode > 0) {
                var patternList = toml.getStringList("errors", key).or(List.of());

                if (!patternList.isEmpty()) {
                    patterns.merge(statusCode, patternList, RouteConfigLoader::concatPatterns);
                }
            }
        }

        return Map.copyOf(patterns);
    }

    private static List<String> concatPatterns(List<String> existing, List<String> added) {
        var combined = new ArrayList<>(existing);

        combined.addAll(added);

        return List.copyOf(combined);
    }

    private static Map<String, Integer> parseExplicitMappings(TomlDocument toml) {
        var mappings = new HashMap<String, Integer>();
        var explicitSection = toml.getSection("errors.explicit");

        for (var entry : explicitSection.entrySet()) {
            var typeName = entry.getKey();

            parseStatusCodeSafely(entry.getValue()).onPresent(statusCode -> mappings.put(typeName, statusCode));
        }

        return Map.copyOf(mappings);
    }

    private static Option<Integer> parseStatusCodeSafely(String value) {
        try {
            return Option.some(Integer.parseInt(value));
        } catch (NumberFormatException _) {
            return Option.none();
        }
    }

    /// Resolve the HTTP status code a `[errors]` key denotes: the legacy `HTTP_<code>` form or a
    /// bare numeric `<code>` (#385). Returns `-1` for non-status keys (`default`, `strict`, ...).
    private static int parseStatusKey(String key) {
        return key.startsWith("HTTP_")
               ? parseHttpStatus(key)
               : parseBareStatus(key);
    }

    private static int parseBareStatus(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    private static int parseHttpStatus(String key) {
        try {
            return Integer.parseInt(key.substring(5));
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /// Internal record for parsed security section values.
    private record SecuritySection(RouteSecurityLevel securityDefault, OverridePolicy overridePolicy) {}

    /// Internal record for a parsed route entry with optional security override and optional
    /// slice-method override (`method = "..."`, used by versioned `[vN.routes]` bind keys, D8).
    private record ParsedRoute(String name,
                               RouteDsl dsl,
                               Option<RouteSecurityLevel> security,
                               Option<String> methodOverride) {}

    /// Internal record for a parsed versioned route: the original bind key, the resolved slice
    /// method name (`getV{N}` or an explicit `method` override), the `/v{N}`-prefixed RouteDsl,
    /// and any per-route security override.
    private record VersionedRoute(String bindKey,
                                  String methodName,
                                  RouteDsl dsl,
                                  Option<RouteSecurityLevel> security) {}

    /// Internal record holding separated routes and route security maps.
    private record RoutesPair(Map<String, RouteDsl> routes, Map<String, RouteSecurityLevel> routeSecurity) {}
}
