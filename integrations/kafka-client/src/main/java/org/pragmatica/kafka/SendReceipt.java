package org.pragmatica.kafka;

/// Broker acknowledgement of one produced record, at the strength of the configured `acks`.
public record SendReceipt(Partition partition, long offset, long timestamp) {
    public static SendReceipt sendReceipt(Partition partition, long offset, long timestamp) {
        return new SendReceipt(partition, offset, timestamp);
    }
}
