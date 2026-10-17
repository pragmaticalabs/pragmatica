package org.pragmatica.cluster.state.kvstore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1529: a refusal burst (a deposed writer's in-flight writes after a failover) logs its first refusal per cause
/// and then at most one summary per interval, never one line per refusal.
class ThrottledWarningTest {
    private static final long INTERVAL = 30_000_000_000L;

    private final List<String> lines = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final ThrottledWarning warning = ThrottledWarning.throttledWarning(lines::add, clock::get, INTERVAL, "refusals");

    @Test
    void burstOfOneCause_logsItOnce_thenOneSummaryPerInterval() {
        for (int i = 0; i < 100; i++) {
            warning.report("pair-a", () -> "first refusal");
        }

        assertThat(lines).containsExactly("first refusal");

        clock.addAndGet(INTERVAL);
        warning.report("pair-a", () -> "first refusal");

        assertThat(lines).hasSize(2);
        assertThat(lines.getLast()).startsWith("100 more refusals");
        assertThat(warning.emitted()).isEqualTo(2L);
    }

    @Test
    void distinctCauses_areEachLoggedOnce() {
        warning.report("pair-a", () -> "a");
        warning.report("pair-b", () -> "b");
        warning.report("pair-a", () -> "a");

        assertThat(lines).containsExactly("a", "b");
    }
}
