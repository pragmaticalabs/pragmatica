package org.pragmatica.aether.forge;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.pragmatica.lang.Option.option;

/// Startup configuration merged from CLI arguments and environment variables.
/// Priority: environment variables > CLI arguments > defaults.
public record StartupConfig(Option<Path> forgeConfig,
                            Option<Path> blueprint,
                            Option<Path> loadConfig,
                            boolean autoStart,
                            int port,
                            int clusterSize,
                            int loadRate) {
    private static final int DEFAULT_PORT = 8888;
    private static final int DEFAULT_CLUSTER_SIZE = 5;
    private static final int DEFAULT_LOAD_RATE = 1000;

    /// Parse startup configuration from CLI args with environment variable overrides.
    public static Result<StartupConfig> startupConfig(String[] args) {
        var parsed = parseArgs(args);
        // Apply env var overrides (highest priority)
        var forgeConfigPath = envOrArg("FORGE_CONFIG", parsed, "config");
        var blueprintPath = envOrArg("FORGE_BLUEPRINT", parsed, "blueprint");
        var loadConfigPath = envOrArg("FORGE_LOAD_CONFIG", parsed, "load-config");
        var autoStart = envOrArgBool("FORGE_AUTO_START", parsed, "auto-start");
        // Cluster settings from env vars (backwards compatible)
        var port = envOrArgInt("FORGE_PORT", parsed, "port", DEFAULT_PORT);
        var clusterSize = envOrArgInt("CLUSTER_SIZE", parsed, "cluster-size", DEFAULT_CLUSTER_SIZE);
        var loadRate = envOrArgInt("LOAD_RATE", parsed, "load-rate", DEFAULT_LOAD_RATE);
        // Validate paths exist
        return validatePath(forgeConfigPath, "FORGE_CONFIG")
        .flatMap(forgeConfig -> validatePath(blueprintPath, "FORGE_BLUEPRINT")
        .flatMap(blueprint -> validatePath(loadConfigPath, "FORGE_LOAD_CONFIG")
        .map(loadConfig -> new StartupConfig(forgeConfig, blueprint, loadConfig, autoStart, port, clusterSize, loadRate))));
    }

    private static Map<String, String> parseArgs(String[] args) {
        var result = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    result.put(key, args[++ i]);
                } else {
                    // Flag without value (like --auto-start)
                    result.put(key, "true");
                }
            }
        }
        return result;
    }

    private static Option<String> envOrArg(String envName, Map<String, String> args, String argName) {
        return option(System.getenv(envName)).orElse(() -> option(args.get(argName)));
    }

    private static boolean envOrArgBool(String envName, Map<String, String> args, String argName) {
        return envOrArg(envName, args, argName).map(v -> v.equalsIgnoreCase("true") || v.equals("1"))
                       .or(false);
    }

    private static int envOrArgInt(String envName, Map<String, String> args, String argName, int defaultValue) {
        return envOrArg(envName, args, argName).flatMap(StartupConfig::parseIntSafe)
                       .or(defaultValue);
    }

    private static Option<Integer> parseIntSafe(String value) {
        try{
            return Option.some(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return Option.none();
        }
    }

    private static Result<Option<Path>> validatePath(Option<String> pathStr, String name) {
        return pathStr.map(s -> validateSinglePath(Path.of(s),
                                                   name))
                      .or(Result.success(Option.none()));
    }

    private static Result<Option<Path>> validateSinglePath(Path path, String name) {
        if (!Files.exists(path)) {
            return StartupError.fileNotFound(name, path)
                               .result();
        }
        if (!Files.isReadable(path)) {
            return StartupError.fileNotReadable(name, path)
                               .result();
        }
        return Result.success(Option.some(path));
    }

    /// Startup configuration errors.
    public sealed interface StartupError extends org.pragmatica.lang.Cause {
        record FileNotFound(String configName, Path path) implements StartupError {
            @Override
            public String message() {
                return configName + " file not found: " + path;
            }
        }

        record FileNotReadable(String configName, Path path) implements StartupError {
            @Override
            public String message() {
                return configName + " file not readable: " + path;
            }
        }

        static StartupError fileNotFound(String name, Path path) {
            return new FileNotFound(name, path);
        }

        static StartupError fileNotReadable(String name, Path path) {
            return new FileNotReadable(name, path);
        }
    }
}
