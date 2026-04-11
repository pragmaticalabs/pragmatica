package org.pragmatica.aether.stream.forward;

import org.pragmatica.lang.Cause;


/// Error types for stream publish forwarding.
public sealed interface StreamForwardError extends Cause {
    enum General implements StreamForwardError {
        FORWARD_TIMEOUT("Stream publish forward timed out"),
        GOVERNOR_UNAVAILABLE("No governor available for STREAMING task group");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    record RemotePublishFailed(String detail) implements StreamForwardError {
        @Override public String message() {
            return "Remote publish failed: " + detail;
        }
    }
}
