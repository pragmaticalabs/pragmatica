package org.pragmatica.aether.infra.feature;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * In-memory implementation of FeatureFlags for testing and single-node scenarios.
 */
final class InMemoryFeatureFlags implements FeatureFlags {
    private final ConcurrentHashMap<String, FlagConfig> flags = new ConcurrentHashMap<>();

    @Override
    public Promise<Boolean> isEnabled(String flag) {
        return isEnabled(flag, Context.empty());
    }

    @Override
    public Promise<Boolean> isEnabled(String flag, Context context) {
        return Promise.success(option(flags.get(flag))
                                     .map(config -> evaluateFlag(config, context))
                                     .or(false));
    }

    @Override
    public Promise<Option<String>> getVariant(String flag) {
        return getVariant(flag, Context.empty());
    }

    @Override
    public Promise<Option<String>> getVariant(String flag, Context context) {
        return Promise.success(option(flags.get(flag))
                                     .map(config -> evaluateFlag(config, context))
                                     .map(enabled -> enabled
                                                     ? "enabled"
                                                     : "disabled"));
    }

    @Override
    public Promise<Unit> setFlag(String flag, FlagConfig config) {
        flags.put(flag, config);
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> deleteFlag(String flag) {
        return option(flags.remove(flag))
                     .map(removed -> Promise.<Unit>success(unit()))
                     .or(() -> new FeatureFlagError.FlagNotFound(flag).promise());
    }

    @Override
    public Promise<Map<String, FlagConfig>> listFlags() {
        return Promise.success(Map.copyOf(flags));
    }

    private boolean evaluateFlag(FlagConfig config, Context context) {
        // Check context overrides first
        for (var override : config.overrides()
                                  .entrySet()) {
            if (context.get(override.getKey())
                       .isPresent()) {
                return override.getValue();
            }
        }
        // Check percentage rollout
        return config.percentage()
                     .map(pct -> evaluatePercentage(context, pct))
                     .or(config.defaultValue());
    }

    private boolean evaluatePercentage(Context context, int percentage) {
        // Use context hash for consistent assignment
        var hash = context.attributes()
                          .hashCode();
        var bucket = Math.abs(hash % 100);
        return bucket < percentage;
    }
}
