package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Error types for environment integration failures.
public sealed interface EnvironmentError extends Cause {
    record ProvisionFailed(Throwable cause) implements EnvironmentError {
        public static Result<ProvisionFailed> provisionFailed(Throwable cause) {
            return success(new ProvisionFailed(cause));
        }

        @Override
        public String message() {
            return "Node provisioning failed: " + cause.getMessage();
        }
    }

    record TerminateFailed(InstanceId instanceId, Throwable cause) implements EnvironmentError {
        public static Result<TerminateFailed> terminateFailed(InstanceId instanceId, Throwable cause) {
            return success(new TerminateFailed(instanceId, cause));
        }

        @Override
        public String message() {
            return "Instance termination failed for '" + instanceId.value() + "': " + cause.getMessage();
        }
    }

    record InstanceNotFound(InstanceId instanceId) implements EnvironmentError {
        public static Result<InstanceNotFound> instanceNotFound(InstanceId instanceId) {
            return success(new InstanceNotFound(instanceId));
        }

        @Override
        public String message() {
            return "Instance not found: " + instanceId.value();
        }
    }

    record ListInstancesFailed(Throwable cause) implements EnvironmentError {
        public static Result<ListInstancesFailed> listInstancesFailed(Throwable cause) {
            return success(new ListInstancesFailed(cause));
        }

        @Override
        public String message() {
            return "Failed to list instances: " + cause.getMessage();
        }
    }

    record SecretResolutionFailed(String path, Throwable cause) implements EnvironmentError {
        public static Result<SecretResolutionFailed> secretResolutionFailed(String path, Throwable cause) {
            return success(new SecretResolutionFailed(path, cause));
        }

        @Override
        public String message() {
            return "Secret resolution failed for '" + path + "': " + cause.getMessage();
        }
    }

    record unused() implements EnvironmentError {
        public static Result<unused> unused() {
            return success(new unused());
        }

        @Override
        public String message() {
            return "";
        }
    }

    static EnvironmentError provisionFailed(Throwable cause) {
        return ProvisionFailed.provisionFailed(cause)
                              .unwrap();
    }

    static EnvironmentError terminateFailed(InstanceId instanceId, Throwable cause) {
        return TerminateFailed.terminateFailed(instanceId, cause)
                              .unwrap();
    }

    static EnvironmentError instanceNotFound(InstanceId instanceId) {
        return InstanceNotFound.instanceNotFound(instanceId)
                               .unwrap();
    }

    static EnvironmentError listInstancesFailed(Throwable cause) {
        return ListInstancesFailed.listInstancesFailed(cause)
                                  .unwrap();
    }

    static EnvironmentError secretResolutionFailed(String path, Throwable cause) {
        return SecretResolutionFailed.secretResolutionFailed(path, cause)
                                     .unwrap();
    }
}
