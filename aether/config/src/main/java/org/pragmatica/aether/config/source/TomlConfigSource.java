package org.pragmatica.aether.config.source;

import org.pragmatica.aether.config.ConfigError;
import org.pragmatica.aether.config.ConfigSource;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    private final Path path;
    private final int priority;
    private final TomlDocument document;
    private final Map<String, String> flattenedValues;

    private TomlConfigSource(Path path, int priority, TomlDocument document) {
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
                         .mapError(e -> ConfigError.readFailed(path.toString(), new RuntimeException(e.message())))
                         .map(doc -> new TomlConfigSource(path, priority, doc));
    }

    /// Create a TomlConfigSource from TOML content string.
    ///
    /// @param content TOML content
    /// @return Result containing the source or error
    public static Result<TomlConfigSource> tomlConfigSource(String content) {
        return TomlParser.parse(content)
                         .mapError(e -> ConfigError.parseFailed("inline", e.message()))
                         .map(doc -> new TomlConfigSource(null, DEFAULT_PRIORITY, doc));
    }

    @Override
    public Option<String> getString(String key) {
        return Option.option(flattenedValues.get(key));
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
        return path != null
            ? "TomlConfigSource[" + path + "]"
            : "TomlConfigSource[inline]";
    }

    @Override
    public Result<ConfigSource> reload() {
        if (path == null) {
            return Result.success(this);
        }
        return tomlConfigSource(path, priority).map(ConfigSource.class::cast);
    }

    /// Get the underlying TOML document.
    ///
    /// @return The parsed TOML document
    public TomlDocument document() {
        return document;
    }

    private static Map<String, String> flattenDocument(TomlDocument document) {
        var result = new LinkedHashMap<String, String>();

        for (var section : document.sectionNames()) {
            var prefix = section.isEmpty() ? "" : section + ".";
            var sectionValues = document.getSection(section);
            for (var entry : sectionValues.entrySet()) {
                result.put(prefix + entry.getKey(), entry.getValue());
            }
        }

        return result;
    }
}
