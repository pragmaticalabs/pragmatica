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

    /// The configured chunk size would produce blocks the instance refuses: `requiredBlockBytes` is the largest block a
    /// full chunk makes once framed, `maxBlockBytes` the instance's limit, `largestChunkSize` the biggest chunk size that fits.
    record ChunkSizeExceedsBlockLimit(int chunkSizeBytes,
                                      long requiredBlockBytes,
                                      int maxBlockBytes,
                                      long largestChunkSize) implements ContentStoreError {
        static ChunkSizeExceedsBlockLimit chunkSizeExceedsBlockLimit(int chunkSizeBytes,
                                                                     long requiredBlockBytes,
                                                                     int maxBlockBytes,
                                                                     long largestChunkSize) {
            return new ChunkSizeExceedsBlockLimit(chunkSizeBytes, requiredBlockBytes, maxBlockBytes, largestChunkSize);
        }

        @Override
        public String message() {
            return "chunkSizeBytes " + chunkSizeBytes
                 + " makes blocks of up to " + requiredBlockBytes
                 + " bytes, over the instance's maximum block size of " + maxBlockBytes
                 + " bytes; use a chunkSizeBytes of at most " + largestChunkSize
                 + " or raise the instance's max_block_bytes";
        }
    }

    record ContentReassemblyFailed(String detail) implements ContentStoreError {
        @Override
        public String message() {
            return "Content reassembly failed: " + detail;
        }
    }
}
