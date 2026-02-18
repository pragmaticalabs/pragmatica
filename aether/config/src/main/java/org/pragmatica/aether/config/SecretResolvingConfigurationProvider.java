package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;

/// Decorates a ConfigurationProvider by resolving ${secrets:path} placeholders
/// in all string values using a resolver function.
///
/// Resolution is eager: all placeholders are resolved at construction time.
/// The resolver function maps secret paths to their values asynchronously.
final class SecretResolvingConfigurationProvider implements ConfigurationProvider {
    private static final Pattern SECRET_PATTERN = Pattern.compile("\\$\\{secrets:([^}]+)}");

    private final ConfigurationProvider delegate;
    private final Map<String, String> resolvedValues;

    private SecretResolvingConfigurationProvider(ConfigurationProvider delegate, Map<String, String> resolvedValues) {
        this.delegate = delegate;
        this.resolvedValues = Map.copyOf(resolvedValues);
    }

    /// Resolve all ${secrets:path} placeholders in the provider's values.
    ///
    /// @param provider       The configuration provider with unresolved values
    /// @param secretResolver Function that resolves secret paths to values
    /// @return New ConfigurationProvider with all secrets resolved, or failure
    static Result<ConfigurationProvider> resolve(ConfigurationProvider provider,
                                                 Fn1<Promise<String>, String> secretResolver) {
        return resolveAllEntries(provider, secretResolver).map(resolved -> wrapProvider(provider, resolved));
    }

    private static ConfigurationProvider wrapProvider(ConfigurationProvider provider, Map<String, String> resolved) {
        return new SecretResolvingConfigurationProvider(provider, resolved);
    }

    private static Result<Map<String, String>> resolveAllEntries(ConfigurationProvider provider,
                                                                 Fn1<Promise<String>, String> secretResolver) {
        var originalMap = provider.asMap();
        var resolvedMap = new LinkedHashMap<String, String>();
        for (var entry : originalMap.entrySet()) {
            var resolved = storeResolvedEntry(entry, secretResolver, resolvedMap);
            if (resolved.isFailure()) {
                return resolved.map(_ -> resolvedMap);
            }
        }
        return success(resolvedMap);
    }

    private static Result<Unit> storeResolvedEntry(Map.Entry<String, String> entry,
                                                   Fn1<Promise<String>, String> secretResolver,
                                                   Map<String, String> resolvedMap) {
        var resolved = fetchResolvedValue(entry.getKey(), entry.getValue(), secretResolver);
        if (resolved.isFailure()) {
            return resolved.mapToUnit();
        }
        resolvedMap.put(entry.getKey(), resolved.unwrap());
        return unitResult();
    }

    private static Result<String> fetchResolvedValue(String key,
                                                     String value,
                                                     Fn1<Promise<String>, String> secretResolver) {
        var matcher = SECRET_PATTERN.matcher(value);
        if (!matcher.find()) {
            return success(value);
        }
        return replaceSecrets(key, value, secretResolver);
    }

    private static Result<String> replaceSecrets(String key,
                                                 String value,
                                                 Fn1<Promise<String>, String> secretResolver) {
        var matcher = SECRET_PATTERN.matcher(value);
        var result = new StringBuilder();
        var replacementResult = matchReplacements(matcher, result, key, secretResolver);
        if (replacementResult.isFailure()) {
            return replacementResult.map(_ -> "");
        }
        matcher.appendTail(result);
        return success(result.toString());
    }

    private static Result<Unit> matchReplacements(Matcher matcher,
                                                  StringBuilder result,
                                                  String key,
                                                  Fn1<Promise<String>, String> secretResolver) {
        if (!matcher.find()) {
            return unitResult();
        }
        return substituteSecret(matcher, result, key, secretResolver)
        .flatMap(_ -> matchReplacements(matcher, result, key, secretResolver));
    }

    private static Result<Unit> substituteSecret(Matcher matcher,
                                                 StringBuilder result,
                                                 String key,
                                                 Fn1<Promise<String>, String> secretResolver) {
        var secretPath = matcher.group(1);
        var fetched = fetchSecret(secretResolver, secretPath);
        return switch (fetched) {
            case Result.Failure<String>(var cause) ->
            secretFailure(key, secretPath, cause);
            case Result.Success<String>(var secretValue) -> {
                matcher.appendReplacement(result, Matcher.quoteReplacement(secretValue));
                yield unitResult();
            }
        };
    }

    private static Result<String> fetchSecret(Fn1<Promise<String>, String> secretResolver, String secretPath) {
        return secretResolver.apply(secretPath)
                             .await();
    }

    private static Result<Unit> secretFailure(String key, String secretPath, Cause cause) {
        return ConfigError.secretResolutionFailed(key, secretPath, cause)
                          .result();
    }

    @Override
    public List<ConfigSource> sources() {
        return delegate.sources();
    }

    @Override
    public Option<String> getString(String key) {
        return option(resolvedValues.get(key));
    }

    @Override
    public Set<String> keys() {
        return resolvedValues.keySet();
    }

    @Override
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(resolvedValues);
    }

    @Override
    public String name() {
        return "SecretResolvingConfigurationProvider[" + delegate.name() + "]";
    }

    @Override
    public Result<ConfigSource> reload() {
        return delegate.reload();
    }
}
