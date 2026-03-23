package org.pragmatica.aether.stream;

import org.pragmatica.lang.Cause;

/// Error types for stream ring buffer operations.
public sealed interface StreamError extends Cause {

    enum General implements StreamError {
        BUFFER_CLOSED("Ring buffer is closed"),
        BUFFER_EMPTY("Ring buffer is empty");

        private final String message;

        General(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    record EventTooLarge(int eventSize, long maxSize) implements StreamError {
        @Override
        public String message() {
            return "Event size %d exceeds maximum %d".formatted(eventSize, maxSize);
        }
    }

    record CursorExpired(long requestedOffset, long tailOffset) implements StreamError {
        @Override
        public String message() {
            return "Cursor at offset %d has expired, oldest available is %d".formatted(requestedOffset, tailOffset);
        }
    }
}
