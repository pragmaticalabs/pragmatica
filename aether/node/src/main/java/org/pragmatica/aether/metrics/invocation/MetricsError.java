package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.lang.Cause;

/**
 * Errors that can occur during metrics operations.
 */
public sealed interface MetricsError extends Cause {
    /**
     * Runtime strategy change is not supported.
     */
    enum StrategyChangeNotSupported implements MetricsError {
        INSTANCE;
        @Override
        public String message() {
            return "Strategy change at runtime requires collector recreation";
        }
    }
}
