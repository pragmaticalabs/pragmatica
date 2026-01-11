package org.pragmatica.aether.infra.streaming;

import org.pragmatica.lang.Option;

import java.time.Instant;

/**
 * A message in the streaming system.
 *
 * @param topic     Topic the message belongs to
 * @param partition Partition number within the topic
 * @param offset    Offset within the partition
 * @param key       Optional message key (used for partition routing)
 * @param value     Message payload
 * @param timestamp Message timestamp
 * @param headers   Optional message headers
 */
public record StreamMessage(String topic,
                            int partition,
                            long offset,
                            Option<String> key,
                            byte[] value,
                            Instant timestamp,
                            Option<java.util.Map<String, String>> headers) {
    /**
     * Creates a message with all fields.
     */
    public static StreamMessage streamMessage(String topic,
                                              int partition,
                                              long offset,
                                              Option<String> key,
                                              byte[] value,
                                              Instant timestamp,
                                              Option<java.util.Map<String, String>> headers) {
        return new StreamMessage(topic, partition, offset, key, value, timestamp, headers);
    }

    /**
     * Creates a message with minimal fields.
     */
    public static StreamMessage streamMessage(String topic,
                                              int partition,
                                              long offset,
                                              byte[] value) {
        return new StreamMessage(topic, partition, offset, Option.none(), value, Instant.now(), Option.none());
    }

    /**
     * Creates a message with key.
     */
    public static StreamMessage streamMessage(String topic,
                                              int partition,
                                              long offset,
                                              String key,
                                              byte[] value) {
        return new StreamMessage(topic, partition, offset, Option.option(key), value, Instant.now(), Option.none());
    }

    /**
     * Returns the value as a UTF-8 string.
     */
    public String valueAsString() {
        return new String(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
