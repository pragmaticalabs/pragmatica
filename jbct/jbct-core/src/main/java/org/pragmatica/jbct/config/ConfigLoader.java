package org.pragmatica.jbct.config;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Loads JBCT configuration by folding layers per key, lowest priority first:
/// 1. Built-in defaults - lowest
/// 2. User config (`~/.jbct/config.toml`)
/// 3. Every `jbct.toml` from the repository root down to the working directory, or, for a source root
///    ([#loadForSourceRoot]), to its module directory
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
    /// Module marker — the file whose directory a source root's configuration walk starts at.
    String MODULE_MARKER = "pom.xml";
    /// Directory segments, consecutive, that make a source root a test root (R2: the CLI's only predicate).
    String TEST_ROOT_PARENT = "src";

    String TEST_ROOT_NAME = "test";

    /// Load configuration with optional explicit config file and working directory.
    ///
    /// @param explicitConfigPath Optional path to explicit config file (highest priority)
    /// @param workingDirectory   Optional working directory for project config lookup
    ///
    /// A layer that cannot be used fails the load as [ConfigError.LayerInvalid], naming its file: a
    /// syntax error (with its line), an unreadable file, or a key of the wrong TOML type (with its
    /// value). Silently skipping such a layer would let a lower layer or the built-in default win over
    /// what the operator wrote (#1455). An absent DISCOVERED file is not a failure (most projects have no
    /// `jbct.toml`); an explicit config path that does not name a regular file is, because the operator
    /// asked for exactly that file.
    static Result<JbctConfig> load(Option<Path> explicitConfigPath, Option<Path> workingDirectory) {
        return foldLayers(discoveredLayers(startDirectory(workingDirectory)), explicitConfigPath);
    }

    /// Load the configuration that applies to a source root (or to any path checked under it, #2525).
    ///
    /// One resolver for the CLI and for Maven: the `jbct.toml` chain starts at the path's [#moduleDirectory], never at
    /// the working directory, so `jbct check <path>` and the module's goal read the same files from any cwd. The
    /// explicit layer (`--config`) is folded last, as in [#load].
    ///
    /// @param explicitConfigPath Optional path to explicit config file (highest priority)
    /// @param sourceRoot         A source root, a file under it, or the directory a command was given
    static Result<JbctConfig> loadForSourceRoot(Option<Path> explicitConfigPath, Path sourceRoot) {
        return load(explicitConfigPath, some(moduleDirectory(sourceRoot)));
    }

    /// The directory a source root's `jbct.toml` walk starts at: the nearest ancestor holding a [#MODULE_MARKER],
    /// searched no higher than the first [#REPOSITORY_MARKER] ancestor; with no marker on that path, the source
    /// root's own directory (R1 (a)), so a `jbct.toml` in `src/` or `src/main/java` is read for such a root only.
    static Path moduleDirectory(Path sourceRoot) {
        var start = startOf(sourceRoot);

        return moduleMarkerDirectory(start).or(start);
    }

    /// The directory a source root's test-root predicate is judged against (O1): the module directory when a
    /// [#MODULE_MARKER] marks one, otherwise the first [#REPOSITORY_MARKER] ancestor, otherwise the path as given.
    static Path boundary(Path sourceRoot) {
        var start = startOf(sourceRoot);

        return moduleMarkerDirectory(start).orElse(() -> repositoryDirectory(start))
                                    .or(start);
    }

    /// Whether a source root is a test root: the path relative to its [#boundary] holds the consecutive segments
    /// `src`, `test`. The absolute path is never read, so a checkout under `~/src/test/<repo>` is not a test root.
    static boolean isTestRoot(Path sourceRoot) {
        var start = startOf(sourceRoot);

        return holdsTestSegments(boundary(sourceRoot).relativize(start));
    }

    /// Load user-level config from ~/.jbct/config.toml.
    static Result<Option<JbctConfig>> loadUserConfig() {
        return userConfigCandidate().map(ConfigLoader::loadFromFile)
                                  .or(success(none()));
    }

    /// Load project-level config from jbct.toml in given directory.
    static Result<Option<JbctConfig>> loadProjectConfig(Path directory) {
        return loadFromFile(directory.resolve(PROJECT_CONFIG_NAME));
    }

    /// Load config from a specific file, materialised on its own against the built-in defaults.
    static Result<Option<JbctConfig>> loadFromFile(Path path) {
        return parseLayer(path).map(layer -> layer.map(PartialConfig::materialize));
    }

    /// Find every `jbct.toml` from the repository root down to `startDir`, outermost first.
    ///
    /// The walk climbs from `startDir` and stops at the first directory holding [#REPOSITORY_MARKER],
    /// including that directory's own config.
    static List<Path> findProjectConfigChain(Path startDir) {
        return collectChain(Option.some(startDir.toAbsolutePath().normalize()));
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

    private static Path startOf(Path sourceRoot) {
        var absolute = sourceRoot.toAbsolutePath().normalize();

        return FileOps.isRegularFile(absolute)
               ? absolute.getParent()
               : absolute;
    }

    private static Option<Path> moduleMarkerDirectory(Path dir) {
        return FileOps.isRegularFile(dir.resolve(MODULE_MARKER))
               ? some(dir)
               : moduleMarkerAbove(dir);
    }

    private static Option<Path> moduleMarkerAbove(Path dir) {
        return isRepositoryRoot(dir)
               ? none()
               : option(dir.getParent()).flatMap(ConfigLoader::moduleMarkerDirectory);
    }

    private static Option<Path> repositoryDirectory(Path dir) {
        return isRepositoryRoot(dir)
               ? some(dir)
               : option(dir.getParent()).flatMap(ConfigLoader::repositoryDirectory);
    }

    private static boolean holdsTestSegments(Path relative) {
        return IntStream.range(0,
                               relative.getNameCount() - 1)
                        .anyMatch(index -> segmentAt(relative, index, TEST_ROOT_PARENT) && segmentAt(relative,
                                                                                                     index + 1,
                                                                                                     TEST_ROOT_NAME));
    }

    private static boolean segmentAt(Path relative, int index, String name) {
        return relative.getName(index)
                       .toString()
                       .equals(name);
    }

    private static Result<JbctConfig> foldLayers(List<Path> discovered, Option<Path> explicit) {
        return Result.allOf(Stream.concat(discovered.stream().map(ConfigLoader::parseLayer),
                                          explicit.stream().map(ConfigLoader::parseExplicitLayer))
                                  .toList())
                     .mapError(ConfigLoader::flattened)
                     .map(ConfigLoader::materialized);
    }

    /// `Result.allOf` answers a composite even for one failure, and its message starts with `Composite:`; the
    /// person running the tool sees one line per bad layer, not the wrapper.
    private static Cause flattened(Cause cause) {
        return cause instanceof Causes.CompositeCause composite
               ? ConfigError.LayersInvalid.layersInvalid(composite.stream().toList())
               : cause;
    }

    private static JbctConfig materialized(List<Option<PartialConfig>> layers) {
        return layers.stream()
                     .flatMap(Option::stream)
                     .reduce(PartialConfig.EMPTY, PartialConfig::mergeWith)
                     .materialize();
    }

    private static List<Path> discoveredLayers(Path startDir) {
        return Stream.concat(userConfigCandidate().stream(),
                             findProjectConfigChain(startDir).stream())
                     .toList();
    }

    private static Path startDirectory(Option<Path> workingDirectory) {
        return workingDirectory.or(() -> Path.of(System.getProperty("user.dir")));
    }

    private static Option<Path> userConfigCandidate() {
        return Option.option(System.getProperty("user.home")).map(home -> Path.of(home,
                                                                                  USER_CONFIG_DIR,
                                                                                  USER_CONFIG_NAME));
    }

    private static Result<Option<PartialConfig>> parseLayer(Path path) {
        return FileOps.isRegularFile(path)
               ? readLayer(path).map(Option::some)
               : success(none());
    }

    private static Result<Option<PartialConfig>> parseExplicitLayer(Path path) {
        return FileOps.isRegularFile(path)
               ? readLayer(path).map(Option::some)
               : ConfigError.LayerInvalid.layerInvalid(path,
                                                       Causes.cause("the file named by --config does not exist or is not a regular file"))
                                         .result();
    }

    private static Result<PartialConfig> readLayer(Path path) {
        return TomlParser.parseFile(path)
                         .flatMap(PartialConfig::partialConfig)
                         .mapError(cause -> ConfigError.LayerInvalid.layerInvalid(path, cause));
    }

    private static List<Path> collectChain(Option<Path> dirOpt) {
        return dirOpt.map(ConfigLoader::collectChainFrom)
                     .or(List.of());
    }

    private static List<Path> collectChainFrom(Path dir) {
        return isRepositoryRoot(dir)
               ? configIn(dir)
               : concat(collectChain(Option.option(dir.getParent())),
                        configIn(dir));
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
        return Stream.concat(outer.stream(),
                             inner.stream())
                     .toList();
    }
}
