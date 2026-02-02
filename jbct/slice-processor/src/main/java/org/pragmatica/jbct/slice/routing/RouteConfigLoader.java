package org.pragmatica.jbct.slice.routing;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.System.Logger.Level.WARNING;

/**
 * Loader for route configuration from TOML files.
 * <p>
 * Supports loading configuration from:
 * <ul>
 *   <li>Single config file via {@link #load(Path)}</li>
 *   <li>Merged base + slice config via {@link #loadMerged(Path)}</li>
 * </ul>
 * <p>
 * TOML format:
 * <pre>{@code
 * prefix = "/api/v1"
 *
 * [routes]
 * getUser = "GET /{id:Long}"
 * createUser = "POST /"
 *
 * [errors]
 * default = 500
 * HTTP_404 = ["*NotFound*", "*Missing*"]
 * HTTP_400 = ["*Invalid*"]
 *
 * [errors.explicit]
 * SomeAmbiguousType = 404
 * }</pre>
 */
public final class RouteConfigLoader {
    public static final String CONFIG_FILE = "routes.toml";
    public static final String BASE_CONFIG_FILE = "routes-base.toml";

    private static final System.Logger LOGGER = System.getLogger(RouteConfigLoader.class.getName());
    private static final Cause FILE_NOT_FOUND = Causes.cause("Route configuration file not found");
    private static final Cause PARSE_ERROR = Causes.cause("Failed to parse route configuration");

    private RouteConfigLoader() {}

    /**
     * Load route configuration from a specific file.
     *
     * @param configPath path to the TOML configuration file
     * @return Result containing RouteConfig or error
     */
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
        var routesResult = parseRoutes(toml.getSection("routes"));
        var errorsConfig = parseErrors(toml);
        return routesResult.map(routes -> RouteConfig.routeConfig(prefix, routes, errorsConfig));
    }

    /**
     * Load and merge base configuration with slice-specific configuration.
     * <p>
     * Looks for:
     * <ul>
     *   <li>{@code routes-base.toml} - base configuration (optional)</li>
     *   <li>{@code routes.toml} - slice-specific configuration (optional)</li>
     * </ul>
     * <p>
     * If neither file exists, returns empty configuration.
     *
     * @param slicePackagePath path to the slice package directory
     * @return Result containing merged RouteConfig (empty if no config files found)
     */
    public static Result<RouteConfig> loadMerged(Path slicePackagePath) {
        var basePath = slicePackagePath.resolve(BASE_CONFIG_FILE);
        var slicePath = slicePackagePath.resolve(CONFIG_FILE);
        var baseConfig = Files.exists(basePath)
                         ? load(basePath).or(RouteConfig.EMPTY)
                         : RouteConfig.EMPTY;
        var sliceConfig = Files.exists(slicePath)
                          ? load(slicePath).option()
                          : Option.<RouteConfig>none();
        return Result.success(baseConfig.merge(sliceConfig));
    }

    private static Result<Map<String, RouteDsl>> parseRoutes(Map<String, String> routesSection) {
        var results = routesSection.entrySet()
                                   .stream()
                                   .map(RouteConfigLoader::parseRouteEntry)
                                   .toList();
        return Result.allOf(results)
                     .map(RouteConfigLoader::toImmutableMap);
    }

    private static Result<Map.Entry<String, RouteDsl>> parseRouteEntry(Map.Entry<String, String> entry) {
        return RouteDsl.parse(entry.getValue())
                       .map(dsl -> Map.entry(entry.getKey(),
                                             dsl));
    }

    private static Map<String, RouteDsl> toImmutableMap(List<Map.Entry<String, RouteDsl>> entries) {
        var map = new HashMap<String, RouteDsl>();
        entries.forEach(e -> map.put(e.getKey(), e.getValue()));
        return Map.copyOf(map);
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
            parseStatusCodeSafely(entry.getValue()).onPresent(statusCode -> mappings.put(typeName, statusCode))
                                 .onEmpty(() -> LOGGER.log(WARNING,
                                                           "Invalid status code for type '" + typeName + "': " + entry.getValue()));
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
}
