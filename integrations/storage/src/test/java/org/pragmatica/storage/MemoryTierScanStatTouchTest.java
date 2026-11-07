package org.pragmatica.storage;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The A2b tier contract on [MemoryTier], whose write times come from its injected clock.
class MemoryTierScanStatTouchTest extends TierScanStatTouchContract {
    private final AtomicLong clock = new AtomicLong(5_000L);

    @Override
    protected StorageTier newTier() {
        return MemoryTier.memoryTier(1 << 20, TierLevel.MEMORY, clock::get);
    }

    @Override
    protected void age(BlockId id) {
        clock.addAndGet(60_000L);
    }

    @Test
    void stat_reportsTheClockAtTheWrite_andTouchTheClockAtTheTouch() {
        clock.set(7_000L);

        var id = store(bytes("clocked"));

        clock.set(9_000L);
        tier.touch(id).await().unwrap();

        assertThat(tier.stat(id).await().unwrap().unwrap().writtenAtMillis()).isEqualTo(9_000L);
    }
}
