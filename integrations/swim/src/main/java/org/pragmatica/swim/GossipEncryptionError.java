package org.pragmatica.swim;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

/// Error types for gossip message encryption/decryption.
public sealed interface GossipEncryptionError extends Cause {

    /// Encryption operation failed.
    record EncryptionFailed(Throwable cause) implements GossipEncryptionError {
        @Override
        public String message() {
            return "Gossip encryption failed: " + Causes.fromThrowable(cause);
        }
    }

    /// Decryption operation failed.
    record DecryptionFailed(Throwable cause) implements GossipEncryptionError {
        @Override
        public String message() {
            return "Gossip decryption failed: " + Causes.fromThrowable(cause);
        }
    }

    /// AES key is not the required 32 bytes.
    record InvalidKeySize(int actual) implements GossipEncryptionError {
        @Override
        public String message() {
            return "Invalid AES key size: " + actual + " (expected 32)";
        }
    }

    /// Received message has an unrecognized key ID.
    record UnknownKeyId(int keyId) implements GossipEncryptionError {
        @Override
        public String message() {
            return "Unknown gossip key ID: " + keyId;
        }
    }
}
