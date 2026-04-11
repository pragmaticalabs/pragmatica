package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Cause;


/// Error types for synchronous replication operations.
public sealed interface ReplicationError extends Cause {
    enum General implements ReplicationError {
        NOT_ENOUGH_REPLICAS("Not enough replicas available for requested acknowledgment count"),
        REPLICATION_TIMEOUT("Replication acknowledgment timed out");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }
}
