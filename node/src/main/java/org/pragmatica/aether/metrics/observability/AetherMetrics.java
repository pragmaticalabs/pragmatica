package org.pragmatica.aether.metrics.observability;

import org.pragmatica.metrics.PromiseMetrics;

import io.micrometer.core.instrument.Counter;

/**
 * Pre-configured metrics for Aether operations.
 *
 * <p>Provides standardized metric names and tags for:
 * <ul>
 *   <li>Slice invocations (local and remote)</li>
 *   <li>Consensus operations</li>
 *   <li>Deployment lifecycle</li>
 *   <li>HTTP routing</li>
 * </ul>
 */
public interface AetherMetrics {
    // Slice invocation metrics
    PromiseMetrics sliceInvocation(String artifact, String method);

    PromiseMetrics localInvocation(String artifact, String method);

    PromiseMetrics remoteInvocation(String artifact, String method);

    // Deployment metrics
    PromiseMetrics sliceLoad(String artifact);

    PromiseMetrics sliceActivate(String artifact);

    PromiseMetrics sliceDeactivate(String artifact);

    // Consensus metrics
    PromiseMetrics consensusCommit();

    Counter consensusBatchCounter();

    // HTTP routing metrics
    PromiseMetrics httpRequest(String method, String path);

    Counter httpRequestCounter(String method, String path, String status);

    // Rolling update metrics
    Counter rollingUpdateStarted();

    Counter rollingUpdateCompleted();

    Counter rollingUpdateRolledBack();

    /**
     * Create Aether metrics from an observability registry.
     */
    static AetherMetrics aetherMetrics(ObservabilityRegistry registry) {
        record aetherMetrics(ObservabilityRegistry registry) implements AetherMetrics {
            @Override
            public PromiseMetrics sliceInvocation(String artifact, String method) {
                return registry.combined("aether.slice.invocation", "artifact", artifact, "method", method);
            }

            @Override
            public PromiseMetrics localInvocation(String artifact, String method) {
                return registry.combined("aether.slice.invocation.local", "artifact", artifact, "method", method);
            }

            @Override
            public PromiseMetrics remoteInvocation(String artifact, String method) {
                return registry.combined("aether.slice.invocation.remote", "artifact", artifact, "method", method);
            }

            @Override
            public PromiseMetrics sliceLoad(String artifact) {
                return registry.timer("aether.slice.load", "artifact", artifact);
            }

            @Override
            public PromiseMetrics sliceActivate(String artifact) {
                return registry.timer("aether.slice.activate", "artifact", artifact);
            }

            @Override
            public PromiseMetrics sliceDeactivate(String artifact) {
                return registry.timer("aether.slice.deactivate", "artifact", artifact);
            }

            @Override
            public PromiseMetrics consensusCommit() {
                return registry.combined("aether.consensus.commit");
            }

            @Override
            public Counter consensusBatchCounter() {
                return registry.counter("aether.consensus.batches");
            }

            @Override
            public PromiseMetrics httpRequest(String method, String path) {
                return registry.combined("aether.http.request", "method", method, "path", path);
            }

            @Override
            public Counter httpRequestCounter(String method, String path, String status) {
                return registry.counter("aether.http.requests", "method", method, "path", path, "status", status);
            }

            @Override
            public Counter rollingUpdateStarted() {
                return registry.counter("aether.rolling_update.started");
            }

            @Override
            public Counter rollingUpdateCompleted() {
                return registry.counter("aether.rolling_update.completed");
            }

            @Override
            public Counter rollingUpdateRolledBack() {
                return registry.counter("aether.rolling_update.rolled_back");
            }
        }
        return new aetherMetrics(registry);
    }
}
