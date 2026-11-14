package org.pragmatica.kafka;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Result.all;


/// A record to produce. Without an explicit partition the Kafka default partitioner decides (key hash, else sticky).
public record OutboundRecord(String topic, Option<Integer> partition, Option<byte[]> key, byte[] value) {
    public static Result<OutboundRecord> outboundRecord(String topic,
                                                        Option<Integer> partition,
                                                        Option<byte[]> key,
                                                        byte[] value) {
        return all(Verify.ensure(topic, Verify.Is::present),
                   Verify.ensureOption(partition, Verify.Is::nonNegative),
                   Verify.ensure(value, Verify.Is::notNull)).map((t, p, v) -> new OutboundRecord(t, p, key, v));
    }
}
