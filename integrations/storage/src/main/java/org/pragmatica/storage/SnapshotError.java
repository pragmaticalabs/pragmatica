package org.pragmatica.storage;

import java.nio.file.Path;

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

    /// #1013 round 2: the snapshot directory is absent and so is the data root that should hold it.
    /// An absent snapshot directory under a reachable data root is a first boot -- nothing was ever
    /// written. Under a data root that is itself absent or unreadable the same absence means the
    /// volume never mounted, and the two are indistinguishable from the snapshot directory alone.
    /// Refusing keeps a node from coming up read-ready on empty metadata because its disk did not
    /// arrive: #1013's own defect, reached by a different route.
    record DataRootUnreachable(Path dataRoot, String detail) implements SnapshotError {
        @Override
        public String message() {
            return "Snapshot directory is absent and its data root " + dataRoot
                 + " " + detail
                 + "; cannot tell a first boot from a volume that never mounted, "
                 + "refusing to start with EMPTY metadata. "
                 + "See docs/operators/runbooks/backup-recovery.md";
        }
    }
}
