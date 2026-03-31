package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Cause;

/// Error types for failover recovery operations.
public sealed interface FailoverRecoveryError extends Cause {

    enum General implements FailoverRecoveryError {
        NO_REPLICAS_AVAILABLE("No replicas available for catch-up");

        private final String message;

        General(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    record CatchupFailed(String streamName, int partition, Cause underlying) implements FailoverRecoveryError {

        @Override
        public String message() {
            return "Catch-up failed for %s[%d]: %s".formatted(streamName, partition, underlying.message());
        }
    }
}
