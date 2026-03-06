package org.pragmatica.config;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Text;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;

/// TOML-based implementation of ConfigService.
///
/// Loads configuration from aether.toml and provides typed section binding.
/// Supports nested sections using dot notation (e.g., "database.primary").
public final class TomlConfigService implements ConfigService {
    private final TomlDocument document;

    private TomlConfigService(TomlDocument document) {
        this.document = document;
    }

    /// Create a ConfigService from a TOML file path.
    ///
    /// @param path Path to the TOML file
    /// @return Result containing the ConfigService or error
    public static Result<ConfigService> tomlConfigService(Path path) {
        return TomlParser.parseFile(path)
                         .mapError(e -> toReadFailed(path.toString(),
                                                     e))
                         .map(TomlConfigService::new);
    }

    /// Create a ConfigService from TOML content string.
    ///
    /// @param content TOML content
    /// @return Result containing the ConfigService or error
    public static Result<ConfigService> tomlConfigService(String content) {
        return TomlParser.parse(content)
                         .mapError(e -> toParseFailed(e))
                         .map(TomlConfigService::new);
    }

    /// Create a ConfigService from default aether.toml location.
    ///
    /// @return Result containing the ConfigService or error
    public static Result<ConfigService> tomlConfigService() {
        return tomlConfigService(Path.of("aether.toml"));
    }

    @Override
    public <T> Result<T> config(String section, Class<T> configClass) {
        if (!hasSection(section)) {
            return ConfigError.sectionNotFound(section)
                              .result();
        }
        return bindToClass(section, configClass);
    }

    @Override
    public boolean hasSection(String section) {
        return document.hasSection(section);
    }

    @Override
    public Option<String> getString(String key) {
        var parts = splitKey(key);
        return document.getString(parts.section(), parts.key());
    }

    @Override
    public Option<Integer> getInt(String key) {
        var parts = splitKey(key);
        return document.getInt(parts.section(), parts.key());
    }

    @Override
    public Option<Boolean> getBoolean(String key) {
        var parts = splitKey(key);
        return document.getBoolean(parts.section(), parts.key());
    }

    @SuppressWarnings("unchecked")
    private <T> Result<T> bindToClass(String section, Class<T> configClass) {
        if (!configClass.isRecord()) {
            return ConfigError.typeMismatch(section,
                                            "record",
                                            configClass.getSimpleName())
                              .result();
        }
        try{
            var components = configClass.getRecordComponents();
            var types = extractComponentTypes(components);
            Constructor<T> constructor = configClass.getDeclaredConstructor(types);
            return collectComponentArgs(section, components).flatMap(args -> invokeConstructor(constructor, args));
        } catch (Exception e) {
            return ConfigError.parseFailed(section, e)
                              .result();
        }
    }

    private <T> Result<T> invokeConstructor(Constructor<T> constructor, Object[] args) {
        try{
            return success(constructor.newInstance(args));
        } catch (Exception e) {
            return ConfigError.parseFailed(constructor.getDeclaringClass()
                                                      .getSimpleName(),
                                           e)
                              .result();
        }
    }

    private Result<Object[]> collectComponentArgs(String section, RecordComponent[] components) {
        return IntStream.range(0, components.length)
                        .mapToObj(i -> collectComponentAt(section, components[i], i))
                        .reduce(success(new Object[components.length]),
                                TomlConfigService::accumulateArg,
                                TomlConfigService::mergeArgs);
    }

    private Result<IndexedValue> collectComponentAt(String section, RecordComponent component, int index) {
        return extractValue(section, component).flatMap(v -> IndexedValue.indexedValue(index, v));
    }

    private static Result<Object[]> accumulateArg(Result<Object[]> acc, Result<IndexedValue> next) {
        return acc.flatMap(args -> next.map(iv -> setArrayElement(args, iv)));
    }

    private static Object[] setArrayElement(Object[] args, IndexedValue iv) {
        args[iv.index()] = iv.value();
        return args;
    }

    private static Result<Object[]> mergeArgs(Result<Object[]> a, Result<Object[]> b) {
        return a.flatMap(_ -> b);
    }

    private record IndexedValue(int index, Object value) {
        static Result<IndexedValue> indexedValue(int index, Object value) {
            return success(new IndexedValue(index, value));
        }
    }

    private static Class<?>[] extractComponentTypes(RecordComponent[] components) {
        var types = Arrays.stream(components)
                          .map(TomlConfigService::componentType);
        return types.toArray(Class[]::new);
    }

    private static Class<?> componentType(RecordComponent component) {
        return component.getType();
    }

    @SuppressWarnings("unchecked")
    private Result<Object> extractValue(String section, RecordComponent component) {
        var key = component.getName();
        var type = component.getType();
        var tomlKey = toSnakeCase(key);
        if (type == String.class) {
            return lookupString(section, key, tomlKey);
        }
        if (type == int.class || type == Integer.class) {
            return lookupInt(section, key, tomlKey);
        }
        if (type == long.class || type == Long.class) {
            return lookupLong(section, key, tomlKey);
        }
        if (type == boolean.class || type == Boolean.class) {
            return lookupBoolean(section, key, tomlKey);
        }
        if (type == double.class || type == Double.class) {
            return lookupDouble(section, key, tomlKey);
        }
        if (type.isEnum()) {
            return lookupEnum(section, key, tomlKey, type);
        }
        if (type.isRecord()) {
            return lookupNestedRecord(section, tomlKey, type);
        }
        if (type == Option.class) {
            return extractOptionValue(section, tomlKey, key, component.getGenericType());
        }
        return ConfigError.typeMismatch(section + "." + key,
                                        "supported type",
                                        type.getSimpleName())
                          .result();
    }

