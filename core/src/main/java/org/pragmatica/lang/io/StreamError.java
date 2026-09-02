package org.pragmatica.lang.io;

import org.pragmatica.lang.Cause;


/// Error types for stream and classpath resource I/O operations.
public sealed interface StreamError extends Cause {
    record ReadFailed(String detail) implements StreamError {
        @Override
        public String message() {
            return "Failed to read stream: " + detail;
        }
    }

    record ResourceNotFound(String resourcePath) implements StreamError {
        @Override
        public String message() {
            return "Classpath resource not found: " + resourcePath;
        }
    }

    record ResourceReadFailed(String resourcePath, String detail) implements StreamError {
        @Override
        public String message() {
            return "Failed to read classpath resource " + resourcePath + ": " + detail;
        }
    }
}
