package org.pragmatica.aether.dht;

import org.pragmatica.lang.Cause;


/// Error causes for ReplicatedMap operations.
public sealed interface ReplicatedMapError extends Cause {
    record SerializationFailed(String detail) implements ReplicatedMapError {
        @Override public String message() {
            return "Serialization failed: " + detail;
        }
    }

    record DhtOperationFailed(Cause underlying) implements ReplicatedMapError {
        @Override public String message() {
            return "DHT operation failed: " + underlying.message();
        }
    }
}