    private Result<Object> lookupString(String section, String key, String tomlKey) {
        return document.getString(section, tomlKey)
                       .toResult(ConfigError.sectionNotFound(section + "." + key))
                       .map(Object.class::cast);
    }

    private Result<Object> lookupInt(String section, String key, String tomlKey) {
        return document.getInt(section, tomlKey)
                       .toResult(ConfigError.sectionNotFound(section + "." + key))
                       .map(Object.class::cast);
    }

    private Result<Object> lookupLong(String section, String key, String tomlKey) {
        return document.getLong(section, tomlKey)
                       .toResult(ConfigError.sectionNotFound(section + "." + key))
                       .map(Object.class::cast);
    }

    private Result<Object> lookupBoolean(String section, String key, String tomlKey) {
        return document.getBoolean(section, tomlKey)
                       .toResult(ConfigError.sectionNotFound(section + "." + key))
                       .map(Object.class::cast);
    }

    private Result<Object> lookupDouble(String section, String key, String tomlKey) {
        return document.getDouble(section, tomlKey)
                       .toResult(ConfigError.sectionNotFound(section + "." + key))
                       .map(Object.class::cast);
    }

    private Result<Object> lookupEnum(String section, String key, String tomlKey, Class<?> type) {
        return document.getString(section, tomlKey)
                       .toResult(ConfigError.sectionNotFound(section + "." + key))
                       .flatMap(value -> safeParseEnum(value, type, section + "." + key));
    }

    @SuppressWarnings("unchecked")
    private Result<Object> lookupNestedRecord(String section, String tomlKey, Class<?> type) {
        var nestedSection = section + "." + tomlKey;
        if (!document.hasSection(nestedSection)) {
            return ConfigError.sectionNotFound(nestedSection)
                              .result();
        }
        return (Result<Object>) bindToClass(nestedSection, type);
    }

    @SuppressWarnings("unchecked")
    private Result<Object> extractOptionValue(String section, String tomlKey, String key, Type genericType) {
        if (genericType instanceof ParameterizedType paramType) {
            var typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length == 1 && typeArgs[0] instanceof Class<?> innerClass) {
                return extractOptionalPrimitive(section, tomlKey, key, innerClass);
            }
        }
        return success(document.getString(section, tomlKey));
    }

    private Result<Object> extractOptionalPrimitive(String section, String tomlKey, String key, Class<?> innerClass) {
        if (innerClass == String.class) {
            return success(document.getString(section, tomlKey));
        }
        if (innerClass == Integer.class) {
            return success(document.getInt(section, tomlKey));
        }
        if (innerClass == Long.class) {
            return success(document.getLong(section, tomlKey));
        }
        if (innerClass == Boolean.class) {
            return success(document.getBoolean(section, tomlKey));
        }
        if (innerClass == Double.class) {
            return success(document.getDouble(section, tomlKey));
        }
        if (innerClass.isEnum()) {
            return handleOptionalEnum(section, tomlKey, key, innerClass);
        }
        return success(none());
    }

    private Result<Object> handleOptionalEnum(String section, String tomlKey, String key, Class<?> innerClass) {
        var stringOpt = document.getString(section, tomlKey);
        if (Verify.Is.none(stringOpt)) {
            return success(none());
        }
        return safeParseEnum(stringOpt.unwrap(), innerClass, section + "." + key).map(Option::option);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<Object> safeParseEnum(String value, Class<?> type, String fullKey) {
        var upperValue = value.toUpperCase();
        return Text.parseEnum((Class<Enum>) type,
                              upperValue)
                   .map(Object.class::cast)
                   .mapError(_ -> toTypeMismatch(fullKey, type, value));
    }

    private static ConfigError toTypeMismatch(String fullKey, Class<?> type, String value) {
        return ConfigError.typeMismatch(fullKey, type.getSimpleName(), value);
    }

    private static ConfigError toReadFailed(String path, Cause cause) {
        return ConfigError.readFailed(path, new RuntimeException(cause.message()));
    }

    private static ConfigError toParseFailed(Cause cause) {
        return ConfigError.parseFailed("root", cause.message());
    }

    private static String toSnakeCase(String camelCase) {
        var result = new StringBuilder();
        IntStream.range(0,
                        camelCase.length())
                 .forEach(i -> appendSnakeCaseChar(result,
                                                   camelCase.charAt(i),
                                                   i));
        return result.toString();
    }

    private static void appendSnakeCaseChar(StringBuilder result, char c, int index) {
        if (isUpperCaseWithPrefix(c, index)) {
            result.append('_');
        }
        result.append(Character.isUpperCase(c)
                      ? Character.toLowerCase(c)
                      : c);
    }

    private static boolean isUpperCaseWithPrefix(char c, int index) {
        return Character.isUpperCase(c) && Verify.Is.positive(index);
    }

    private record KeyParts(String section, String key) {
        static Result<KeyParts> keyParts(String section, String key) {
            return success(new KeyParts(section, key));
        }
    }

    private static KeyParts splitKey(String fullKey) {
        int lastDot = fullKey.lastIndexOf('.');
        if (Verify.Is.negative(lastDot)) {
            return KeyParts.keyParts("", fullKey)
                           .unwrap();
        }
        return KeyParts.keyParts(fullKey.substring(0, lastDot),
                                 fullKey.substring(lastDot + 1))
                       .unwrap();
    }
}
