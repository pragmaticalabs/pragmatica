package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;

/// Error types for environment integration failures.
public sealed interface EnvironmentError extends Cause {
    record ProvisionFailed(Throwable cause) implements EnvironmentError {
        @Override
        public String message() {
            return "Node provisioning failed: " + cause.getMessage();
        }
    }

    record SecretResolutionFailed(String path, Throwable cause) implements EnvironmentError {
        @Override
        public String message() {
            return "Secret resolution failed for '" + path + "': " + cause.getMessage();
        }
    }

    static EnvironmentError provisionFailed(Throwable cause) {
        return new ProvisionFailed(cause);
    }

    static EnvironmentError secretResolutionFailed(String path, Throwable cause) {
        return new SecretResolutionFailed(path, cause);
    }
}
