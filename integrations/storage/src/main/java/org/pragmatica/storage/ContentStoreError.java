package org.pragmatica.storage;

import org.pragmatica.lang.Cause;


/// Error hierarchy for content store operations.
public sealed interface ContentStoreError extends Cause {
    enum General implements ContentStoreError {
        CONTENT_NOT_FOUND("Content not found"),
        MANIFEST_PARSE_FAILED("Failed to parse content manifest"),
        CHUNK_MISSING("One or more content chunks are missing"),
        ENTRY_PARSE_FAILED("Failed to parse object entry"),
        WRITER_CLOSED("The object writer is committed or aborted");
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

    /// The configured chunk size is not positive: no content can be split into chunks of `chunkSizeBytes`. The instance's
    /// `maxBlockBytes` is named beside it so the refusal states what the size was judged against.
    record ChunkSizeNotPositive(int chunkSizeBytes, int maxBlockBytes) implements ContentStoreError {
        static ChunkSizeNotPositive chunkSizeNotPositive(int chunkSizeBytes, int maxBlockBytes) {
            return new ChunkSizeNotPositive(chunkSizeBytes, maxBlockBytes);
        }

        @Override
        public String message() {
            return "chunkSizeBytes " + chunkSizeBytes
                 + " must be at least 1 (the instance's maximum block size is " + maxBlockBytes
                 + " bytes)";
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

    /// The operation is not available in local mode.
    record NotSupported(String operation) implements ContentStoreError {
        public static NotSupported notSupported(String operation) {
            return new NotSupported(operation);
        }

        @Override
        public String message() {
            return operation + " is not supported in local mode";
        }
    }

    record ObjectNotFound(String id) implements ContentStoreError {
        static ObjectNotFound objectNotFound(String id) {
            return new ObjectNotFound(id);
        }

        @Override
        public String message() {
            return "Object not found: " + id;
        }
    }

    record InvalidRange(String detail) implements ContentStoreError {
        static InvalidRange invalidRange(String detail) {
            return new InvalidRange(detail);
        }

        @Override
        public String message() {
            return "Invalid byte range: " + detail;
        }
    }

    record PreconditionFailed(String detail) implements ContentStoreError {
        static PreconditionFailed preconditionFailed(String detail) {
            return new PreconditionFailed(detail);
        }

        @Override
        public String message() {
            return "Precondition failed: " + detail;
        }
    }

    record LengthMismatch(long declared, long actual) implements ContentStoreError {
        static LengthMismatch lengthMismatch(long declared, long actual) {
            return new LengthMismatch(declared, actual);
        }

        @Override
        public String message() {
            return "Declared length " + declared + " but " + actual + " bytes were written";
        }
    }

    record DigestMismatch(String expected, String actual) implements ContentStoreError {
        static DigestMismatch digestMismatch(String expected, String actual) {
            return new DigestMismatch(expected, actual);
        }

        @Override
        public String message() {
            return "Expected SHA-256 " + expected + " but the bytes written hash to " + actual;
        }
    }

    /// A setting of the content store is not usable; `field` names it.
    record InvalidConfig(String field, String detail) implements ContentStoreError {
        static InvalidConfig invalidConfig(String field, String detail) {
            return new InvalidConfig(field, detail);
        }

        @Override
        public String message() {
            return "Invalid content store setting '" + field + "': " + detail;
        }
    }

    record InvalidIdentifier(String kind, String detail) implements ContentStoreError {
        static InvalidIdentifier invalidIdentifier(String kind, String detail) {
            return new InvalidIdentifier(kind, detail);
        }

        @Override
        public String message() {
            return "Invalid " + kind + ": " + detail;
        }
    }
}
