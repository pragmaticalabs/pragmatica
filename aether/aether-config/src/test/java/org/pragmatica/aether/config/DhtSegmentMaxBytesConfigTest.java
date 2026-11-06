package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2258: `[streaming] dht_segment_max_bytes` as a value: its unset sentinel, every way of building or copying a streaming
/// config keeps it, the derived cap is the larger of the floor and a quarter of the heap, and a negative cap is refused at
/// validation.
class DhtSegmentMaxBytesConfigTest {
    private static final long MIB = 1024L * 1024;

    @Test
    void theSentinelAndTheFloor_areZeroAnd64MiB() {
        assertThat(StreamingConfig.DERIVE_DHT_SEGMENT_MAX_BYTES).isZero();
        assertThat(StreamingConfig.DHT_SEGMENT_MAX_BYTES_FLOOR).isEqualTo(64 * MIB);
    }

    @Test
    void everyFactory_leavesTheCapUnset() {
        var t = timeSpan(1).seconds();
        var mode = ReadLinearizationMode.values()[0];

        assertThat(StreamingConfig.streamingConfig().dhtSegmentMaxBytes()).isZero();
        assertThat(StreamingConfig.streamingConfig(t, t, 1L).dhtSegmentMaxBytes()).isZero();
        assertThat(StreamingConfig.streamingConfig(t, t, 1L, mode).dhtSegmentMaxBytes()).isZero();
        assertThat(StreamingConfig.streamingConfig(t, t, 1L, mode, 2).dhtSegmentMaxBytes()).isZero();
        assertThat(StreamingConfig.streamingConfig(t, t, 1L, mode, 2, 3L).dhtSegmentMaxBytes()).isZero();
    }

    @Test
    void everyCopy_keepsTheCap_andSettingItKeepsTheRest() {
        var capped = StreamingConfig.streamingConfig().withDhtSegmentMaxBytes(777L);

        assertThat(capped.dhtSegmentMaxBytes()).isEqualTo(777L);
        assertThat(capped.withSegmentDiskMaxBytes(5L).dhtSegmentMaxBytes()).isEqualTo(777L);
        assertThat(capped.withIsrLagMax(timeSpan(9).seconds()).dhtSegmentMaxBytes()).isEqualTo(777L);
        assertThat(capped.withPromotionEscapeAfter(timeSpan(9).seconds()).dhtSegmentMaxBytes()).isEqualTo(777L);
        assertThat(capped.withReadLinearization(ReadLinearizationMode.values()[0]).dhtSegmentMaxBytes()).isEqualTo(777L);

        var disk = StreamingConfig.streamingConfig().withSegmentDiskMaxBytes(5L).withIsrLagMax(timeSpan(9).seconds());
        var both = disk.withDhtSegmentMaxBytes(777L);

        assertThat(both.segmentDiskMaxBytes()).isEqualTo(5L);
        assertThat(both.isrLagMax()).isEqualTo(timeSpan(9).seconds());
        assertThat(both.promotionEscapeAfter()).isEqualTo(disk.promotionEscapeAfter());
        assertThat(both.readLinearization()).isEqualTo(disk.readLinearization());
        assertThat(both.publishForwardTimeout()).isEqualTo(disk.publishForwardTimeout());
    }

    @Test
    void theEffectiveCap_isTheConfiguredValue_orTheLargerOfTheFloorAndAQuarterOfTheHeap() {
        var unset = StreamingConfig.streamingConfig();

        assertThat(unset.effectiveDhtSegmentMaxBytes(1024 * MIB)).as("a quarter of 1 GiB").isEqualTo(256 * MIB);
        assertThat(unset.effectiveDhtSegmentMaxBytes(4096 * MIB)).isEqualTo(1024 * MIB);
        assertThat(unset.effectiveDhtSegmentMaxBytes(256 * MIB)).as("a quarter equals the floor").isEqualTo(64 * MIB);
        assertThat(unset.effectiveDhtSegmentMaxBytes(128 * MIB)).as("below the floor").isEqualTo(64 * MIB);
        assertThat(unset.effectiveDhtSegmentMaxBytes(0L)).isEqualTo(64 * MIB);
        assertThat(unset.withDhtSegmentMaxBytes(123L).effectiveDhtSegmentMaxBytes(4096 * MIB)).as("configured, whatever the heap").isEqualTo(123L);
    }

    @Test
    void validation_refusesANegativeCap_andAcceptsZeroAndPositive() {
        var base = AetherConfig.aetherConfig(Environment.DOCKER);

        ConfigValidator.validate(base.withStreaming(StreamingConfig.streamingConfig().withDhtSegmentMaxBytes(0L)))
                       .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        ConfigValidator.validate(base.withStreaming(StreamingConfig.streamingConfig().withDhtSegmentMaxBytes(1L)))
                       .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

        var refused = ConfigValidator.validate(base.withStreaming(StreamingConfig.streamingConfig().withDhtSegmentMaxBytes(-1L)));

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause.message()).contains("streaming.dht_segment_max_bytes must be >= 0").contains("Got: -1"));
    }
}
