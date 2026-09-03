package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #737 fix round 3 -- pins two [BlockLifecycle] transitions that were already correct but had no test
/// pinning them: a redundant decrement at the floor must not restart the GC grace clock, and
/// resurrection must clear it so a re-referenced block drops out of the orphan-grace filter
/// immediately. Both guard the same invariant from opposite directions -- orphanedAt reflects the
/// LATEST >0-to-<=0 transition, not "the last time withRefCountDecremented ran" or "whatever it was
/// before withRefCountIncremented touched refCount at all".
class BlockLifecycleTest {

    private static final BlockId BLOCK_ID = testBlockId();

    private static BlockId testBlockId() {
        return BlockId.blockId("bl-test".getBytes(StandardCharsets.UTF_8))
                      .fold(c -> { fail("blockId failed: " + c.message()); return null; },
                            id -> id);
    }

    @Test
    void withRefCountDecremented_redundantAtFloor_doesNotRestartGraceClock() {
        var orphanedAt = 1_000L;
        var alreadyOrphaned = new BlockLifecycle(BLOCK_ID, EnumSet.noneOf(TierLevel.class), 0, 500L, 100L, 0, orphanedAt);

        var decrementedAgain = alreadyOrphaned.withRefCountDecremented();

        assertThat(decrementedAgain.refCount()).isZero();
        assertThat(decrementedAgain.orphanedAt())
                .as("a redundant decrement on an already-orphaned block must not push the grace clock"
                    + " forward to a later 'now' -- that would hand the block a fresh grace period every"
                    + " time something no-ops a decrement against it, which GC's orphan-scan cadence"
                    + " does routinely")
                .isEqualTo(orphanedAt);
    }

    @Test
    void withRefCountIncremented_resurrection_clearsOrphanedAt() {
        var orphanedAt = 1_000L;
        var orphaned = new BlockLifecycle(BLOCK_ID, EnumSet.noneOf(TierLevel.class), 0, 500L, 100L, 0, orphanedAt);

        var resurrected = orphaned.withRefCountIncremented();

        assertThat(resurrected.refCount()).isEqualTo(1);
        assertThat(resurrected.orphanedAt())
                .as("resurrecting an orphaned block must clear orphanedAt -- it is referenced again and"
                    + " must drop out of GC's orphan-grace filter immediately, not linger eligible on a"
                    + " stale orphaning timestamp from before it was re-referenced")
                .isZero();
    }
}
