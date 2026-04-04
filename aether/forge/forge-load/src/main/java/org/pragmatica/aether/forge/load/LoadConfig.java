package org.pragmatica.aether.forge.load;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.pragmatica.lang.Result.success;


/// Root configuration for load generation, containing multiple targets.
///
/// @param targets List of load generation targets to run in parallel
public record LoadConfig(List<LoadTarget> targets) {
    private static final Cause EMPTY_CONFIG = Causes.cause("Load config must have at least one target");

    public static Result<LoadConfig> loadConfig(List<LoadTarget> targets) {
        return success(targets).filter(EMPTY_CONFIG,
                                       list -> !list.isEmpty())
                      .map(List::copyOf)
                      .map(LoadConfig::new);
    }

    public static Result<LoadConfig> loadConfig() {
        return success(new LoadConfig(List.of()));
    }

    public boolean isEmpty() {
        return targets.isEmpty();
    }

    public int totalRequestsPerSecond() {
        return targets.stream().mapToInt(LoadConfig::targetRequestsPerSecond)
                             .sum();
    }

    private static int targetRequestsPerSecond(LoadTarget t) {
        return t.rate().requestsPerSecond();
    }

    public static Result<LoadConfig> loadConfig(LoadConfig config, double multiplier) {
        var scaledTargets = config.targets().stream()
                                          .map(t -> t.withScaledRate(multiplier))
                                          .toList();
        return success(new LoadConfig(scaledTargets));
    }
}
