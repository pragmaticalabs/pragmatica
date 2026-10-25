package org.pragmatica.storage;

/// A condition of a storage instance that needs an operator (A0b): the instance is running with less than it was
/// configured for, or a protection it relies on is not working. A component reports the conditions it holds NOW
/// ([DemotionManager#conditions], [SnapshotManager#conditions]); the node turns the appearance and the disappearance
/// of each into an operator event, so a condition that persists is reported once.
///
/// `detail` is the sentence an operator reads: it names what is wrong and what to do. It is not part of the
/// condition's identity: the same `kind` on the same instance is the same condition whatever it says.
public record StorageCondition(Kind kind, String detail) {
    public enum Kind {
        /// The disk tier of an instance could not be opened at a default or derived path: the instance runs on memory
        /// plus the shared tier, so a put is acknowledged without a local durable copy. Lasts until the node restarts.
        DISK_TIER_UNAVAILABLE,
        /// The configured memory-tier size was reduced to fit a fraction of the JVM's maximum heap. Lasts until the node
        /// restarts with a larger heap or smaller configured caps.
        MEMORY_TIER_CLAMPED,
        /// A durable tier is above its high watermark and holds the only durable copy of its blocks, so nothing is demoted
        /// from it. Ends when the tier drops back under its watermark.
        DEMOTION_REFUSED,
        /// A metadata snapshot could not be written, so the references taken since the last good snapshot are not
        /// protected. Ends with the next successful snapshot.
        SNAPSHOT_WRITE_FAILING,
        /// The latest metadata snapshot was unreadable and an older retained one was restored: metadata recorded only in
        /// the unreadable file is lost. Ends with the next successful snapshot, which writes a complete current state.
        SNAPSHOT_FELL_BACK
    }

    public static StorageCondition storageCondition(Kind kind, String detail) {
        return new StorageCondition(kind, detail);
    }
}
