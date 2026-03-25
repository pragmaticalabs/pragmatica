package org.pragmatica.jbct.slice.routing;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final SecuritySection DEFAULT_SECURITY =
        new SecuritySection(RouteSecurityLevel.PUBLIC, OverridePolicy.STRENGTHEN_ONLY);

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
                         .fold(_ -> PARSE_ERROR.<TomlDocument>result(),
                               Result::success)
                         .flatMap(RouteConfigLoader::buildRouteConfig);
    }

    private static Result<RouteConfig> buildRouteConfig(TomlDocument toml) {
        var prefix = toml.getString("", "prefix")
                         .or("");
        var errorsConfig = parseErrors(toml);

        return parseSecurity(toml)
            .flatMap(security -> parseRoutesWithSecurity(toml, security.securityDefault())
                .map(routesPair -> RouteConfig.routeConfig(prefix,
                                                           routesPair.routes(),
                                                           errorsConfig,
                                                           security.securityDefault(),
                                                           security.overridePolicy(),
                                                           routesPair.routeSecurity())));
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

        return load(slicePath)
            .map(sliceConfig -> baseConfig.merge(Option.some(sliceConfig)));
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

        return Result.all(secDefault, policy)
                     .map(SecuritySection::new);
    }

    private static Result<RoutesPair> parseRoutesWithSecurity(TomlDocument toml,
                                                              RouteSecurityLevel defaultSecurity) {
        var routeKeys = toml.keys("routes");
        var routeResults = routeKeys.stream()
                                    .map(key -> parseRouteWithSecurity(toml, key, defaultSecurity))
                                    .toList();

        return Result.allOf(routeResults)
                     .map(RouteConfigLoader::buildRoutesPair);
    }

    private static Result<ParsedRoute> parseRouteWithSecurity(TomlDocument toml,
                                                              String key,
                                                              RouteSecurityLevel defaultSecurity) {
        return toml.getStringList("routes", key)
                   .map(list -> parseArrayRoute(key, list))
                   .or(() -> parseStringRoute(toml, key, defaultSecurity));
    }

    private static Result<ParsedRoute> parseArrayRoute(String key, List<String> parts) {
        if (parts.size() < 2) {
            return Causes.cause("Route array must have [dsl, security]: " + key).result();
        }

        var dslResult = RouteDsl.parse(parts.getFirst());
        var secResult = RouteSecurityLevel.parse(parts.get(1));

        return Result.all(dslResult, secResult)
                     .map((dsl, sec) -> new ParsedRoute(key, dsl, Option.some(sec)));
    }

    private static Result<ParsedRoute> parseStringRoute(TomlDocument toml,
                                                        String key,
                                                        RouteSecurityLevel defaultSecurity) {
        return toml.getString("routes", key)
                   .map(RouteDsl::parse)
                   .or(Causes.cause("Missing route value: " + key).result())
                   .map(dsl -> new ParsedRoute(key, dsl, Option.none()));
    }

    private static RoutesPair buildRoutesPair(List<ParsedRoute> parsed) {
        var routes = new LinkedHashMap<String, RouteDsl>();
        var security = new LinkedHashMap<String, RouteSecurityLevel>();

        for (var entry : parsed) {
            routes.put(entry.name(), entry.dsl());
            entry.security().onPresent(sec -> security.put(entry.name(), sec));
        }

        return new RoutesPair(Collections.unmodifiableMap(routes),
                              Collections.unmodifiableMap(security));
    }

    private static ErrorPatternConfig parseErrors(TomlDocument toml) {
        var defaultStatus = toml.getInt("errors", "default")
                                .or(500);
        var statusPatterns = parseStatusPatterns(toml);
        var explicitMappings = parseExplicitMappings(toml);
        return ErrorPatternConfig.errorPatternConfig(defaultStatus, statusPatterns, explicitMappings);
    }

    private static Map<Integer, List<String>> parseStatusPatterns(TomlDocument toml) {
        var patterns = new HashMap<Integer, List<String>>();
        var errorsSection = toml.getSection("errors");
        for (var entry : errorsSection.entrySet()) {
            var key = entry.getKey();
            if (key.startsWith("HTTP_")) {
                var statusCode = parseHttpStatus(key);
                if (statusCode > 0) {
                    var patternList = toml.getStringList("errors", key)
                                          .or(List.of());
                    if (!patternList.isEmpty()) {
                        patterns.put(statusCode, patternList);
                    }
                }
            }
        }
        return Map.copyOf(patterns);
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
        try{
            return Option.some(Integer.parseInt(value));
        } catch (NumberFormatException _) {
            return Option.none();
        }
    }

    private static int parseHttpStatus(String key) {
        try{
            return Integer.parseInt(key.substring(5));
        } catch (NumberFormatException _) {
            return - 1;
        }
    }

    /// Internal record for parsed security section values.
    private record SecuritySection(RouteSecurityLevel securityDefault,
                                   OverridePolicy overridePolicy) {}

    /// Internal record for a parsed route entry with optional security override.
    private record ParsedRoute(String name,
                               RouteDsl dsl,
                               Option<RouteSecurityLevel> security) {}

    /// Internal record holding separated routes and route security maps.
    private record RoutesPair(Map<String, RouteDsl> routes,
                              Map<String, RouteSecurityLevel> routeSecurity) {}
}
