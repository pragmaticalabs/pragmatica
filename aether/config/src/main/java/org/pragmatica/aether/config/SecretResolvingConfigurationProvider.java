package org.pragmatica.aether.config;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
    /// Scans all merged values for ${secrets:path} patterns and resolves them
    /// using the provided resolver function. Resolution is eager (at call time).
    ///
    /// @param provider       The configuration provider with unresolved values
    /// @param secretResolver Function that resolves secret paths to values
    /// @return New ConfigurationProvider with all secrets resolved, or failure
    static Result<ConfigurationProvider> resolve(ConfigurationProvider provider,
                                                 Fn1<Promise<String>, String> secretResolver) {
        var originalMap = provider.asMap();
        var resolvedMap = new LinkedHashMap<String, String>();
        for (var entry : originalMap.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            var resolvedValue = resolveValue(key, value, secretResolver);
            if (resolvedValue.isFailure()) {
                return resolvedValue.map(_ -> (ConfigurationProvider) null);
            }
            resolvedMap.put(key, resolvedValue.unwrap());
        }
        return Result.success(new SecretResolvingConfigurationProvider(provider, resolvedMap));
    }

    private static Result<String> resolveValue(String key, String value, Fn1<Promise<String>, String> secretResolver) {
        var matcher = SECRET_PATTERN.matcher(value);
        if (!matcher.find()) {
            return Result.success(value);
        }
        var result = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            var secretPath = matcher.group(1);
            var resolved = secretResolver.apply(secretPath)
                                         .await();
            switch (resolved) {
                case Result.Failure<String>(var cause) -> {
                    return Result.failure(ConfigError.secretResolutionFailed(key, secretPath, cause));
                }
                case Result.Success<String>(var secretValue) ->
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(secretValue));
            }
        }
        matcher.appendTail(result);
        return Result.success(result.toString());
    }

    @Override
    public List<ConfigSource> sources() {
        return delegate.sources();
    }

    @Override
    public org.pragmatica.lang.Option<String> getString(String key) {
        return org.pragmatica.lang.Option.option(resolvedValues.get(key));
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
