package org.pragmatica.jbct.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads JBCT configuration with priority:
 * 1. Explicit config (e.g., from CLI args) - highest
 * 2. Project config (./jbct.toml)
 * 3. User config (~/.jbct/config.toml)
 * 4. Built-in defaults - lowest
 */
public final class ConfigLoader {

    private static final String PROJECT_CONFIG_NAME = "jbct.toml";
    private static final String USER_CONFIG_DIR = ".jbct";
    private static final String USER_CONFIG_NAME = "config.toml";

    private ConfigLoader() {}

    /**
     * Load configuration with optional explicit config file and working directory.
     *
     * @param explicitConfigPath Optional path to explicit config file (highest priority)
     * @param workingDirectory   Optional working directory for project config lookup
     */
    public static JbctConfig load(Option<Path> explicitConfigPath, Option<Path> workingDirectory) {
        // Start with defaults
        JbctConfig config = JbctConfig.DEFAULT;

        // Layer 1: User config (~/.jbct/config.toml)
        var userConfig = loadUserConfig();
        if (userConfig.isPresent()) {
            config = config.merge(userConfig.unwrap());
        }

        // Layer 2: Project config (./jbct.toml)
        var projectDir = workingDirectory.or(() -> Path.of(System.getProperty("user.dir")));
        var projectConfig = loadProjectConfig(projectDir);
        if (projectConfig.isPresent()) {
            config = config.merge(projectConfig.unwrap());
        }

        // Layer 3: Explicit config file (highest priority)
        if (explicitConfigPath.isPresent()) {
            var explicitConfig = loadFromFile(explicitConfigPath.unwrap());
            if (explicitConfig.isPresent()) {
                config = config.merge(explicitConfig.unwrap());
            }
        }

        return config;
    }

    /**
     * Load user-level config from ~/.jbct/config.toml.
     */
    public static Option<JbctConfig> loadUserConfig() {
        var userHome = System.getProperty("user.home");
        if (userHome == null) {
            return Option.none();
        }
        var configPath = Path.of(userHome, USER_CONFIG_DIR, USER_CONFIG_NAME);
        return loadFromFile(configPath);
    }

    /**
     * Load project-level config from jbct.toml in given directory.
     */
    public static Option<JbctConfig> loadProjectConfig(Path directory) {
        var configPath = directory.resolve(PROJECT_CONFIG_NAME);
        return loadFromFile(configPath);
    }

    /**
     * Load config from a specific file.
     */
    public static Option<JbctConfig> loadFromFile(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return Option.none();
        }

        return readFile(path)
                .flatMap(TomlParser::parse)
                .map(JbctConfig::fromToml)
                .fold(
                        _ -> Option.none(),
                        Option::option
                );
    }

    /**
     * Find the project config file, searching up the directory tree.
     */
    public static Option<Path> findProjectConfig(Path startDir) {
        var dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            var configPath = dir.resolve(PROJECT_CONFIG_NAME);
            if (Files.exists(configPath) && Files.isRegularFile(configPath)) {
                return Option.option(configPath);
            }
            dir = dir.getParent();
        }
        return Option.none();
    }

    /**
     * Get the default user config directory path.
     */
    public static Path getUserConfigDir() {
        var userHome = System.getProperty("user.home");
        return Path.of(userHome, USER_CONFIG_DIR);
    }

    /**
     * Get the default user config file path.
     */
    public static Path getUserConfigPath() {
        return getUserConfigDir().resolve(USER_CONFIG_NAME);
    }

    private static Result<String> readFile(Path path) {
        return Result.lift(() -> Files.readString(path));
    }
}
