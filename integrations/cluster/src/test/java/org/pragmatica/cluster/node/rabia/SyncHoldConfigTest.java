package org.pragmatica.cluster.node.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Verifies Phase 2 PR-B `SyncHoldConfig.holdMs(...)` clamps the per-snapshot hold duration
/// between `minHold` and `maxHold`, with linear interpolation by `expectedSyncBps` in between.
class SyncHoldConfigTest {
    @Test
    void holdMs_zeroBytes_collapseToMin() {
        var config = SyncHoldConfig.defaults();

        assertThat(config.holdMs(0L)).isEqualTo(config.minHold().millis());
    }

    @Test
    void holdMs_smallSnapshot_clampsToMin() {
        // 1 KB at 10 MB/s = 0.1 ms — well below min 5s — must clamp UP to min.
        var config = SyncHoldConfig.defaults();

        assertThat(config.holdMs(1_024L)).isEqualTo(config.minHold().millis());
    }

    @Test
    void holdMs_hugeSnapshot_clampsToMax() {
        // 10 GB at 10 MB/s = 1000 s — well above max 60s — must clamp DOWN to max.
        var config = SyncHoldConfig.defaults();

        assertThat(config.holdMs(10L * 1024 * 1024 * 1024)).isEqualTo(config.maxHold().millis());
    }

    @Test
    void holdMs_midSnapshot_interpolatesByBps() {
        // 100 MB at 10 MB/s = 10s — falls between min 5s and max 60s — exact value expected.
        var config = SyncHoldConfig.defaults();

        assertThat(config.holdMs(100L * 1024 * 1024)).isBetween(9_000L, 11_000L);
    }

    @Test
    void holdMs_negativeBytes_collapseToMin() {
        var config = SyncHoldConfig.defaults();

        assertThat(config.holdMs(-100L)).isEqualTo(config.minHold().millis());
    }

    @Test
    void holdMs_customConfig_respectsCustomBoundsAndBps() {
        // 50 MB at 5 MB/s = 10s; clamped to [2s, 30s].
        var config = new SyncHoldConfig(timeSpan(2).seconds(),
                                         timeSpan(30).seconds(),
                                         5_000_000.0);

        assertThat(config.holdMs(50L * 1024 * 1024)).isBetween(9_000L, 11_000L);
    }

    @Test
    void defaults_haveExpectedRC1Values() {
        var config = SyncHoldConfig.defaults();

        assertThat(config.minHold()).isEqualTo(TimeSpan.timeSpan(5).seconds());
        assertThat(config.maxHold()).isEqualTo(TimeSpan.timeSpan(60).seconds());
        assertThat(config.expectedSyncBps()).isEqualTo(10_000_000.0);
    }
}
