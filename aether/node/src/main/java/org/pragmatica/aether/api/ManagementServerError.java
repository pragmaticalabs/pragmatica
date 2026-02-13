package org.pragmatica.aether.api;

import org.pragmatica.lang.Cause;

/// Errors for the Management Server.
public sealed interface ManagementServerError extends Cause {
    record BindFailed(int port, Throwable cause) implements ManagementServerError {
        @Override
        public String message() {
            return "Failed to bind management server on port " + port + ": " + cause.getMessage();
        }
    }

    record MissingField(String fieldName) implements ManagementServerError {
        @Override
        public String message() {
            return "Missing '" + fieldName + "' field";
        }
    }

    record InvalidArtifactPath(String path) implements ManagementServerError {
        @Override
        public String message() {
            return "Invalid artifact path. Expected: /repository/info/{groupPath}/{artifactId}/{version}, got: " + path;
        }
    }

    record NotLeader(String leaderId) implements ManagementServerError {
        @Override
        public String message() {
            return "This operation requires the leader node." + (leaderId.isEmpty()
                                                                 ? ""
                                                                 : " Current leader: " + leaderId);
        }
    }

    record StrategyChangeNotSupported() implements ManagementServerError {
        @Override
        public String message() {
            return "Runtime strategy change not supported. Strategy must be set at node startup.";
        }
    }
}
