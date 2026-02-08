package org.pragmatica.aether.provider;

import org.pragmatica.lang.Cause;

/**
 * Error types for node provisioning failures.
 */
public sealed interface NodeProviderError extends Cause {
    record ProvisionFailed(Throwable cause) implements NodeProviderError {
        @Override
        public String message() {
            return "Node provisioning failed: " + cause.getMessage();
        }
    }

    static NodeProviderError provisionFailed(Throwable cause) {
        return new ProvisionFailed(cause);
    }
}
