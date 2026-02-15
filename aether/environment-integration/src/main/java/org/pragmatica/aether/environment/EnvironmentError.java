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

    record TerminateFailed(InstanceId instanceId, Throwable cause) implements EnvironmentError {
        @Override
        public String message() {
            return "Instance termination failed for '" + instanceId.value() + "': " + cause.getMessage();
        }
    }

    record InstanceNotFound(InstanceId instanceId) implements EnvironmentError {
        @Override
        public String message() {
            return "Instance not found: " + instanceId.value();
        }
    }

    record ListInstancesFailed(Throwable cause) implements EnvironmentError {
        @Override
        public String message() {
            return "Failed to list instances: " + cause.getMessage();
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

    static EnvironmentError terminateFailed(InstanceId instanceId, Throwable cause) {
        return new TerminateFailed(instanceId, cause);
    }

    static EnvironmentError instanceNotFound(InstanceId instanceId) {
        return new InstanceNotFound(instanceId);
    }

    static EnvironmentError listInstancesFailed(Throwable cause) {
        return new ListInstancesFailed(cause);
    }

    static EnvironmentError secretResolutionFailed(String path, Throwable cause) {
        return new SecretResolutionFailed(path, cause);
    }
}
