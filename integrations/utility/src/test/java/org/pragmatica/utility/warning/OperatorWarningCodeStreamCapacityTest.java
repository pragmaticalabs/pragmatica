package org.pragmatica.utility.warning;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.utility.warning.OperatorWarningCode.*;

/// #2258 spec 3.2 item 8: the six codes the DHT bound and the disk-pressure episode raise, each as an operator reads it: its
/// wire string, subsystem, level, and for a recovery the code it closes.
class OperatorWarningCodeStreamCapacityTest {
    private record Expected(OperatorWarningCode code, String wire, WarningLevel level, Option<OperatorWarningCode> closes) {}

    @Test
    void theSixCodes_carryTheirWireStringSubsystemLevelAndRecoveryPartner() {
        var expected = java.util.List.of(new Expected(STREAM_SEGMENT_DHT_NEAR_FULL, "stream-segment-dht-near-full", WarningLevel.WARNING, Option.none()),
                                         new Expected(STREAM_SEGMENT_DHT_NEAR_FULL_CLEARED,
                                                      "stream-segment-dht-near-full-cleared",
                                                      WarningLevel.INFO,
                                                      Option.some(STREAM_SEGMENT_DHT_NEAR_FULL)),
                                         new Expected(STREAM_SEGMENT_DHT_FULL, "stream-segment-dht-full", WarningLevel.CRITICAL, Option.none()),
                                         new Expected(STREAM_SEGMENT_DHT_FULL_CLEARED,
                                                      "stream-segment-dht-full-cleared",
                                                      WarningLevel.INFO,
                                                      Option.some(STREAM_SEGMENT_DHT_FULL)),
                                         new Expected(STREAM_SEGMENT_DISK_PRESSURE, "stream-segment-disk-pressure", WarningLevel.WARNING, Option.none()),
                                         new Expected(STREAM_SEGMENT_DISK_PRESSURE_RELIEVED,
                                                      "stream-segment-disk-pressure-relieved",
                                                      WarningLevel.INFO,
                                                      Option.some(STREAM_SEGMENT_DISK_PRESSURE)));

        expected.forEach(e -> {
            assertThat(e.code().code()).as("wire string of %s", e.code()).isEqualTo(e.wire());
            assertThat(e.code().subsystem()).as("subsystem of %s", e.code()).isEqualTo("storage");
            assertThat(e.code().level()).as("level of %s", e.code()).isEqualTo(e.level());
            assertThat(e.code().recoveryOf()).as("partner of %s", e.code()).isEqualTo(e.closes());
        });
    }
}
