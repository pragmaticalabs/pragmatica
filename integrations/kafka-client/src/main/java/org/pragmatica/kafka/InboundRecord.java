package org.pragmatica.kafka;

import org.pragmatica.lang.Option;

/// A record fetched from a partition.
public record InboundRecord(Partition partition, long offset, long timestamp, Option<byte[]> key, byte[] value) {
    public static InboundRecord inboundRecord(Partition partition, long offset, long timestamp, Option<byte[]> key, byte[] value) {
        return new InboundRecord(partition, offset, timestamp, key, value);
    }
}
