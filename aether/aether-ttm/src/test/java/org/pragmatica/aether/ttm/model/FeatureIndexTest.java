package org.pragmatica.aether.ttm.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureIndexTest {

    @Test
    void featureCount_matchesNumberOfFeatures() {
        // All features: CPU_USAGE, HEAP_USAGE, EVENT_LOOP_LAG_MS, LATENCY_MS, INVOCATIONS,
        // GC_PAUSE_MS, LATENCY_P50, LATENCY_P95, LATENCY_P99, ERROR_RATE, EVENT_COUNT
        assertThat(FeatureIndex.FEATURE_COUNT).isEqualTo(11);
    }

    @Test
    void cpuUsage_hasCorrectIndex() {
        assertThat(FeatureIndex.CPU_USAGE).isEqualTo(0);
    }

    @Test
    void heapUsage_hasCorrectIndex() {
        assertThat(FeatureIndex.HEAP_USAGE).isEqualTo(1);
    }

    @Test
    void eventLoopLagMs_hasCorrectIndex() {
        assertThat(FeatureIndex.EVENT_LOOP_LAG_MS).isEqualTo(2);
    }

    @Test
    void latencyMs_hasCorrectIndex() {
        assertThat(FeatureIndex.LATENCY_MS).isEqualTo(3);
    }

    @Test
    void invocations_hasCorrectIndex() {
        assertThat(FeatureIndex.INVOCATIONS).isEqualTo(4);
    }

    @Test
    void gcPauseMs_hasCorrectIndex() {
        assertThat(FeatureIndex.GC_PAUSE_MS).isEqualTo(5);
    }

    @Test
    void latencyP50_hasCorrectIndex() {
        assertThat(FeatureIndex.LATENCY_P50).isEqualTo(6);
    }

    @Test
    void latencyP95_hasCorrectIndex() {
        assertThat(FeatureIndex.LATENCY_P95).isEqualTo(7);
    }

    @Test
    void latencyP99_hasCorrectIndex() {
        assertThat(FeatureIndex.LATENCY_P99).isEqualTo(8);
    }

    @Test
    void errorRate_hasCorrectIndex() {
        assertThat(FeatureIndex.ERROR_RATE).isEqualTo(9);
    }

    @Test
    void eventCount_hasCorrectIndex() {
        assertThat(FeatureIndex.EVENT_COUNT).isEqualTo(10);
    }

    @Test
    void featureNames_matchesFeatureCount() {
        assertThat(FeatureIndex.featureNames().length).isEqualTo(FeatureIndex.FEATURE_COUNT);
    }

    @Test
    void featureNames_haveCorrectOrder() {
        var names = FeatureIndex.featureNames();
        assertThat(names[FeatureIndex.CPU_USAGE]).isEqualTo("cpu_usage");
        assertThat(names[FeatureIndex.HEAP_USAGE]).isEqualTo("heap_usage");
        assertThat(names[FeatureIndex.LATENCY_MS]).isEqualTo("latency_ms");
        assertThat(names[FeatureIndex.ERROR_RATE]).isEqualTo("error_rate");
    }
}
