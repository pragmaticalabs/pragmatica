package org.pragmatica.config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.parse.Text;
import org.pragmatica.lang.parse.TimeSpan;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;


/// ConfigService implementation that delegates to a ConfigurationProvider.
///
/// Bridges the ConfigurationProvider (layered key-value configuration) to ConfigService
/// (typed section binding). This enables AetherNode to create a ConfigService from
/// a ConfigurationProvider for resource provisioning.
public final class ProviderBasedConfigService implements ConfigService {
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
            return ConfigError.sectionNotFound(section).result();
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
        return Number.parseInt(value).option();
    }

    // --- Record binding ---
    private <T> Result<T> bindToClass(String section, Class<T> configClass) {
        if (!configClass.isRecord()) {
            return ConfigError.typeMismatch(section,
                                            "record",
                                            configClass.getSimpleName())
                              .result();
        }

        try {
            var components = configClass.getRecordComponents();
            var types = extractComponentTypes(components);
            Constructor<T> constructor = configClass.getDeclaredConstructor(types);

            return strictKeyCheck(section, configClass, components).flatMap(_ -> collectComponentArgs(section,
                                                                                                      components,
                                                                                                      configClass))
                                 .flatMap(args -> invokeFactoryOrConstructor(configClass, constructor, types, args));
        } catch (ReflectiveOperationException e) {
            return ConfigError.parseFailed(section, e).result();
        }
    }

    /// Opt-in ([StrictKeys]) rejection of keys the annotated record does not declare. Scoped to
    /// exactly one path segment past the section prefix, so a nested sub-section (e.g. a consumer
    /// group table owned by the dashed-by-convention stream parser) is never inspected here.
    ///
    /// Scoped to [ConfigurationProvider#staticKeys()] rather than the full merged
    /// [ConfigurationProvider#keys()]: an environment variable, system property, or KV-overlay
    /// entry landing at `<section>.<one segment>` must never fail a bind the file alone would have
    /// accepted, since none of those layers wrote the section that declares this record (#738
    /// review finding).
    ///
    /// A quoted TOML key with a literal dot (e.g. `"a.b" = 1`) is indistinguishable, once
    /// flattened, from a nested sub-table (`[section.a]` / `b = 1`) — [TomlConfigSource]'s
    /// flattening step throws that distinction away before it reaches this class or any
    /// [ConfigurationProvider]. Such a key is therefore never flagged as unknown here, by the same
    /// `indexOf('.') < 0` scoping that protects genuine nested sub-sections; see the class Javadoc
    /// on `org.pragmatica.aether.resource.TopicConfig` for the operator-facing statement of this
    /// limit.
    ///
    /// Reports every unknown key in the section in one error, not just the first.
    private Result<Unit> strictKeyCheck(String section, Class<?> configClass, RecordComponent[] components) {
        if (!configClass.isAnnotationPresent(StrictKeys.class)) {
            return unitResult();
        }

        var known = Arrays.stream(components).map(c -> toSnakeCase(c.getName())).collect(Collectors.toSet());
        var prefix = section + ".";
        var unknownKeys = provider.staticKeys()
                                  .stream()
                                  .filter(k -> k.startsWith(prefix))
                                  .map(k -> k.substring(prefix.length()))
                                  .filter(k -> k.indexOf('.') < 0)
                                  .filter(k -> !known.contains(k))
                                  .sorted()
                                  .toList();

        if (unknownKeys.isEmpty()) {
            return unitResult();
        }

        var suggestions = unknownKeys.stream().collect(Collectors.toMap(k -> k, k -> nearestKey(k, known)));

        return ConfigError.unknownKey(section, unknownKeys, suggestions).<Unit> result();
    }

    private static final int MIN_SUGGESTION_DISTANCE = 3;

    /// Nearest known key by Levenshtein distance, omitted (empty string) beyond a bound of
    /// `max(3, unknown.length() / 2)` — otherwise a typo bearing no real resemblance to any
    /// component (e.g. `zzzzzzzzzzqqqq`) still names the argmin of an unbounded search, which
    /// reads as a real suggestion rather than the noise it is (#738 review finding).
    private static String nearestKey(String unknown, Set<String> known) {
        var threshold = Math.max(MIN_SUGGESTION_DISTANCE, unknown.length() / 2);

        return known.stream()
                    .map(k -> Map.entry(k,
                                        levenshtein(unknown, k)))
                    .min(Comparator.comparingInt(Map.Entry::getValue))
                    .filter(e -> e.getValue() <= threshold)
                    .map(Map.Entry::getKey)
                    .orElse("");
    }

    private static int levenshtein(String a, String b) {
        var dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }

        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                var cost = a.charAt(i - 1) == b.charAt(j - 1)
                           ? 0
                           : 1;

                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }

        return dp[a.length()][b.length()];
    }

    private static Class<?>[] extractComponentTypes(RecordComponent[] components) {
        var types = Arrays.stream(components).map(ProviderBasedConfigService::componentType);

        return types.toArray(Class[]::new);
    }

    private static Class<?> componentType(RecordComponent component) {
        return component.getType();
    }

    private <T> Result<T> invokeFactoryOrConstructor(Class<T> configClass,
                                                     Constructor<T> constructor,
                                                     Class<?>[] types,
                                                     Object[] args) {
        return findFactoryMethod(configClass, types).map(method -> invokeFactory(method, args, configClass))
                                .or(() -> invokeConstructor(constructor, args));
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> invokeFactory(Method method, Object[] args, Class<T> configClass) {
        try {
            return (Result<T>) method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            return ConfigError.parseFailed(configClass.getSimpleName(),
                                           e)
                              .result();
        }
    }

    private static Option<Method> findFactoryMethod(Class<?> configClass, Class<?>[] types) {
        var name = factoryMethodName(configClass);

        try {
            var method = configClass.getDeclaredMethod(name, types);

            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == Result.class) {
                return some(method);
            }
        } catch (NoSuchMethodException e) {}

        return none();
    }

    private static String factoryMethodName(Class<?> configClass) {
        var simpleName = configClass.getSimpleName();

        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private <T> Result<T> invokeConstructor(Constructor<T> constructor, Object[] args) {
        try {
            return success(constructor.newInstance(args));
        } catch (ReflectiveOperationException e) {
            return ConfigError.parseFailed(constructor.getDeclaringClass().getSimpleName(),
                                           e)
                              .result();
        }
    }

    private Result<Object[]> collectComponentArgs(String section, RecordComponent[] components, Class<?> configClass) {
        return IntStream.range(0, components.length)
                        .mapToObj(i -> collectComponentAt(section, components[i], i, configClass))
                        .reduce(success(new Object[components.length]),
                                ProviderBasedConfigService::accumulateArg,
                                ProviderBasedConfigService::mergeArgs);
    }

    private Result<IndexedValue> collectComponentAt(String section,
                                                    RecordComponent component,
                                                    int index,
                                                    Class<?> configClass) {
        var extracted = extractValue(section, component);

        if (extracted.isSuccess()) {
            return extracted.flatMap(v -> IndexedValue.indexedValue(index, v));
        }
        // Convention: derive `name` (String) from the section suffix when it's absent from TOML.
        // Supports blueprint patterns like [streams.test-events] where the trailing segment IS the name.
        var derived = deriveNameFromSectionSuffix(section, component);

        if (derived.isPresent()) {
            return IndexedValue.indexedValue(index, derived.unwrap());
        }

        return getDefaultComponentValue(configClass, component, index);
    }

    private static Option<Object> deriveNameFromSectionSuffix(String section, RecordComponent component) {
        if (!"name".equals(component.getName()) || component.getType() != String.class) {
            return none();
        }

        var lastDot = section.lastIndexOf('.');

        if (lastDot < 0 || lastDot == section.length() - 1) {
            return none();
        }

        return some(section.substring(lastDot + 1));
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
                                         .orElse(() -> lookupList(type, genericType, fullKey))
                                         .orElse(() -> lookupBackoffStrategy(fullKey, type))
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

        if (type == org.pragmatica.lang.io.TimeSpan.class) {
            return some(ProviderBasedConfigService::parseIoTimeSpanAsObject);
        }

        if (type == TimeSpan.class) {
            return some(ProviderBasedConfigService::parseTimeSpanAsObject);
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

    private static Option<Object> parseTimeSpanAsObject(String v) {
        return TimeSpan.timeSpan(v)
                       .option()
                       .map(Object.class::cast);
    }

    private static Option<Object> parseDurationAsObject(String v) {
        return TimeSpan.timeSpan(v)
                       .option()
                       .map(ts -> (Object) ts.duration());
    }

    private static Option<Object> parseIoTimeSpanAsObject(String v) {
        return parseIoTimeSpan(v).map(Object.class::cast);
    }

    private static Option<org.pragmatica.lang.io.TimeSpan> parseIoTimeSpan(String v) {
        return TimeSpan.timeSpan(v)
                       .option()
                       .map(ts -> org.pragmatica.lang.io.TimeSpan.fromDuration(ts.duration()));
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
                                 .or(ConfigError.sectionNotFound(nestedSection).result());
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

    private Option<Result<Object>> lookupList(Class<?> type, Type genericType, String fullKey) {
        if (type != List.class || !isStringListType(genericType)) {
            return none();
        }

        return some(collectListValue(fullKey));
    }

    private static boolean isStringListType(Type genericType) {
        return genericType instanceof ParameterizedType paramType
               && paramType.getActualTypeArguments().length == 1
               && paramType.getActualTypeArguments() [0] == String.class;
    }

    private Option<Result<Object>> lookupBackoffStrategy(String fullKey, Class<?> type) {
        if (type != BackoffStrategy.class) {
            return none();
        }

        return some(resolveBackoffStrategy(fullKey));
    }

    // --- Primitive parsers ---
    private static Option<Integer> safeParseInt(String value) {
        return Number.parseInt(value).option();
    }

    private static Option<Long> safeParseLong(String value) {
        return Number.parseLong(value).option();
    }

    private static Option<Double> safeParseDouble(String value) {
        return Number.parseDouble(value).option();
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

        provider.getString(mapKey).onPresent(v -> map.put(subKey, v));
    }

    // --- List<String> value collection ---
    // Values are comma-joined scalars, not native TOML arrays: TomlDocument#getSection flattens
    // every value via toString() before ProviderBasedConfigService ever sees it, so a native array
    // would arrive as Java's accidental "[a, b]" format. This mirrors the repo's established
    // comma-joined-scalar convention (e.g. HetznerEnvironmentIntegrationFactory#ssh_key_ids).
    // Absent key defaults to an empty list, matching collectMapValue's zero-matches behavior.
    private Result<Object> collectListValue(String fullKey) {
        return success(provider.getString(fullKey).map(ProviderBasedConfigService::splitCommaList).or(List.of()));
    }

    private static List<String> splitCommaList(String raw) {
        return Arrays.stream(raw.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .toList();
    }

    // --- BackoffStrategy value resolution ---
    // BackoffStrategy is a closed core value type (fixed/exponential/linear factories on
    // Retry.BackoffStrategy) - not a record, so it can't go through lookupNestedRecord. A
    // discriminated [section.field] sub-section with a `type` key selects the strategy; the
    // exponential branch reuses the same fallback numbers RetryConfig itself already applies
    // when a caller asks for a strategy without specifying details. fixed/linear have no such
    // precedent default, so their per-strategy fields are required and fail loud when absent.
    private Result<Object> resolveBackoffStrategy(String fullKey) {
        return provider.getString(fullKey + ".type")
                       .toResult(ConfigError.sectionNotFound(fullKey))
                       .flatMap(kind -> buildBackoffStrategy(fullKey, kind));
    }

    private Result<Object> buildBackoffStrategy(String fullKey, String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "fixed" -> fixedBackoffStrategy(fullKey);
            case "exponential" -> exponentialBackoffStrategy(fullKey);
            case "linear" -> linearBackoffStrategy(fullKey);
            default -> ConfigError.typeMismatch(fullKey + ".type", "fixed|exponential|linear", kind).result();
        };
    }

    private Result<Object> fixedBackoffStrategy(String fullKey) {
        return requiredIoTimeSpan(fullKey + ".interval").map(interval -> BackoffStrategy.fixed().interval(interval));
    }

    private Result<Object> exponentialBackoffStrategy(String fullKey) {
        var initialDelay = optionalIoTimeSpan(fullKey + ".initial_delay",
                                              org.pragmatica.lang.io.TimeSpan.timeSpan(100).millis());
        var maxDelay = optionalIoTimeSpan(fullKey + ".max_delay",
                                          org.pragmatica.lang.io.TimeSpan.timeSpan(10).seconds());
        var factor = optionalDouble(fullKey + ".factor", 2.0);
        var withJitter = optionalBoolean(fullKey + ".with_jitter", false);

        return success(BackoffStrategy.exponential()
                                      .initialDelay(initialDelay)
                                      .maxDelay(maxDelay)
                                      .factor(factor)
                                      .jitter(withJitter));
    }

    private Result<Object> linearBackoffStrategy(String fullKey) {
        return Result.all(requiredIoTimeSpan(fullKey + ".initial_delay"),
                          requiredIoTimeSpan(fullKey + ".increment"),
                          requiredIoTimeSpan(fullKey + ".max_delay")).map((initialDelay, increment, maxDelay) -> BackoffStrategy.linear()
                                                                                                                                .initialDelay(initialDelay)
                                                                                                                                .increment(increment)
                                                                                                                                .maxDelay(maxDelay));
    }

    private Result<org.pragmatica.lang.io.TimeSpan> requiredIoTimeSpan(String fullKey) {
        return provider.getString(fullKey)
                       .flatMap(ProviderBasedConfigService::parseIoTimeSpan)
                       .toResult(ConfigError.sectionNotFound(fullKey));
    }

    private org.pragmatica.lang.io.TimeSpan optionalIoTimeSpan(String fullKey,
                                                               org.pragmatica.lang.io.TimeSpan fallback) {
        return provider.getString(fullKey)
                       .flatMap(ProviderBasedConfigService::parseIoTimeSpan)
                       .or(fallback);
    }

    private double optionalDouble(String fullKey, double fallback) {
        return provider.getString(fullKey)
                       .flatMap(ProviderBasedConfigService::safeParseDouble)
                       .or(fallback);
    }

    private boolean optionalBoolean(String fullKey, boolean fallback) {
        return provider.getString(fullKey)
                       .map(Boolean::parseBoolean)
                       .or(fallback);
    }

    // --- DEFAULT field lookup ---
    private static Option<Object> lookupDefaultField(Class<?> type) {
        try {
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

    private static Result<IndexedValue> getDefaultComponentValue(Class<?> configClass,
                                                                 RecordComponent component,
                                                                 int index) {
        return lookupDefaultField(configClass).flatMap(defaultInstance -> invokeAccessor(defaultInstance, component))
                                 .map(value -> new IndexedValue(index, value))
                                 .toResult(ConfigError.sectionNotFound(configClass.getSimpleName()
                                                                      + "." + component.getName()));
    }

    private static Option<Object> invokeAccessor(Object instance, RecordComponent component) {
        try {
            return some(component.getAccessor().invoke(instance));
        } catch (ReflectiveOperationException e) {
            return none();
        }
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
        return success(provider.getString(fullKey).flatMap(parser));
    }

    private Result<Object> handleOptionalEnum(String fullKey, Class<?> innerClass) {
        if (!innerClass.isEnum()) {
            return handleOptionalRecord(fullKey, innerClass);
        }

        var stringOpt = provider.getString(fullKey);

        if (Verify.Is.none(stringOpt)) {
            return success(none());
        }

        return safeParseEnum(stringOpt.unwrap(), innerClass, fullKey).map(Option::option);
    }

    /// Binds a record nested inside `Option` — e.g. `NotificationConfig.smtpConfig`, declared
    /// `Option<SmtpConfig>`, reading section `notification.smtp_config`.
    ///
    /// [#lookupByType] dispatches on `component.getType()`, which for an Option-wrapped component is
    /// the erased `Option.class`. [#lookupNestedRecord] therefore rejects it (`Option` is not a
    /// record) and the component falls to the Option path, which before this method ended at
    /// [#handleOptionalEnum] returning `none()` for anything non-enum. So EVERY `Option<record>`
    /// bound to empty regardless of what the TOML held, with no error — silence that reads as "not
    /// configured" rather than "cannot be configured". `NotificationSenderFactory` failed both its
    /// smtp and http branches for exactly this reason: both sub-configs are `Option<record>`.
    ///
    /// An ABSENT section must still yield `Option.empty()` — that is what `Option` means here, and
    /// it is why this deliberately does NOT use `findDefaultOrError`, which [#lookupNestedRecord]
    /// applies to a bare (non-Option) record component to make a missing section an error.
    private Result<Object> handleOptionalRecord(String fullKey, Class<?> innerClass) {
        if (!innerClass.isRecord() || !hasSection(fullKey)) {
            return success(none());
        }

        return bindToClass(fullKey, innerClass).map(Option::option);
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

        IntStream.range(0, camelCase.length()).forEach(i -> appendSnakeCaseChar(result, camelCase.charAt(i), i));

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
