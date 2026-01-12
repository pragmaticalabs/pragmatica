package org.pragmatica.aether.infra.feature;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

/**
 * Runtime feature toggles with targeting support.
 */
public interface FeatureFlags extends Slice {
    /**
     * Check if a flag is enabled (without context).
     *
     * @param flag Flag name
     * @return Promise with flag value
     */
    Promise<Boolean> isEnabled(String flag);

    /**
     * Check if a flag is enabled for a specific context.
     *
     * @param flag    Flag name
     * @param context Evaluation context
     * @return Promise with flag value
     */
    Promise<Boolean> isEnabled(String flag, Context context);

    /**
     * Get variant for a flag (for A/B testing).
     *
     * @param flag Flag name
     * @return Promise with variant name
     */
    Promise<Option<String>> getVariant(String flag);

    /**
     * Get variant for a flag with context.
     *
     * @param flag    Flag name
     * @param context Evaluation context
     * @return Promise with variant name
     */
    Promise<Option<String>> getVariant(String flag, Context context);

    /**
     * Set or update a flag configuration.
     *
     * @param flag   Flag name
     * @param config Flag configuration
     * @return Promise completing when flag is set
     */
    Promise<Unit> setFlag(String flag, FlagConfig config);

    /**
     * Delete a flag.
     *
     * @param flag Flag name
     * @return Promise completing when flag is deleted
     */
    Promise<Unit> deleteFlag(String flag);

    /**
     * List all flags.
     *
     * @return Promise with map of flag names to configurations
     */
    Promise<Map<String, FlagConfig>> listFlags();

    /**
     * Factory method for in-memory implementation.
     *
     * @return FeatureFlags instance
     */
    static FeatureFlags inMemory() {
        return new InMemoryFeatureFlags();
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
