package org.pragmatica.aether.config;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * TOML-based implementation of ConfigService.
 * <p>
 * Loads configuration from aether.toml and provides typed section binding.
 * Supports nested sections using dot notation (e.g., "database.primary").
 */
public final class TomlConfigService implements ConfigService {
    private final TomlDocument document;

    private TomlConfigService(TomlDocument document) {
        this.document = document;
    }

    /**
     * Create a ConfigService from a TOML file path.
     *
     * @param path Path to the TOML file
     * @return Result containing the ConfigService or error
     */
    public static Result<ConfigService> tomlConfigService(Path path) {
        return TomlParser.parseFile(path)
                         .mapError(e -> ConfigError.readFailed(path.toString(), new RuntimeException(e.message())))
                         .map(TomlConfigService::new);
    }

    /**
     * Create a ConfigService from TOML content string.
     *
     * @param content TOML content
     * @return Result containing the ConfigService or error
     */
    public static Result<ConfigService> tomlConfigService(String content) {
        return TomlParser.parse(content)
                         .mapError(e -> ConfigError.parseFailed("root", e.message()))
                         .map(TomlConfigService::new);
    }

    /**
     * Create a ConfigService from default aether.toml location.
     *
     * @return Result containing the ConfigService or error
     */
    public static Result<ConfigService> tomlConfigService() {
        return tomlConfigService(Path.of("aether.toml"));
    }

    @Override
    public <T> Result<T> config(String section, Class<T> configClass) {
        if (!hasSection(section)) {
            return ConfigError.sectionNotFound(section).result();
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
            return ConfigError.typeMismatch(section, "record", configClass.getSimpleName()).result();
        }

        try {
            var components = configClass.getRecordComponents();
            var types = Arrays.stream(components)
                              .map(RecordComponent::getType)
                              .toArray(Class[]::new);

            Constructor<T> constructor = configClass.getDeclaredConstructor(types);

            var args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var component = components[i];
                var value = extractValue(section, component);
                if (value.isFailure()) {
                    return (Result<T>) value;
                }
                args[i] = value.unwrap();
            }

            return Result.success(constructor.newInstance(args));
        } catch (Exception e) {
            return ConfigError.parseFailed(section, e).result();
        }
    }

    @SuppressWarnings("unchecked")
    private Result<Object> extractValue(String section, RecordComponent component) {
        var key = component.getName();
        var type = component.getType();
        var tomlKey = toSnakeCase(key);

        if (type == String.class) {
            return document.getString(section, tomlKey)
                           .toResult(ConfigError.sectionNotFound(section + "." + key))
                           .map(Object.class::cast);
        }

        if (type == int.class || type == Integer.class) {
            return document.getInt(section, tomlKey)
                           .toResult(ConfigError.sectionNotFound(section + "." + key))
                           .map(Object.class::cast);
        }

        if (type == long.class || type == Long.class) {
            return document.getLong(section, tomlKey)
                           .toResult(ConfigError.sectionNotFound(section + "." + key))
                           .map(Object.class::cast);
        }

        if (type == boolean.class || type == Boolean.class) {
            return document.getBoolean(section, tomlKey)
                           .toResult(ConfigError.sectionNotFound(section + "." + key))
                           .map(Object.class::cast);
        }

        if (type == double.class || type == Double.class) {
            return document.getDouble(section, tomlKey)
                           .toResult(ConfigError.sectionNotFound(section + "." + key))
                           .map(Object.class::cast);
        }

        if (type.isEnum()) {
            return document.getString(section, tomlKey)
                           .toResult(ConfigError.sectionNotFound(section + "." + key))
                           .flatMap(value -> parseEnum(value, type, section + "." + key));
        }

        if (type.isRecord()) {
            var nestedSection = section + "." + tomlKey;
            if (!document.hasSection(nestedSection)) {
                return ConfigError.sectionNotFound(nestedSection).result();
            }
            return (Result<Object>) bindToClass(nestedSection, type);
        }

        if (type == Option.class) {
            return extractOptionValue(section, tomlKey, key, component.getGenericType());
        }

        return ConfigError.typeMismatch(section + "." + key, "supported type", type.getSimpleName()).result();
    }

    @SuppressWarnings("unchecked")
    private Result<Object> extractOptionValue(String section, String tomlKey, String key, Type genericType) {
        // Extract the type parameter from Option<T>
        if (genericType instanceof ParameterizedType paramType) {
            var typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length == 1) {
                var innerType = typeArgs[0];
                if (innerType instanceof Class<?> innerClass) {
                    return extractOptionalPrimitive(section, tomlKey, key, innerClass);
                }
            }
        }
        // If we can't determine the inner type, treat as Option<String>
        return Result.success(document.getString(section, tomlKey));
    }

    private Result<Object> extractOptionalPrimitive(String section, String tomlKey, String key, Class<?> innerClass) {
        if (innerClass == String.class) {
            return Result.success(document.getString(section, tomlKey));
        }
        if (innerClass == Integer.class) {
            return Result.success(document.getInt(section, tomlKey));
        }
        if (innerClass == Long.class) {
            return Result.success(document.getLong(section, tomlKey));
        }
        if (innerClass == Boolean.class) {
            return Result.success(document.getBoolean(section, tomlKey));
        }
        if (innerClass == Double.class) {
            return Result.success(document.getDouble(section, tomlKey));
        }
        if (innerClass.isEnum()) {
            var stringOpt = document.getString(section, tomlKey);
            if (stringOpt.isEmpty()) {
                return Result.success(Option.none());
            }
            return parseEnum(stringOpt.unwrap(), innerClass, section + "." + key)
                .map(Option::option);
        }
        // For unsupported types, return none
        return Result.success(Option.none());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Result<Object> parseEnum(String value, Class<?> type, String fullKey) {
        try {
            return Result.success(Enum.valueOf((Class<Enum>) type, value.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return ConfigError.typeMismatch(fullKey, type.getSimpleName(), value).result();
        }
    }

    private static String toSnakeCase(String camelCase) {
        var result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private record KeyParts(String section, String key) {}

    private static KeyParts splitKey(String fullKey) {
        int lastDot = fullKey.lastIndexOf('.');
        if (lastDot < 0) {
            return new KeyParts("", fullKey);
        }
        return new KeyParts(fullKey.substring(0, lastDot), fullKey.substring(lastDot + 1));
    }
}
