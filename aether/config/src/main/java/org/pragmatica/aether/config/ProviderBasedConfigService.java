package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;

/**
 * ConfigService implementation that delegates to a ConfigurationProvider.
 * <p>
 * Bridges the ConfigurationProvider (layered key-value configuration) to ConfigService
 * (typed section binding). This enables AetherNode to create a ConfigService from
 * a ConfigurationProvider for resource provisioning.
 */
public final class ProviderBasedConfigService implements ConfigService {
    private final ConfigurationProvider provider;

    private ProviderBasedConfigService(ConfigurationProvider provider) {
        this.provider = provider;
    }

    /**
     * Create a ConfigService from a ConfigurationProvider.
     *
     * @param provider The configuration provider to delegate to
     * @return ConfigService implementation
     */
    public static ProviderBasedConfigService providerBasedConfigService(ConfigurationProvider provider) {
        return new ProviderBasedConfigService(provider);
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
        // Check if any keys exist with this section prefix
        var prefix = section + ".";
        return provider.keys()
                       .stream()
                       .anyMatch(key -> key.startsWith(prefix) || key.equals(section));
    }

    @Override
    public Option<String> getString(String key) {
        return provider.getString(key);
    }

    @Override
    public Option<Integer> getInt(String key) {
        return provider.getString(key)
                       .flatMap(this::parseInteger);
    }

    @Override
    public Option<Boolean> getBoolean(String key) {
        return provider.getString(key)
                       .map(Boolean::parseBoolean);
    }

    private Option<Integer> parseInteger(String value) {
        try {
            return Option.some(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return Option.none();
        }
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
        var fullKey = section + "." + toSnakeCase(key);

        if (type == String.class) {
            return provider.getString(fullKey)
                           .toResult(ConfigError.sectionNotFound(fullKey))
                           .map(Object.class::cast);
        }

        if (type == int.class || type == Integer.class) {
            return provider.getString(fullKey)
                           .flatMap(this::parseInteger)
                           .toResult(ConfigError.sectionNotFound(fullKey))
                           .map(Object.class::cast);
        }

        if (type == long.class || type == Long.class) {
            return provider.getString(fullKey)
                           .flatMap(this::parseLong)
                           .toResult(ConfigError.sectionNotFound(fullKey))
                           .map(Object.class::cast);
        }

        if (type == boolean.class || type == Boolean.class) {
            return provider.getString(fullKey)
                           .map(Boolean::parseBoolean)
                           .toResult(ConfigError.sectionNotFound(fullKey))
                           .map(Object.class::cast);
        }

        if (type == double.class || type == Double.class) {
            return provider.getString(fullKey)
                           .flatMap(this::parseDouble)
                           .toResult(ConfigError.sectionNotFound(fullKey))
                           .map(Object.class::cast);
        }

        if (type.isEnum()) {
            return provider.getString(fullKey)
                           .toResult(ConfigError.sectionNotFound(fullKey))
                           .flatMap(value -> parseEnum(value, type, fullKey));
        }

        if (type.isRecord()) {
            var nestedSection = section + "." + toSnakeCase(key);
            if (!hasSection(nestedSection)) {
                return ConfigError.sectionNotFound(nestedSection).result();
            }
            return (Result<Object>) bindToClass(nestedSection, type);
        }

        if (type == Option.class) {
            return extractOptionValue(section, toSnakeCase(key), key, component.getGenericType());
        }

        return ConfigError.typeMismatch(fullKey, "supported type", type.getSimpleName()).result();
    }

    private Option<Long> parseLong(String value) {
        try {
            return Option.some(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return Option.none();
        }
    }

    private Option<Double> parseDouble(String value) {
        try {
            return Option.some(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return Option.none();
        }
    }

    @SuppressWarnings("unchecked")
    private Result<Object> extractOptionValue(String section, String tomlKey, String key, Type genericType) {
        var fullKey = section + "." + tomlKey;
        // Extract the type parameter from Option<T>
        if (genericType instanceof ParameterizedType paramType) {
            var typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length == 1) {
                var innerType = typeArgs[0];
                if (innerType instanceof Class<?> innerClass) {
                    return extractOptionalPrimitive(fullKey, innerClass);
                }
            }
        }
        // If we can't determine the inner type, treat as Option<String>
        return Result.success(provider.getString(fullKey));
    }

    private Result<Object> extractOptionalPrimitive(String fullKey, Class<?> innerClass) {
        if (innerClass == String.class) {
            return Result.success(provider.getString(fullKey));
        }
        if (innerClass == Integer.class) {
            return Result.success(provider.getString(fullKey).flatMap(this::parseInteger));
        }
        if (innerClass == Long.class) {
            return Result.success(provider.getString(fullKey).flatMap(this::parseLong));
        }
        if (innerClass == Boolean.class) {
            return Result.success(provider.getString(fullKey).map(Boolean::parseBoolean));
        }
        if (innerClass == Double.class) {
            return Result.success(provider.getString(fullKey).flatMap(this::parseDouble));
        }
        if (innerClass.isEnum()) {
            var stringOpt = provider.getString(fullKey);
            if (stringOpt.isEmpty()) {
                return Result.success(Option.none());
            }
            return parseEnum(stringOpt.unwrap(), innerClass, fullKey)
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
}
