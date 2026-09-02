package org.pragmatica.jbct.config;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.FileOps;


/// Loads JBCT configuration by folding layers per key, lowest priority first:
/// 1. Built-in defaults - lowest
/// 2. User config (`~/.jbct/config.toml`)
/// 3. Every `jbct.toml` from the repository root down to the working directory
/// 4. Explicit config (e.g., from CLI args) - highest
///
/// Layers are folded key by key ([PartialConfig#mergeWith]), not section by section, so a nearer
/// layer overrides only the keys it actually writes and the built-in defaults are applied exactly
/// once, at the end. That makes `--config` a genuine per-key override rather than a wholesale
/// replacement, and lets a nearer layer clear an inherited list with `key = []`.
///
/// The `jbct.toml` walk stops at (and includes) the first ancestor holding a `.git` entry, so a
/// stray `jbct.toml` in `$HOME` or in a parent workspace never joins the chain.
public sealed interface ConfigLoader permits ConfigLoader.unused {
    record unused() implements ConfigLoader {}

    String PROJECT_CONFIG_NAME = "jbct.toml";
    String USER_CONFIG_DIR = ".jbct";

    String USER_CONFIG_NAME = "config.toml";

    /// Repository boundary marker — a directory in a normal clone, a file in a git worktree.
    String REPOSITORY_MARKER = ".git";

    /// Load configuration with optional explicit config file and working directory.
    ///
    /// @param explicitConfigPath Optional path to explicit config file (highest priority)
    /// @param workingDirectory   Optional working directory for project config lookup
    static JbctConfig load(Option<Path> explicitConfigPath, Option<Path> workingDirectory) {
        return foldLayers(layerPaths(explicitConfigPath, startDirectory(workingDirectory)));
    }

    /// Load user-level config from ~/.jbct/config.toml.
    static Option<JbctConfig> loadUserConfig() {
        return userConfigCandidate().flatMap(ConfigLoader::loadFromFile);
    }

    /// Load project-level config from jbct.toml in given directory.
    static Option<JbctConfig> loadProjectConfig(Path directory) {
        return loadFromFile(directory.resolve(PROJECT_CONFIG_NAME));
    }

    /// Load config from a specific file, materialised on its own against the built-in defaults.
    static Option<JbctConfig> loadFromFile(Path path) {
        return parseLayer(path).map(PartialConfig::materialize);
    }

    /// Find every `jbct.toml` from the repository root down to `startDir`, outermost first.
    ///
    /// The walk climbs from `startDir` and stops at the first directory holding [#REPOSITORY_MARKER],
    /// including that directory's own config.
    static List<Path> findProjectConfigChain(Path startDir) {
        return collectChain(Option.some(startDir.toAbsolutePath()
                                                .normalize()));
    }

    /// Get the default user config directory path.
    static Path getUserConfigDir() {
        var userHome = System.getProperty("user.home");

        return Path.of(userHome, USER_CONFIG_DIR);
    }

    /// Get the default user config file path.
    static Path getUserConfigPath() {
        return getUserConfigDir().resolve(USER_CONFIG_NAME);
    }

    private static JbctConfig foldLayers(List<Path> paths) {
        return paths.stream()
                    .flatMap(ConfigLoader::layerStream)
                    .reduce(PartialConfig.EMPTY, PartialConfig::mergeWith)
                    .materialize();
    }

    private static List<Path> layerPaths(Option<Path> explicitConfigPath, Path startDir) {
        var inheritedLayers = Stream.concat(userConfigCandidate().stream(),
                                            findProjectConfigChain(startDir).stream());

        return Stream.concat(inheritedLayers, explicitConfigPath.stream())
                     .toList();
    }

    private static Path startDirectory(Option<Path> workingDirectory) {
        return workingDirectory.or(() -> Path.of(System.getProperty("user.dir")));
    }

    private static Option<Path> userConfigCandidate() {
        return Option.option(System.getProperty("user.home"))
                     .map(home -> Path.of(home, USER_CONFIG_DIR, USER_CONFIG_NAME));
    }

    private static Stream<PartialConfig> layerStream(Path path) {
        return parseLayer(path).stream();
    }

    private static Option<PartialConfig> parseLayer(Path path) {
        return FileOps.isRegularFile(path)
               ? TomlParser.parseFile(path)
                           .map(PartialConfig::partialConfig)
                           .option()
               : Option.none();
    }

    private static List<Path> collectChain(Option<Path> dirOpt) {
        return dirOpt.map(ConfigLoader::collectChainFrom)
                     .or(List.of());
    }

    private static List<Path> collectChainFrom(Path dir) {
        return isRepositoryRoot(dir)
               ? configIn(dir)
               : concat(collectChain(Option.option(dir.getParent())), configIn(dir));
    }

    private static boolean isRepositoryRoot(Path dir) {
        return FileOps.exists(dir.resolve(REPOSITORY_MARKER));
    }

    private static List<Path> configIn(Path dir) {
        var configPath = dir.resolve(PROJECT_CONFIG_NAME);

        return FileOps.isRegularFile(configPath)
               ? List.of(configPath)
               : List.of();
    }

    private static List<Path> concat(List<Path> outer, List<Path> inner) {
        return Stream.concat(outer.stream(), inner.stream())
                     .toList();
    }
}
