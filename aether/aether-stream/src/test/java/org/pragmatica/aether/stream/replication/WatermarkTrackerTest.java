package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.WatermarkTracker.watermarkTracker;


class WatermarkTrackerTest {

    private WatermarkTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = watermarkTracker();
    }

    @Nested
    class AdvanceAndQuery {

        @Test
        void watermark_beforeAdvance_returnsNone() {
            var result = tracker.watermark("orders", 0);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void watermark_afterAdvance_returnsOffset() {
            tracker.advance("orders", 0, 42L);

            var result = tracker.watermark("orders", 0);

            assertThat(result.isEmpty()).isFalse();
            result.onPresent(offset -> assertThat(offset).isEqualTo(42L));
        }

        @Test
        void advance_higherOffset_updatesWatermark() {
            tracker.advance("orders", 0, 10L);
            tracker.advance("orders", 0, 50L);

            tracker.watermark("orders", 0)
                   .onPresent(offset -> assertThat(offset).isEqualTo(50L));
        }

        @Test
        void advance_lowerOffset_doesNotRegress() {
            tracker.advance("orders", 0, 50L);
            tracker.advance("orders", 0, 10L);

            tracker.watermark("orders", 0)
                   .onPresent(offset -> assertThat(offset).isEqualTo(50L));
        }
    }

    @Nested
    class MultipleStreamsAndPartitions {

        @Test
        void watermark_differentStreams_trackedIndependently() {
            tracker.advance("orders", 0, 100L);
            tracker.advance("events", 0, 200L);

            tracker.watermark("orders", 0)
                   .onPresent(offset -> assertThat(offset).isEqualTo(100L));
            tracker.watermark("events", 0)
                   .onPresent(offset -> assertThat(offset).isEqualTo(200L));
        }

        @Test
        void watermark_differentPartitions_trackedIndependently() {
            tracker.advance("orders", 0, 10L);
            tracker.advance("orders", 1, 20L);
            tracker.advance("orders", 2, 30L);

            tracker.watermark("orders", 0)
                   .onPresent(offset -> assertThat(offset).isEqualTo(10L));
            tracker.watermark("orders", 1)
                   .onPresent(offset -> assertThat(offset).isEqualTo(20L));
            tracker.watermark("orders", 2)
                   .onPresent(offset -> assertThat(offset).isEqualTo(30L));
        }

        @Test
        void watermark_unknownPartition_returnsNone() {
            tracker.advance("orders", 0, 10L);

            assertThat(tracker.watermark("orders", 99).isEmpty()).isTrue();
        }
    }

    @Nested
    class AllWatermarks {

        @Test
        void allWatermarks_empty_returnsEmptyMap() {
            assertThat(tracker.allWatermarks()).isEmpty();
        }

        @Test
        void allWatermarks_multipleEntries_groupsByStreamAndPartition() {
            tracker.advance("orders", 0, 10L);
            tracker.advance("orders", 1, 20L);
            tracker.advance("events", 0, 30L);

            var all = tracker.allWatermarks();

            assertThat(all).hasSize(2);
            assertThat(all.get("orders")).containsEntry(0, 10L);
            assertThat(all.get("orders")).containsEntry(1, 20L);
            assertThat(all.get("events")).containsEntry(0, 30L);
        }
    }
}
