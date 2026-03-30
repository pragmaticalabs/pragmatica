package org.pragmatica.aether.storage;

import org.pragmatica.lang.Cause;

/// Storage error hierarchy.
public sealed interface StorageError extends Cause {

    record BlockNotFound(BlockId blockId) implements StorageError {
        @Override
        public String message() {
            return "Block not found: " + blockId;
        }
    }

    record IntegrityError(BlockId expected, BlockId actual) implements StorageError {
        @Override
        public String message() {
            return "Integrity check failed: expected " + expected + ", got " + actual;
        }
    }

    record TierFull(TierLevel tier, long usedBytes, long maxBytes) implements StorageError {
        @Override
        public String message() {
            return tier + " tier full: " + usedBytes + "/" + maxBytes + " bytes";
        }
    }

    record WriteError(String detail) implements StorageError {
        @Override
        public String message() {
            return "Storage write error: " + detail;
        }
    }

    record ReadError(String detail) implements StorageError {
        @Override
        public String message() {
            return "Storage read error: " + detail;
        }
    }
}
