package org.pragmatica.storage;

import org.pragmatica.lang.Cause;

/// Error hierarchy for content store operations.
public sealed interface ContentStoreError extends Cause {

    enum General implements ContentStoreError {
        CONTENT_NOT_FOUND("Content not found"),
        MANIFEST_PARSE_FAILED("Failed to parse content manifest"),
        CHUNK_MISSING("One or more content chunks are missing");

        private final String message;

        General(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    record ChunkStoreFailed(int chunkIndex, Cause cause) implements ContentStoreError {
        @Override
        public String message() {
            return "Failed to store chunk " + chunkIndex + ": " + cause.message();
        }
    }

    record ContentReassemblyFailed(String detail) implements ContentStoreError {
        @Override
        public String message() {
            return "Content reassembly failed: " + detail;
        }
    }
}
