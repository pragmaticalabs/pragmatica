package org.pragmatica.aether.infra.streaming;

import java.util.Map;
import java.util.Set;

/**
 * Information about a consumer group.
 *
 * @param groupId          Consumer group identifier
 * @param subscribedTopics Topics this group is subscribed to
 * @param offsets          Current offsets per topic-partition
 */
public record ConsumerGroupInfo(String groupId,
                                Set<String> subscribedTopics,
                                Map<String, Map<Integer, Long>> offsets) {
    /**
     * Creates consumer group info.
     */
    public static ConsumerGroupInfo consumerGroupInfo(String groupId,
                                                      Set<String> subscribedTopics,
                                                      Map<String, Map<Integer, Long>> offsets) {
        return new ConsumerGroupInfo(groupId, subscribedTopics, offsets);
    }
}
