package org.pragmatica.storage;

import org.pragmatica.lang.Result;


/// How many versions of an anchor a storage instance keeps below the one it just wrote (`[storage.<instance>]
/// anchor_retention`, at least 2). After version `v` is written, versions below `v - versions` are pruned, so the newest
/// `versions + 1` survive. The default of 8 is a guess, to be revisited after the A3-2 benchmark.
public record AnchorRetention(int versions) {
    public static final int MINIMUM = 2;
    public static final AnchorRetention DEFAULT = new AnchorRetention(8);
    /// How far past the kept versions one prune looks for older ones; a larger backlog is worked off by later prunes.
    private static final int PRUNE_BACKLOG_PER_PASS = 15;

    public static Result<AnchorRetention> anchorRetention(int versions) {
        return versions >= MINIMUM
               ? Result.success(new AnchorRetention(versions))
               : AnchorError.RetentionTooSmall.retentionTooSmall(versions).result();
    }

    /// The versions one prune examines from the newest end: the kept ones plus a pass of backlog.
    int scanLimit() {
        return versions + 1 + PRUNE_BACKLOG_PER_PASS;
    }
}
