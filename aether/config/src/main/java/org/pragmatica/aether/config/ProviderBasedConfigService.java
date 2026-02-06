package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ConfigService implementation that delegates to a ConfigurationProvider.
 * <p>
 * Bridges the ConfigurationProvider (layered key-value configuration) to ConfigService
 * (typed section binding). This enables AetherNode to create a ConfigService from
 * a ConfigurationProvider for resource provisioning.
 */
public final class ProviderBasedConfigService implements ConfigService {
    private static final Pattern DURATION_PATTERN = Pattern.compile(
        "^(\\d+)\\s*(ms|s|m|h|d)$"
    );

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

        if (type == Duration.class) {
            return provider.getString(fullKey)
                           .flatMap(ProviderBasedConfigService::parseDuration)
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
                return findDefaultField(type)
                    .map(Result::success)
                    .or(ConfigError.sectionNotFound(nestedSection).result());
            }
            return (Result<Object>) bindToClass(nestedSection, type);
        }

        if (type == Map.class) {
            return extractMapValue(fullKey, component.getGenericType());
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

    static Option<Duration> parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return Option.none();
        }

        // Try human-friendly format: "30s", "10m", "1h", "500ms", "1d"
        var matcher = DURATION_PATTERN.matcher(value.trim());
        if (matcher.matches()) {
            var amount = Long.parseLong(matcher.group(1));
            var unit = matcher.group(2);
            return Option.some(switch (unit) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> Duration.ZERO;
            });
        }

        // Try ISO-8601 format: "PT30S", "PT10M", etc.
        try {
            return Option.some(Duration.parse(value.trim()));
        } catch (Exception e) {
            return Option.none();
        }
    }

    @SuppressWarnings("unchecked")
    private Result<Object> extractMapValue(String fullKey, Type genericType) {
        // Collect all sub-keys under the prefix into a Map<String, String>
        var prefix = fullKey + ".";
        Map<String, String> map = new LinkedHashMap<>();

        for (var mapKey : provider.keys()) {
            if (mapKey.startsWith(prefix)) {
                var subKey = mapKey.substring(prefix.length());
                provider.getString(mapKey)
                        .onPresent(v -> map.put(subKey, v));
            }
        }

        return Result.success(map);
    }

    private static Option<Object> findDefaultField(Class<?> type) {
        try {
            Field defaultField = type.getField("DEFAULT");
            if (java.lang.reflect.Modifier.isStatic(defaultField.getModifiers())
                && java.lang.reflect.Modifier.isFinal(defaultField.getModifiers())
                && type.isAssignableFrom(defaultField.getType())) {
                return Option.option(defaultField.get(null));
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // No DEFAULT field — fall through
        }
        return Option.none();
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
        if (innerClass == Duration.class) {
            return Result.success(provider.getString(fullKey).flatMap(ProviderBasedConfigService::parseDuration));
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

    static String toSnakeCase(String camelCase) {
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
