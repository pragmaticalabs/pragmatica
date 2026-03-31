package org.pragmatica.storage;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

/// Compression-related error hierarchy.
public sealed interface CompressionError extends Cause {

    record CompressionFailed(Throwable cause) implements CompressionError {
        @Override
        public String message() {
            return "Compression failed: " + Causes.fromThrowable(cause).message();
        }
    }

    record DecompressionFailed(Throwable cause) implements CompressionError {
        @Override
        public String message() {
            return "Decompression failed: " + Causes.fromThrowable(cause).message();
        }
    }
}
