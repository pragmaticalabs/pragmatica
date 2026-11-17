package org.pragmatica.kafka;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Result.all;


/// A topic partition. The bridge's own type: no `kafka-clients` class appears in the public API.
public record Partition(String topic, int partition) {
    public static Result<Partition> partition(String topic, int partition) {
        return all(Verify.ensure(topic, Verify.Is::present), Verify.ensure(partition, Verify.Is::nonNegative)).map(Partition::new);
    }
}
