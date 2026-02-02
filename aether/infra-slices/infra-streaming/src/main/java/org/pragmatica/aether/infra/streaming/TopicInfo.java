package org.pragmatica.aether.infra.streaming;

import java.time.Instant;

/**
 * Information about a topic.
 *
 * @param name        Topic name
 * @param partitions  Number of partitions
 * @param createdAt   Creation timestamp
 */
public record TopicInfo(String name,
                        int partitions,
                        Instant createdAt) {
    /**
     * Creates topic info.
     */
    public static TopicInfo topicInfo(String name, int partitions, Instant createdAt) {
        return new TopicInfo(name, partitions, createdAt);
    }

    /**
     * Creates topic info with current timestamp.
     */
    public static TopicInfo topicInfo(String name, int partitions) {
        return new TopicInfo(name, partitions, Instant.now());
    }
}
