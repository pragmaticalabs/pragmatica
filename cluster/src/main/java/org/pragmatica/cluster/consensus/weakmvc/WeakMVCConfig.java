package org.pragmatica.cluster.consensus.weakmvc;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Configuration for the Weak MVC consensus engine.
 */
public record WeakMVCConfig(
        TimeSpan cleanupInterval,
        TimeSpan syncRetryInterval,
        int maxSyncAttempts,
        long removeOlderThanPhases
) {
    /**
     * Creates a default configuration.
     *
     * @return A default configuration
     */
    public static WeakMVCConfig defaultConfig() {
        return new WeakMVCConfig(timeSpan(60).seconds(),
                                 timeSpan(5).seconds(),
                                 10,
                                 100);
    }
    public static WeakMVCConfig testConfig() {
        return new WeakMVCConfig(timeSpan(60).seconds(),
                                 timeSpan(100).millis(),
                                 3,
                                 100);
    }
}
