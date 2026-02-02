package org.pragmatica.aether.infra.cache;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

/**
 * Error types for cache service operations.
 */
public sealed interface CacheServiceError extends Cause {
    /**
     * Key not found in cache.
     */
    record KeyNotFound(String key) implements CacheServiceError {
        public static KeyNotFound keyNotFound(String key) {
            return new KeyNotFound(key);
        }

        @Override
        public String message() {
            return "Key not found in cache: " + key;
        }
    }

    static KeyNotFound keyNotFound(String key) {
        return KeyNotFound.keyNotFound(key);
    }

    /**
     * Invalid key format.
     */
    record InvalidKey(String key, String reason) implements CacheServiceError {
        public static InvalidKey invalidKey(String key, String reason) {
            return new InvalidKey(key, reason);
        }

        @Override
        public String message() {
            return "Invalid cache key '" + key + "': " + reason;
        }
    }

    static InvalidKey invalidKey(String key, String reason) {
        return InvalidKey.invalidKey(key, reason);
    }

    /**
     * Serialization error.
     */
    record SerializationFailed(String key, Throwable cause) implements CacheServiceError {
        public static SerializationFailed serializationFailed(String key, Throwable cause) {
            return new SerializationFailed(key, cause);
        }

        @Override
        public String message() {
            return "Failed to serialize value for key '" + key + "': " + cause.getMessage();
        }

        @Override
        public Option<Cause> source() {
            return Option.some(Causes.fromThrowable(cause));
        }
    }

    static SerializationFailed serializationFailed(String key, Throwable cause) {
        return SerializationFailed.serializationFailed(key, cause);
    }

    /**
     * Deserialization error.
     */
    record DeserializationFailed(String key, Throwable cause) implements CacheServiceError {
        public static DeserializationFailed deserializationFailed(String key, Throwable cause) {
            return new DeserializationFailed(key, cause);
        }

        @Override
        public String message() {
            return "Failed to deserialize value for key '" + key + "': " + cause.getMessage();
        }

        @Override
        public Option<Cause> source() {
            return Option.some(Causes.fromThrowable(cause));
        }
    }

    static DeserializationFailed deserializationFailed(String key, Throwable cause) {
        return DeserializationFailed.deserializationFailed(key, cause);
    }

    /**
     * Cache operation failed.
     */
    record OperationFailed(String operation, Throwable cause) implements CacheServiceError {
        public static OperationFailed operationFailed(String operation, Throwable cause) {
            return new OperationFailed(operation, cause);
        }

        @Override
        public String message() {
            return "Cache operation failed: " + operation + " - " + cause.getMessage();
        }

        @Override
        public Option<Cause> source() {
            return Option.some(Causes.fromThrowable(cause));
        }
    }

    static OperationFailed operationFailed(String operation, Throwable cause) {
        return OperationFailed.operationFailed(operation, cause);
    }

    /**
     * Cache is full and cannot accept new entries.
     */
    record CacheFull(long maxSize, long currentSize) implements CacheServiceError {
        public static CacheFull cacheFull(long maxSize, long currentSize) {
            return new CacheFull(maxSize, currentSize);
        }

        @Override
        public String message() {
            return "Cache is full: " + currentSize + "/" + maxSize + " entries";
        }
    }

    static CacheFull cacheFull(long maxSize, long currentSize) {
        return CacheFull.cacheFull(maxSize, currentSize);
    }
}
