package org.pragmatica.aether.forge.load;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

/**
 * Root configuration for load generation, containing multiple targets.
 *
 * @param targets List of load generation targets to run in parallel
 */
public record LoadConfig(List<LoadTarget> targets) {
    private static final Cause EMPTY_CONFIG = Causes.cause("Load config must have at least one target");

    /**
     * Creates a LoadConfig with validation.
     */
    public static Result<LoadConfig> loadConfig(List<LoadTarget> targets) {
        return Verify.ensure(targets,
                             list -> list != null && !list.isEmpty(),
                             EMPTY_CONFIG)
                     .map(List::copyOf)
                     .map(LoadConfig::new);
    }

    /**
     * Creates an empty LoadConfig (for initial state).
     */
    public static LoadConfig empty() {
        return new LoadConfig(List.of());
    }

    /**
     * Returns true if this config has no targets.
     */
    public boolean isEmpty() {
        return targets.isEmpty();
    }

    /**
     * Returns the total target requests per second across all targets.
     */
    public int totalRequestsPerSecond() {
        return targets.stream()
                      .mapToInt(t -> t.rate()
                                      .requestsPerSecond())
                      .sum();
    }
}
