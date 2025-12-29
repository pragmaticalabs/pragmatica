package org.pragmatica.jbct.config;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads JBCT configuration with priority:
 * 1. Explicit config (e.g., from CLI args) - highest
 * 2. Project config (./jbct.toml)
 * 3. User config (~/.jbct/config.toml)
 * 4. Built-in defaults - lowest
 */
public sealed interface ConfigLoader permits ConfigLoader.unused {
    record unused() implements ConfigLoader {}

    String PROJECT_CONFIG_NAME = "jbct.toml";
    String USER_CONFIG_DIR = ".jbct";
    String USER_CONFIG_NAME = "config.toml";

    /**
     * Load configuration with optional explicit config file and working directory.
     *
     * @param explicitConfigPath Optional path to explicit config file (highest priority)
     * @param workingDirectory   Optional working directory for project config lookup
     */
    static JbctConfig load(Option<Path> explicitConfigPath, Option<Path> workingDirectory) {
        // Start with defaults
        var config = JbctConfig.DEFAULT;
        // Layer 1: User config (~/.jbct/config.toml)
        config = loadUserConfig()
                 .map(config::merge)
                 .or(config);
        // Layer 2: Project config (./jbct.toml)
        var projectDir = workingDirectory.or(() -> Path.of(System.getProperty("user.dir")));
        config = loadProjectConfig(projectDir)
                 .map(config::merge)
                 .or(config);
        // Layer 3: Explicit config file (highest priority)
        var finalConfig = config;
        return explicitConfigPath.flatMap(ConfigLoader::loadFromFile)
                                 .map(finalConfig::merge)
                                 .or(finalConfig);
    }

    /**
     * Load user-level config from ~/.jbct/config.toml.
     */
    static Option<JbctConfig> loadUserConfig() {
        return Option.option(System.getProperty("user.home"))
                     .map(home -> Path.of(home, USER_CONFIG_DIR, USER_CONFIG_NAME))
                     .flatMap(ConfigLoader::loadFromFile);
    }

    /**
     * Load project-level config from jbct.toml in given directory.
     */
    static Option<JbctConfig> loadProjectConfig(Path directory) {
        var configPath = directory.resolve(PROJECT_CONFIG_NAME);
        return loadFromFile(configPath);
    }

    /**
     * Load config from a specific file.
     */
    static Option<JbctConfig> loadFromFile(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return Option.none();
        }
        return TomlParser.parseFile(path)
                         .map(JbctConfig::fromToml)
                         .fold(_ -> Option.none(),
                               Option::option);
    }

    /**
     * Find the project config file, searching up the directory tree.
     */
    static Option<Path> findProjectConfig(Path startDir) {
        var dir = startDir.toAbsolutePath()
                          .normalize();
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
    static Path getUserConfigDir() {
        var userHome = System.getProperty("user.home");
        return Path.of(userHome, USER_CONFIG_DIR);
    }

    /**
     * Get the default user config file path.
     */
    static Path getUserConfigPath() {
        return getUserConfigDir()
               .resolve(USER_CONFIG_NAME);
    }
}
