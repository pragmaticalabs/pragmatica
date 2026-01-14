package org.pragmatica.jbct.slice.routing;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
public sealed interface RouteConfigLoader permits RouteConfigLoader.unused {
    record unused() implements RouteConfigLoader {}

    String CONFIG_FILE = "routes.toml";
    String BASE_CONFIG_FILE = "routes-base.toml";

    Cause FILE_NOT_FOUND = Causes.cause("Route configuration file not found");
    Cause PARSE_ERROR = Causes.cause("Failed to parse route configuration");

    /**
     * Load route configuration from a specific file.
     *
     * @param configPath path to the TOML configuration file
     * @return Result containing RouteConfig or error
     */
    static Result<RouteConfig> load(Path configPath) {
        if (!Files.exists(configPath) || !Files.isRegularFile(configPath)) {
            return FILE_NOT_FOUND.result();
        }
        return TomlParser.parseFile(configPath)
                         .fold(_ -> PARSE_ERROR.<org.pragmatica.config.toml.TomlDocument>result(),
                               Result::success)
                         .flatMap(toml -> {
                                      var prefix = toml.getString("", "prefix")
                                                       .or("");
                                      var routesResult = parseRoutes(toml.getSection("routes"));
                                      var errorsConfig = parseErrors(toml);
                                      return routesResult.map(routes -> RouteConfig.routeConfig(prefix,
                                                                                                routes,
                                                                                                errorsConfig));
                                  });
    }

    /**
     * Load and merge base configuration with slice-specific configuration.
     * <p>
     * Looks for:
     * <ul>
     *   <li>{@code routes-base.toml} - base configuration (optional)</li>
     *   <li>{@code routes.toml} - slice-specific configuration (required)</li>
     * </ul>
     *
     * @param slicePackagePath path to the slice package directory
     * @return Result containing merged RouteConfig or error
     */
    static Result<RouteConfig> loadMerged(Path slicePackagePath) {
        var basePath = slicePackagePath.resolve(BASE_CONFIG_FILE);
        var slicePath = slicePackagePath.resolve(CONFIG_FILE);
        var baseConfig = Files.exists(basePath)
                         ? load(basePath)
                               .or(RouteConfig.EMPTY)
                         : RouteConfig.EMPTY;
        return load(slicePath)
                   .map(baseConfig::merge);
    }

    private static Result<Map<String, RouteDsl>> parseRoutes(Map<String, String> routesSection) {
        var results = new ArrayList<Result<Map.Entry<String, RouteDsl>>>();
        for (var entry : routesSection.entrySet()) {
            var handlerName = entry.getKey();
            var dslString = entry.getValue();
            results.add(RouteDsl.parse(dslString)
                                .map(dsl -> Map.entry(handlerName, dsl)));
        }
        return Result.allOf(results)
                     .map(entries -> {
                              var map = new HashMap<String, RouteDsl>();
                              entries.forEach(e -> map.put(e.getKey(),
                                                           e.getValue()));
                              return Map.copyOf(map);
                          });
    }

    private static ErrorPatternConfig parseErrors(org.pragmatica.config.toml.TomlDocument toml) {
        var defaultStatus = toml.getInt("errors", "default")
                                .or(500);
        var statusPatterns = parseStatusPatterns(toml);
        var explicitMappings = parseExplicitMappings(toml);
        return ErrorPatternConfig.errorPatternConfig(defaultStatus, statusPatterns, explicitMappings);
    }

    private static Map<Integer, List<String>> parseStatusPatterns(org.pragmatica.config.toml.TomlDocument toml) {
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

    private static Map<String, Integer> parseExplicitMappings(org.pragmatica.config.toml.TomlDocument toml) {
        var mappings = new HashMap<String, Integer>();
        var explicitSection = toml.getSection("errors.explicit");
        for (var entry : explicitSection.entrySet()) {
            var typeName = entry.getKey();
            try{
                var statusCode = Integer.parseInt(entry.getValue());
                mappings.put(typeName, statusCode);
            } catch (NumberFormatException _) {}
        }
        return Map.copyOf(mappings);
    }

    private static int parseHttpStatus(String key) {
        try{
            return Integer.parseInt(key.substring(5));
        } catch (NumberFormatException _) {
            return - 1;
        }
    }
}
