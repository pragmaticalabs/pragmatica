package org.pragmatica.config.source;

import org.pragmatica.config.ConfigError;
import org.pragmatica.config.ConfigSource;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Configuration source backed by a TOML file.
///
/// TOML sections are flattened to dot notation:
/// ```{@code
/// [database]
/// host = "localhost"
/// port = 5432
/// }```
/// Becomes:
///
///   - database.host -> localhost
///   - database.port -> 5432
///
public final class TomlConfigSource implements ConfigSource {
    private static final int DEFAULT_PRIORITY = 0;

    private final Option<Path> path;
    private final int priority;
    private final TomlDocument document;
    private final Map<String, String> flattenedValues;

    private TomlConfigSource(Option<Path> path, int priority, TomlDocument document) {
        this.path = path;
        this.priority = priority;
        this.document = document;
        this.flattenedValues = flattenDocument(document);
    }

    /// Create a TomlConfigSource from a file path.
    ///
    /// @param path Path to the TOML file
    /// @return Result containing the source or error
    public static Result<TomlConfigSource> tomlConfigSource(Path path) {
        return tomlConfigSource(path, DEFAULT_PRIORITY);
    }

    /// Create a TomlConfigSource from a file path with specified priority.
    ///
    /// @param path     Path to the TOML file
    /// @param priority Source priority
    /// @return Result containing the source or error
    public static Result<TomlConfigSource> tomlConfigSource(Path path, int priority) {
        return TomlParser.parseFile(path)
                         .mapError(e -> toReadFailed(path, e))
                         .map(doc -> createWithPath(path, priority, doc));
    }

    /// Create a TomlConfigSource from TOML content string.
    ///
    /// @param content TOML content
    /// @return Result containing the source or error
    public static Result<TomlConfigSource> tomlConfigSource(String content) {
        return TomlParser.parse(content)
                         .mapError(e -> toParseFailed(e))
                         .map(doc -> createInline(doc));
    }

    private static TomlConfigSource createWithPath(Path path, int priority, TomlDocument doc) {
        return new TomlConfigSource(some(path), priority, doc);
    }

    private static TomlConfigSource createInline(TomlDocument doc) {
        return new TomlConfigSource(none(), DEFAULT_PRIORITY, doc);
    }

    private static ConfigError toReadFailed(Path path, Cause cause) {
        return ConfigError.readFailed(path.toString(), new RuntimeException(cause.message()));
    }

    private static ConfigError toParseFailed(Cause cause) {
        return ConfigError.parseFailed("inline", cause.message());
    }

    @Override
    public Option<String> getString(String key) {
        return option(flattenedValues.get(key));
    }

    @Override
    public Set<String> keys() {
        return flattenedValues.keySet();
    }

    @Override
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(flattenedValues);
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return path.map(TomlConfigSource::pathSourceName)
                   .or("TomlConfigSource[inline]");
    }

    private static String pathSourceName(Path p) {
        return "TomlConfigSource[" + p + "]";
    }

    @Override
    public Result<ConfigSource> reload() {
        return path.map(p -> reloadFromPath(p))
                   .or(success(this));
    }

    private Result<ConfigSource> reloadFromPath(Path p) {
        return tomlConfigSource(p, priority).map(ConfigSource.class::cast);
    }

    /// Get the underlying TOML document.
    ///
    /// @return The parsed TOML document
    public TomlDocument document() {
        return document;
    }

    private static Map<String, String> flattenDocument(TomlDocument document) {
        var result = new LinkedHashMap<String, String>();
        document.sectionNames()
                .forEach(section -> flattenSection(document, section, result));
        return result;
    }

    private static void flattenSection(TomlDocument document, String section, Map<String, String> result) {
        var prefix = sectionPrefix(section);
        var sectionValues = document.getSection(section);
        sectionValues.forEach((key, value) -> result.put(prefix + key, value));
    }

    private static String sectionPrefix(String section) {
        if (Verify.Is.empty(section)) {
            return "";
        }
        return section + ".";
    }
}
