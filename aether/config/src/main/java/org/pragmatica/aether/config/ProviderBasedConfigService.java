package org.pragmatica.aether.config;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.format.DateTimeParseException;
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

    // --- Fix #2: Extract resolveComponentArgs from bindToClass ---

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

            return resolveComponentArgs(section, components)
                .map(args -> invokeConstructor(constructor, args))
                .flatMap(r -> r);
        } catch (ReflectiveOperationException e) {
            return ConfigError.parseFailed(section, e).result();
        }
    }

    private <T> Result<T> invokeConstructor(Constructor<T> constructor, Object[] args) {
        try {
            return Result.success(constructor.newInstance(args));
        } catch (ReflectiveOperationException e) {
            return ConfigError.parseFailed(constructor.getDeclaringClass().getSimpleName(), e).result();
        }
    }

    private Result<Object[]> resolveComponentArgs(String section, RecordComponent[] components) {
        var args = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
            var value = extractValue(section, components[i]);
            if (value.isFailure()) {
                return value.map(_ -> args);
            }
            args[i] = value.unwrap();
        }

        return Result.success(args);
    }

    // --- Fix #1: Decompose extractValue into clean Condition dispatcher ---

    private Result<Object> extractValue(String section, RecordComponent component) {
        var key = component.getName();
        var type = component.getType();
        var fullKey = section + "." + toSnakeCase(key);

        // Condition dispatcher: delegates to type-specific resolvers
        return resolvePrimitive(fullKey, type)
            .orElse(() -> resolveEnum(fullKey, type))
            .orElse(() -> resolveNestedRecord(section, key, type))
            .orElse(() -> resolveMap(fullKey, type, component.getGenericType()))
            .orElse(() -> resolveOption(section, key, type, component.getGenericType()))
            .or(ConfigError.typeMismatch(fullKey, "supported type", type.getSimpleName()).result());
    }

    /**
     * Returns a parser function for primitive/simple types, or None if the type is not a primitive.
     * Parser takes a String and returns Option of the parsed value (type-erased to Object).
     */
    @SuppressWarnings("unchecked")
    static Option<Fn1<Option<Object>, String>> primitiveParser(Class<?> type) {
        if (type == String.class) {
            return Option.some(v -> Option.some(v));
        }
        if (type == int.class || type == Integer.class) {
            return Option.some(v -> safeParseInteger(v).map(Object.class::cast));
        }
        if (type == long.class || type == Long.class) {
            return Option.some(v -> safeParseLong(v).map(Object.class::cast));
        }
        if (type == boolean.class || type == Boolean.class) {
            return Option.some(v -> Option.some(Boolean.parseBoolean(v)));
        }
        if (type == double.class || type == Double.class) {
            return Option.some(v -> safeParseDouble(v).map(Object.class::cast));
        }
        if (type == Duration.class) {
            return Option.some(v -> parseDuration(v).map(Object.class::cast));
        }
        return Option.none();
    }

    /**
     * Resolves a primitive type value from config. Returns Some(Result) if type recognized, None otherwise.
     */
    private Option<Result<Object>> resolvePrimitive(String fullKey, Class<?> type) {
        return primitiveParser(type)
            .map(parser -> provider.getString(fullKey)
                                   .flatMap(parser)
                                   .toResult(ConfigError.sectionNotFound(fullKey)));
    }

    /**
     * Resolves an enum type value from config. Returns Some(Result) if type is enum, None otherwise.
     */
    private Option<Result<Object>> resolveEnum(String fullKey, Class<?> type) {
        if (!type.isEnum()) {
            return Option.none();
        }
        return Option.some(
            provider.getString(fullKey)
                    .toResult(ConfigError.sectionNotFound(fullKey))
                    .flatMap(value -> parseEnum(value, type, fullKey))
        );
    }

    /**
     * Resolves a nested record type value from config. Returns Some(Result) if type is record, None otherwise.
     */
    @SuppressWarnings("unchecked")
    private Option<Result<Object>> resolveNestedRecord(String section, String key, Class<?> type) {
        if (!type.isRecord()) {
            return Option.none();
        }

        var nestedSection = section + "." + toSnakeCase(key);

        if (!hasSection(nestedSection)) {
            return Option.some(
                findDefaultField(type)
                    .map(Result::success)
                    .or(ConfigError.sectionNotFound(nestedSection).result())
            );
        }
        return Option.some((Result<Object>) bindToClass(nestedSection, type));
    }

    private Option<Result<Object>> resolveMap(String fullKey, Class<?> type, Type genericType) {
        if (type != Map.class) {
            return Option.none();
        }
        return Option.some(extractMapValue(fullKey, genericType));
    }

    private Option<Result<Object>> resolveOption(String section, String key, Class<?> type, Type genericType) {
        if (type != Option.class) {
            return Option.none();
        }
        return Option.some(extractOptionValue(section, toSnakeCase(key), key, genericType));
    }

    // --- Primitive parsers (static, no instance dependency) ---

    private static Option<Integer> safeParseInteger(String value) {
        try {
            return Option.some(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return Option.none();
        }
    }

    private static Option<Long> safeParseLong(String value) {
        try {
            return Option.some(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return Option.none();
        }
    }

    private static Option<Double> safeParseDouble(String value) {
        try {
            return Option.some(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return Option.none();
        }
    }

    // --- Fix #5: Remove null check from parseDuration ---

    static Option<Duration> parseDuration(String value) {
        if (value.isBlank()) {
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
        // Fix #7: Narrow catch to DateTimeParseException
        try {
            return Option.some(Duration.parse(value.trim()));
        } catch (DateTimeParseException e) {
            return Option.none();
        }
    }

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

    // --- Fix #4: Flatten extractOptionValue nesting ---

    @SuppressWarnings("unchecked")
    private Result<Object> extractOptionValue(String section, String tomlKey, String key, Type genericType) {
        var fullKey = section + "." + tomlKey;

        if (!(genericType instanceof ParameterizedType paramType)) {
            return Result.success(provider.getString(fullKey));
        }

        var typeArgs = paramType.getActualTypeArguments();

        if (typeArgs.length != 1 || !(typeArgs[0] instanceof Class<?> innerClass)) {
            return Result.success(provider.getString(fullKey));
        }

        return extractOptionalPrimitive(fullKey, innerClass);
    }

    // --- Fix #3: Reuse primitiveParser in extractOptionalPrimitive ---

    private Result<Object> extractOptionalPrimitive(String fullKey, Class<?> innerClass) {
        return primitiveParser(innerClass)
            .map(parser -> Result.<Object>success(provider.getString(fullKey).flatMap(parser)))
            .or(() -> resolveOptionalEnum(fullKey, innerClass));
    }

    private Result<Object> resolveOptionalEnum(String fullKey, Class<?> innerClass) {
        if (!innerClass.isEnum()) {
            return Result.success(Option.none());
        }

        var stringOpt = provider.getString(fullKey);
        if (stringOpt.isEmpty()) {
            return Result.success(Option.none());
        }

        return parseEnum(stringOpt.unwrap(), innerClass, fullKey)
            .map(Option::option);
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
