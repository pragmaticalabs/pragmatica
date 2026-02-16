package org.pragmatica.aether.config;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.parse.Text;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// ConfigService implementation that delegates to a ConfigurationProvider.
///
/// Bridges the ConfigurationProvider (layered key-value configuration) to ConfigService
/// (typed section binding). This enables AetherNode to create a ConfigService from
/// a ConfigurationProvider for resource provisioning.
public final class ProviderBasedConfigService implements ConfigService {
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)\\s*(ms|s|m|h|d)$");

    private final ConfigurationProvider provider;

    private ProviderBasedConfigService(ConfigurationProvider provider) {
        this.provider = provider;
    }

    /// Create a ConfigService from a ConfigurationProvider.
    ///
    /// @param provider The configuration provider to delegate to
    /// @return ConfigService implementation
    public static ProviderBasedConfigService providerBasedConfigService(ConfigurationProvider provider) {
        return new ProviderBasedConfigService(provider);
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
        var prefix = section + ".";
        return provider.keys()
                       .stream()
                       .anyMatch(key -> hasSectionPrefix(key, prefix, section));
    }

    @Override
    public Option<String> getString(String key) {
        return provider.getString(key);
    }

    @Override
    public Option<Integer> getInt(String key) {
        return provider.getString(key)
                       .flatMap(ProviderBasedConfigService::safeParseInteger);
    }

    @Override
    public Option<Boolean> getBoolean(String key) {
        var raw = provider.getString(key);
        return raw.map(ProviderBasedConfigService::toBooleanValue);
    }

    private static Boolean toBooleanValue(String value) {
        return Boolean.parseBoolean(value);
    }

    private static boolean hasSectionPrefix(String key, String prefix, String section) {
        return key.startsWith(prefix) || key.equals(section);
    }

    private static Option<Integer> safeParseInteger(String value) {
        return Number.parseInt(value)
                     .option();
    }

    // --- Record binding ---
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
        } catch (ReflectiveOperationException e) {
            return ConfigError.parseFailed(section, e)
                              .result();
        }
    }

    private static Class<?>[] extractComponentTypes(RecordComponent[] components) {
        var types = Arrays.stream(components)
                          .map(ProviderBasedConfigService::componentType);
        return types.toArray(Class[]::new);
    }

    private static Class<?> componentType(RecordComponent component) {
        return component.getType();
    }

    private <T> Result<T> invokeConstructor(Constructor<T> constructor, Object[] args) {
        try{
            return success(constructor.newInstance(args));
        } catch (ReflectiveOperationException e) {
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
                                ProviderBasedConfigService::accumulateArg,
                                ProviderBasedConfigService::mergeArgs);
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

    // --- Value extraction dispatcher ---
    private Result<Object> extractValue(String section, RecordComponent component) {
        var key = component.getName();
        var type = component.getType();
        var fullKey = section + "." + toSnakeCase(key);
        return lookupByType(section, key, type, fullKey, component.getGenericType());
    }

    private Result<Object> lookupByType(String section, String key, Class<?> type, String fullKey, Type genericType) {
        var simpleResult = lookupPrimitive(fullKey, type).orElse(() -> lookupEnum(fullKey, type))
                                          .orElse(() -> lookupNestedRecord(section, key, type));
        var extendedResult = simpleResult.orElse(() -> lookupMap(fullKey, type))
                                         .orElse(() -> lookupOption(section, key, type, genericType));
        return extendedResult.or(typeMismatchError(fullKey, type));
    }

    private static Result<Object> typeMismatchError(String fullKey, Class<?> type) {
        return ConfigError.typeMismatch(fullKey,
                                        "supported type",
                                        type.getSimpleName())
                          .result();
    }

    // --- Primitive parser lookup ---
    @SuppressWarnings("unchecked")
    static Option<Fn1<Option<Object>, String>> primitiveParser(Class<?> type) {
        if (type == String.class) {
            return some(Option::some);
        }
        if (type == int.class || type == Integer.class) {
            return some(ProviderBasedConfigService::parseIntAsObject);
        }
        if (type == long.class || type == Long.class) {
            return some(ProviderBasedConfigService::parseLongAsObject);
        }
        if (type == boolean.class || type == Boolean.class) {
            return some(ProviderBasedConfigService::parseBooleanAsObject);
        }
        if (type == double.class || type == Double.class) {
            return some(ProviderBasedConfigService::parseDoubleAsObject);
        }
        if (type == Duration.class) {
            return some(ProviderBasedConfigService::parseDurationAsObject);
        }
        return none();
    }

    private static Option<Object> parseIntAsObject(String v) {
        return safeParseInt(v).map(Object.class::cast);
    }

    private static Option<Object> parseLongAsObject(String v) {
        return safeParseLong(v).map(Object.class::cast);
    }

    private static Option<Object> parseBooleanAsObject(String v) {
        return some(Boolean.parseBoolean(v));
    }

    private static Option<Object> parseDoubleAsObject(String v) {
        return safeParseDouble(v).map(Object.class::cast);
    }

    private static Option<Object> parseDurationAsObject(String v) {
        return parseDuration(v).map(Object.class::cast);
    }

    // --- Type-specific resolvers ---
    private Option<Result<Object>> lookupPrimitive(String fullKey, Class<?> type) {
        return primitiveParser(type).map(parser -> fetchAndParse(fullKey, parser));
    }

    private Result<Object> fetchAndParse(String fullKey, Fn1<Option<Object>, String> parser) {
        return provider.getString(fullKey)
                       .flatMap(parser)
                       .toResult(ConfigError.sectionNotFound(fullKey));
    }

    private Option<Result<Object>> lookupEnum(String fullKey, Class<?> type) {
        if (!type.isEnum()) {
            return none();
        }
        return some(fetchAndParseEnum(fullKey, type));
    }

    private Result<Object> fetchAndParseEnum(String fullKey, Class<?> type) {
        return provider.getString(fullKey)
                       .toResult(ConfigError.sectionNotFound(fullKey))
                       .flatMap(value -> safeParseEnum(value, type, fullKey));
    }

    @SuppressWarnings("unchecked")
    private Option<Result<Object>> lookupNestedRecord(String section, String key, Class<?> type) {
        if (!type.isRecord()) {
            return none();
        }
        var nestedSection = section + "." + toSnakeCase(key);
        if (!hasSection(nestedSection)) {
            return some(findDefaultOrError(type, nestedSection));
        }
        return some((Result<Object>) bindToClass(nestedSection, type));
    }

    private static Result<Object> findDefaultOrError(Class<?> type, String nestedSection) {
        return lookupDefaultField(type).map(Result::success)
                                 .or(ConfigError.sectionNotFound(nestedSection)
                                                .result());
    }

    private Option<Result<Object>> lookupMap(String fullKey, Class<?> type) {
        if (type != Map.class) {
            return none();
        }
        return some(collectMapValue(fullKey));
    }

    private Option<Result<Object>> lookupOption(String section, String key, Class<?> type, Type genericType) {
        if (type != Option.class) {
            return none();
        }
        return some(extractOptionValue(section, toSnakeCase(key), genericType));
    }

    // --- Primitive parsers ---
    private static Option<Integer> safeParseInt(String value) {
        return Number.parseInt(value)
                     .option();
    }

    private static Option<Long> safeParseLong(String value) {
        return Number.parseLong(value)
                     .option();
    }

    private static Option<Double> safeParseDouble(String value) {
        return Number.parseDouble(value)
                     .option();
    }

    static Option<Duration> parseDuration(String value) {
        if (Verify.Is.blank(value)) {
            return none();
        }
        var trimmed = value.trim();
        return parseHumanDuration(trimmed).orElse(() -> safeParseIsoDuration(trimmed));
    }

    private static Option<Duration> parseHumanDuration(String trimmed) {
        var matcher = DURATION_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return none();
        }
        return Number.parseLong(matcher.group(1))
                     .option()
                     .map(amount -> durationForUnit(amount,
                                                    matcher.group(2)));
    }

    private static Duration durationForUnit(long amount, String unit) {
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> Duration.ZERO;
        };
    }

    private static Option<Duration> safeParseIsoDuration(String trimmed) {
        return DateTime.parseDuration(trimmed)
                       .option();
    }

    // --- Map value collection ---
    private Result<Object> collectMapValue(String fullKey) {
        var prefix = fullKey + ".";
        Map<String, String> map = new LinkedHashMap<>();
        provider.keys()
                .stream()
                .filter(mapKey -> mapKey.startsWith(prefix))
                .forEach(mapKey -> insertMapEntry(mapKey, prefix, map));
        return success(map);
    }

    private void insertMapEntry(String mapKey, String prefix, Map<String, String> map) {
        var subKey = mapKey.substring(prefix.length());
        provider.getString(mapKey)
                .onPresent(v -> map.put(subKey, v));
    }

    // --- DEFAULT field lookup ---
    private static Option<Object> lookupDefaultField(Class<?> type) {
        try{
            Field defaultField = type.getField("DEFAULT");
            if (isStaticFinalFieldOfType(defaultField, type)) {
                return option(defaultField.get(type));
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {}
        return none();
    }

    private static boolean isStaticFinalFieldOfType(Field field, Class<?> type) {
        var modifiers = field.getModifiers();
        var isStaticFinal = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);
        return isStaticFinal && type.isAssignableFrom(field.getType());
    }

    // --- Option value extraction ---
    @SuppressWarnings("unchecked")
    private Result<Object> extractOptionValue(String section, String tomlKey, Type genericType) {
        var fullKey = section + "." + tomlKey;
        if (! (genericType instanceof ParameterizedType paramType)) {
            return success(provider.getString(fullKey));
        }
        var typeArgs = paramType.getActualTypeArguments();
        if (typeArgs.length != 1 || !(typeArgs[0] instanceof Class<?> innerClass)) {
            return success(provider.getString(fullKey));
        }
        return extractOptionalPrimitive(fullKey, innerClass);
    }

    private Result<Object> extractOptionalPrimitive(String fullKey, Class<?> innerClass) {
        return primitiveParser(innerClass).map(parser -> wrapOptionalParse(fullKey, parser))
                              .or(() -> handleOptionalEnum(fullKey, innerClass));
    }

    private Result<Object> wrapOptionalParse(String fullKey, Fn1<Option<Object>, String> parser) {
        return success(provider.getString(fullKey)
                               .flatMap(parser));
    }

    private Result<Object> handleOptionalEnum(String fullKey, Class<?> innerClass) {
        if (!innerClass.isEnum()) {
            return success(none());
        }
        var stringOpt = provider.getString(fullKey);
        if (Verify.Is.none(stringOpt)) {
            return success(none());
        }
        return safeParseEnum(stringOpt.unwrap(), innerClass, fullKey).map(Option::option);
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

    static String toSnakeCase(String camelCase) {
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
}
