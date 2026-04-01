package org.pragmatica.storage;

import org.pragmatica.lang.Cause;

/// Storage error hierarchy.
public sealed interface StorageError extends Cause {

    record BlockNotFound(BlockId blockId) implements StorageError {
        static BlockNotFound blockNotFound(BlockId blockId) { return new BlockNotFound(blockId); }

        @Override
        public String message() {
            return "Block not found: " + blockId;
        }
    }

    record IntegrityError(BlockId expected, BlockId actual) implements StorageError {
        static IntegrityError integrityError(BlockId expected, BlockId actual) { return new IntegrityError(expected, actual); }

        @Override
        public String message() {
            return "Integrity check failed: expected " + expected + ", got " + actual;
        }
    }

    record TierFull(TierLevel tier, long usedBytes, long maxBytes) implements StorageError {
        static TierFull tierFull(TierLevel tier, long usedBytes, long maxBytes) { return new TierFull(tier, usedBytes, maxBytes); }

        @Override
        public String message() {
            return tier + " tier full: " + usedBytes + "/" + maxBytes + " bytes";
        }
    }

    record WriteError(String detail) implements StorageError {
        static WriteError writeError(String detail) { return new WriteError(detail); }

        @Override
        public String message() {
            return "Storage write error: " + detail;
        }
    }

    record ReadError(String detail) implements StorageError {
        static ReadError readError(String detail) { return new ReadError(detail); }

        @Override
        public String message() {
            return "Storage read error: " + detail;
        }
    }
}
