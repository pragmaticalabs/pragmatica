package org.pragmatica.storage;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

/// Encryption error hierarchy.
public sealed interface EncryptionError extends Cause {

    record EncryptionFailed(Throwable cause) implements EncryptionError {
        @Override
        public String message() {
            return "Encryption failed: " + Causes.fromThrowable(cause).message();
        }
    }

    record DecryptionFailed(Throwable cause) implements EncryptionError {
        @Override
        public String message() {
            return "Decryption failed: " + Causes.fromThrowable(cause).message();
        }
    }

    record InvalidKeyLength(int actual, int expected) implements EncryptionError {
        @Override
        public String message() {
            return "Invalid key length: " + actual + " bytes, expected " + expected;
        }
    }
}
