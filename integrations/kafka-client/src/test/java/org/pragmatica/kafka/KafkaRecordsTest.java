package org.pragmatica.kafka;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class KafkaRecordsTest {
    @Test
    void partition_negativeIndex_fails() {
        assertThat(Partition.partition("t", -1).isFailure()).isTrue();
        assertThat(Partition.partition("t", 0).isSuccess()).isTrue();
    }

    @Test
    void outboundRecord_negativePartition_fails() {
        assertThat(OutboundRecord.outboundRecord("t",
                                                 Option.some(-1),
                                                 Option.none(),
                                                 new byte[]{1})
                                 .isFailure()).isTrue();
    }
}
