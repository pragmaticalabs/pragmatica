package org.pragmatica.storage;

import org.pragmatica.lang.Cause;


/// Storage error hierarchy.
public sealed interface StorageError extends Cause {
    record BlockNotFound(BlockId blockId) implements StorageError {
        static BlockNotFound blockNotFound(BlockId blockId) {
            return new BlockNotFound(blockId);
        }

        @Override
        public String message() {
            return "Block not found: " + blockId;
        }
    }

    record IntegrityError(BlockId expected, BlockId actual) implements StorageError {
        static IntegrityError integrityError(BlockId expected, BlockId actual) {
            return new IntegrityError(expected, actual);
        }

        @Override
        public String message() {
            return "Integrity check failed: expected " + expected + ", got " + actual;
        }
    }

    record TierFull(TierLevel tier, long usedBytes, long maxBytes) implements StorageError {
        static TierFull tierFull(TierLevel tier, long usedBytes, long maxBytes) {
            return new TierFull(tier, usedBytes, maxBytes);
        }

        @Override
        public String message() {
            return tier + " tier full: " + usedBytes + "/" + maxBytes + " bytes";
        }
    }

    record WriteError(String detail) implements StorageError {
        static WriteError writeError(String detail) {
            return new WriteError(detail);
        }

        @Override
        public String message() {
            return "Storage write error: " + detail;
        }
    }

    record ReadError(String detail) implements StorageError {
        static ReadError readError(String detail) {
            return new ReadError(detail);
        }

        @Override
        public String message() {
            return "Storage read error: " + detail;
        }
    }

    /// #858 C1: a read reached a DHT-backed tier before its post-formation encryption-marker check
    /// admitted it, and the admission bound (`DhtStorageTier`'s local copy of
    /// `StorageFactory#DHT_MARKER_TIMEOUT`) elapsed while it was still pending -- distinct from the
    /// tier being REFUSED (that fails immediately with the refusal cause, e.g.
    /// `EncryptionError.EncryptedTierRequiresKeyring`, never this one). No package-private static
    /// factory here, unlike this file's other records -- the only constructor is
    /// `DhtStorageTier` in `org.pragmatica.aether.storage`, a different package, so a
    /// package-private factory would be unreachable from it (see `EncryptionError`'s bare-constructor
    /// records for the same cross-package precedent). Recovery: none from the read path -- retry once
    /// `start()`'s marker check has resolved the tier's admission gate. #1052: that check retries while
    /// the DHT cannot answer, and the node reports not-ready (`/health/ready` component
    /// `dht-admission`) until it completes, so this error can outlast one admission bound. Check the
    /// node's logs for the attempt failures.
    record TierNotAdmitted(String instanceName, long timeoutMillis) implements StorageError {
        @Override
        public String message() {
            return "DHT tier '" + instanceName + "' not yet admitted (waited " + timeoutMillis + "ms)";
        }
    }

    /// [StorageInstance#openLog] on an instance built without a log root: it has nowhere on local
    /// disk to put a log, and a log is never placed on any other tier (#1567).
    record LogsUnsupported(String instanceName) implements StorageError {
        static LogsUnsupported logsUnsupported(String instanceName) {
            return new LogsUnsupported(instanceName);
        }

        @Override
        public String message() {
            return "Storage instance '" + instanceName + "' has no log root; it cannot open append logs";
        }
    }

    /// A log name that is blank or does not resolve to a file strictly under the instance's log root
    /// (absolute, or climbing out with `..`).
    record InvalidLogName(String name) implements StorageError {
        static InvalidLogName invalidLogName(String name) {
            return new InvalidLogName(name);
        }

        @Override
        public String message() {
            return "Invalid append-log name '" + name + "': must be a relative path under the log root";
        }
    }

    /// [StorageInstance#seal] on an instance with no durable tier ([StorageTier#isDurable]): the block
    /// could not be made to survive a power loss, so the range is not sealed and its log keeps it (#1567).
    /// Recovery: configure a local-disk tier for the instance; the sealer retries.
    record NoDurableTier(String instanceName) implements StorageError {
        static NoDurableTier noDurableTier(String instanceName) {
            return new NoDurableTier(instanceName);
        }

        @Override
        public String message() {
            return "Storage instance '" + instanceName + "' has no durable tier; refusing to seal";
        }
    }

    record InvalidSealRange(long fromOffset, long toOffset) implements StorageError {
        static InvalidSealRange invalidSealRange(long fromOffset, long toOffset) {
            return new InvalidSealRange(fromOffset, toOffset);
        }

        @Override
        public String message() {
            return "Invalid seal range [" + fromOffset + "-" + toOffset + "]";
        }
    }
}
