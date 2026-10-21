package org.pragmatica.storage;

import java.nio.file.Path;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;


/// Snapshot error hierarchy for metadata snapshot operations.
public sealed interface SnapshotError extends Cause {
    SnapshotError INTEGRITY_CHECK_FAILED = new IntegrityCheckFailed();

    /// A [SnapshotManager] that cannot report what it wrote (#1604).
    enum General implements SnapshotError {
        SNAPSHOT_NOW_UNSUPPORTED("This snapshot manager cannot report the snapshot it writes"),
        /// #2257: the encoded snapshot parsed and validated, but is not the snapshot that was captured.
        ROUND_TRIP_MISMATCH("Snapshot read back differently than it was captured");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

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

    /// #2257: the encoded snapshot cannot be read back as captured, so it is NOT written. Detected at
    /// write time so the failure is loud there, not a refused boot after the retained good snapshots
    /// have been replaced by files that restore nothing.
    record RoundTripFailed(Cause origin) implements SnapshotError {
        @Override
        public String message() {
            return "Snapshot would not read back as written and was NOT written: " + origin.message();
        }
    }

    record UnsupportedFormat(String version) implements SnapshotError {
        @Override
        public String message() {
            return "Unsupported snapshot format version: " + version;
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

    /// #1473: the volume-identity sentinel outside the volume says this node wrote a snapshot
    /// directory before, and the directory now in front of it is empty or carries another volume's
    /// id. An unmounted mount point looks exactly like this; a first boot never does, because it has
    /// no sentinel.
    record VolumeNotPreviouslyWritten(Path snapshotPath, Path sentinel) implements SnapshotError {
        @Override
        public String message() {
            return "Snapshot directory " + snapshotPath
                 + " is not the volume this node wrote before (sentinel " + sentinel
                 + " records a volume id the directory does not carry); refusing to start with EMPTY metadata. "
                 + "Mount the original volume, or delete the sentinel only if the volume was replaced on purpose";
        }
    }

    /// #1473: the sentinel says this node wrote a volume at `snapshotPath` before, and the disk tier for it
    /// did not come up at all (the mount point is missing or not writable). That is the unmounted volume
    /// reaching the snapshot layer as "no disk tier"; booting on empty metadata would be the defect.
    record VolumeUnavailable(Path snapshotPath, Path sentinel) implements SnapshotError {
        @Override
        public String message() {
            return "The disk tier for snapshot directory " + snapshotPath
                 + " is unavailable, but sentinel " + sentinel
                 + " records that this node wrote a volume there before; refusing to start with EMPTY metadata. "
                 + "Mount the original volume, or delete the sentinel only if the volume was retired on purpose";
        }
    }
}
