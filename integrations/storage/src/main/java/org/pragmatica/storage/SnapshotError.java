package org.pragmatica.storage;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;


/// Snapshot error hierarchy for metadata snapshot operations.
public sealed interface SnapshotError extends Cause {
    SnapshotError INTEGRITY_CHECK_FAILED = new IntegrityCheckFailed();

    record DirectoryCreateFailed(Throwable cause) implements SnapshotError {
        @Override
        public String message() {
            return "Failed to create snapshot directory: " + Causes.fromThrowable(cause).message();
        }
    }

    record WriteFailed(Throwable cause) implements SnapshotError {
        @Override
        public String message() {
            return "Failed to write snapshot: " + Causes.fromThrowable(cause).message();
        }
    }

    record ReadFailed(Throwable cause) implements SnapshotError {
        @Override
        public String message() {
            return "Failed to read snapshot: " + Causes.fromThrowable(cause).message();
        }
    }

    record ParseFailed(Throwable cause) implements SnapshotError {
        @Override
        public String message() {
            return "Failed to parse snapshot: " + Causes.fromThrowable(cause).message();
        }
    }

    record PruneFailed(Throwable cause) implements SnapshotError {
        @Override
        public String message() {
            return "Failed to prune old snapshots: " + Causes.fromThrowable(cause).message();
        }
    }

    record IntegrityCheckFailed() implements SnapshotError {
        @Override
        public String message() {
            return "Snapshot integrity check failed: content hash mismatch";
        }
    }

    /// #1013: snapshot files are on disk and none of them restores. Distinct from "no snapshot
    /// exists", which is a success with none -- this is metadata that WAS acked and is now
    /// unreadable, and a boot must not proceed on empty metadata as if it never existed.
    record NothingRestorable(String latest, int retained) implements SnapshotError {
        @Override
        public String message() {
            return latest
                 + " and none of the " + retained
                 + " other retained snapshot(s) restores; refusing to start with EMPTY metadata. "
                 + "See docs/operators/runbooks/backup-recovery.md";
        }
    }
}
