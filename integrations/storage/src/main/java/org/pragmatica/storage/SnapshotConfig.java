package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Configuration for metadata snapshot manager.
///
/// @param snapshotPath directory where snapshot files are stored
/// @param mutationThreshold number of mutations before triggering a snapshot. A mutation is a change to what
///        the metadata asserts (a block recorded, a reference counted, a ref moved); a READ is not one, so read
///        load never moves the snapshot cadence (C-08), and an unacknowledged claim is not captured (SB-02)
/// @param maxIntervalMillis maximum time between snapshots
/// @param retentionCount number of old snapshots to retain
/// @param nodeId the node identity for snapshot attribution
/// @param volumeSentinel #1473: a file OUTSIDE the volume that holds the id of the volume the snapshot
///        directory lives on, present only when the disk tier is armed (a degraded tier has no
///        durable volume to lose). Once the sentinel exists, an empty snapshot directory without the
///        matching `.volume-id` inside it is a different (or unmounted) volume, not a first boot.
/// @param storageId #2836: the identity of the storage this node expects to be restoring, stamped into every
///        snapshot written and compared on restore. Absent until the identity step supplies one; an absent id
///        stamps nothing and refuses nothing. Set it through [#withStorageId], which refuses an id the snapshot
///        header could not carry (see [#isWellFormedStorageId]); the snapshot parser applies the same rule.
public record SnapshotConfig(Path snapshotPath,
                             int mutationThreshold,
                             long maxIntervalMillis,
                             int retentionCount,
                             String nodeId,
                             Option<Path> volumeSentinel,
                             Option<String> storageId) {
    public static SnapshotConfig snapshotConfig(Path snapshotPath, String nodeId) {
        return new SnapshotConfig(snapshotPath, 1000, 60_000, 5, nodeId, Option.none(), Option.none());
    }

    public static SnapshotConfig snapshotConfig(Path snapshotPath,
                                                int mutationThreshold,
                                                long maxIntervalMillis,
                                                int retentionCount,
                                                String nodeId) {
        return new SnapshotConfig(snapshotPath,
                                  mutationThreshold,
                                  maxIntervalMillis,
                                  retentionCount,
                                  nodeId,
                                  Option.none(),
                                  Option.none());
    }

    public SnapshotConfig withVolumeSentinel(Option<Path> sentinel) {
        return new SnapshotConfig(snapshotPath,
                                  mutationThreshold,
                                  maxIntervalMillis,
                                  retentionCount,
                                  nodeId,
                                  sentinel,
                                  storageId);
    }

    /// The same configuration carrying `id`, validated here because this is where an id is minted: an id that
    /// is not [#isWellFormedStorageId] fails with [SnapshotError.InvalidStorageId] instead of writing a snapshot
    /// this storage could not restore. Both the sentinel and the other fields are kept.
    public Result<SnapshotConfig> withStorageId(Option<String> id) {
        return checkedStorageId(id).map(checked -> new SnapshotConfig(snapshotPath,
                                                                      mutationThreshold,
                                                                      maxIntervalMillis,
                                                                      retentionCount,
                                                                      nodeId,
                                                                      volumeSentinel,
                                                                      checked));
    }

    /// `id` itself when absent or well formed; the one rule shared by the mint ([#withStorageId]) and the parse.
    static Result<Option<String>> checkedStorageId(Option<String> id) {
        return id.fold(() -> Result.success(id),
                       text -> Result.success(id)
                                     .filter(SnapshotError.InvalidStorageId.invalidStorageId(text),
                                             _ -> isWellFormedStorageId(text)));
    }

    /// A storage id the header line carries and reads back unchanged: non-empty (an empty value means "none"), no
    /// control character (`\n` would inject a header line, `\r` would be read back), and valid UTF-8 (a lone
    /// surrogate is written as `?`).
    static boolean isWellFormedStorageId(String text) {
        return !text.isEmpty() && text.chars().noneMatch(Character::isISOControl) && roundTripsAsUtf8(text);
    }

    private static boolean roundTripsAsUtf8(String text) {
        return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(text);
    }

    /// `text` with control characters shown as a backslash, u and four hex digits, for a message an operator reads.
    static String printable(String text) {
        var sb = new StringBuilder("'");

        text.chars().forEach(c -> sb.append(Character.isISOControl(c) ? String.format("\\u%04x", c) : String.valueOf((char) c)));

        return sb.append('\'').toString();
    }
}
