package org.pragmatica.aether.infra.feature;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Verify.ensure;

/**
 * Configuration for a feature flag.
 *
 * @param defaultValue Default flag value
 * @param percentage   Optional percentage rollout (0-100)
 * @param overrides    Per-context overrides
 */
public record FlagConfig(boolean defaultValue, Option<Integer> percentage, Map<String, Boolean> overrides) {
    /**
     * Create a simple boolean flag.
     *
     * @param enabled Whether flag is enabled by default
     * @return Flag configuration
     */
    public static Result<FlagConfig> flagConfig(boolean enabled) {
        return Result.success(new FlagConfig(enabled, none(), Map.of()));
    }

    /**
     * Create a flag with percentage rollout.
     *
     * @param enabled    Default value
     * @param percentage Percentage of contexts that get true (0-100)
     * @return Result containing configuration or error
     */
    public static Result<FlagConfig> flagConfig(boolean enabled, int percentage) {
        return ensure(percentage, p -> p >= 0 && p <= 100)
                     .map(p -> new FlagConfig(enabled,
                                              some(p),
                                              Map.of()));
    }

    /**
     * Create a flag with context overrides.
     *
     * @param enabled   Default value
     * @param overrides Map of context key to override value
     * @return Flag configuration
     */
    public static Result<FlagConfig> flagConfig(boolean enabled, Map<String, Boolean> overrides) {
        return Result.success(new FlagConfig(enabled, none(), Map.copyOf(overrides)));
    }

    /**
     * Create a fully configured flag.
     *
     * @param enabled    Default value
     * @param percentage Percentage rollout (0-100)
     * @param overrides  Context overrides
     * @return Result containing configuration or error
     */
    public static Result<FlagConfig> flagConfig(boolean enabled, int percentage, Map<String, Boolean> overrides) {
        return ensure(percentage, p -> p >= 0 && p <= 100)
                     .map(p -> new FlagConfig(enabled,
                                              some(p),
                                              Map.copyOf(overrides)));
    }
}
