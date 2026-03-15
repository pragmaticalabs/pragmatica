package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.topology.SliceTopology;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class PubSubValidatorTest {

    @Nested
    class SuccessCases {
        @Test
        void validate_succeeds_withMatchedPubSub() {
            var publisher = topologyWithPub("order-events");
            var subscriber = topologyWithSub("order-events");

            PubSubValidator.validate(List.of(publisher, subscriber))
                           .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                           .onSuccess(topologies -> assertThat(topologies).hasSize(2));
        }

        @Test
        void validate_succeeds_withNoPublishers() {
            var subscriber = topologyWithSub("order-events");
            var plain = topologyWithNoPubSub();

            PubSubValidator.validate(List.of(subscriber, plain))
                           .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                           .onSuccess(topologies -> assertThat(topologies).hasSize(2));
        }

        @Test
        void validate_succeeds_withEmptyTopologies() {
            PubSubValidator.validate(List.of())
                           .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                           .onSuccess(topologies -> assertThat(topologies).isEmpty());
        }

        @Test
        void validate_succeeds_withMultipleSubscribersForOneTopic() {
            var publisher = topologyWithPub("order-events");
            var sub1 = topologyWithSub("order-events");
            var sub2 = topologyWithSub("order-events");

            PubSubValidator.validate(List.of(publisher, sub1, sub2))
                           .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                           .onSuccess(topologies -> assertThat(topologies).hasSize(3));
        }

        @Test
        void validate_succeeds_withPubAndSubInSameSlice() {
            var topology = new SliceTopology(
                "self-loop-slice", "org.example:self-loop:1.0.0",
                List.of(), List.of(), List.of(),
                List.of(new SliceTopology.TopicPub("internal-events", "Event")),
                List.of(new SliceTopology.TopicSub("internal-events", "onEvent", "Event"))
            );

            PubSubValidator.validate(List.of(topology))
                           .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                           .onSuccess(topologies -> assertThat(topologies).hasSize(1));
        }
    }

    @Nested
    class FailureCases {
        @Test
        void validate_fails_withOrphanPublisher() {
            var publisher = topologyWithPub("order-events");
            var unrelatedSub = topologyWithSub("payment-events");

            PubSubValidator.validate(List.of(publisher, unrelatedSub))
                           .onSuccessRun(() -> fail("Expected failure"))
                           .onFailure(cause -> assertContainsOrphanTopics(cause.message(), "order-events"));
        }

        @Test
        void validate_fails_withMultipleOrphanPublishers() {
            var pub1 = topologyWithPub("order-events");
            var pub2 = topologyWithPub("payment-events");

            PubSubValidator.validate(List.of(pub1, pub2))
                           .onSuccessRun(() -> fail("Expected failure"))
                           .onFailure(cause -> assertContainsOrphanTopics(cause.message(), "order-events", "payment-events"));
        }

        @Test
        void validate_fails_withPartialMatch() {
            var pub1 = topologyWithPub("order-events");
            var pub2 = topologyWithPub("payment-events");
            var sub = topologyWithSub("order-events");

            PubSubValidator.validate(List.of(pub1, pub2, sub))
                           .onSuccessRun(() -> fail("Expected failure"))
                           .onFailure(cause -> assertContainsOnlyOrphanTopic(cause.message(), "payment-events", "order-events"));
        }
    }

    private static void assertContainsOrphanTopics(String message, String... expectedTopics) {
        assertThat(message).contains("no subscribers");
        for (var topic : expectedTopics) {
            assertThat(message).contains(topic);
        }
    }

    private static void assertContainsOnlyOrphanTopic(String message, String expectedOrphan, String expectedAbsent) {
        assertThat(message).contains(expectedOrphan);
        assertThat(message).doesNotContain(expectedAbsent);
    }

    private static SliceTopology topologyWithPub(String config) {
        return new SliceTopology(
            "pub-slice", "org.example:pub-slice:1.0.0",
            List.of(), List.of(), List.of(),
            List.of(new SliceTopology.TopicPub(config, "Event")),
            List.of()
        );
    }

    private static SliceTopology topologyWithSub(String config) {
        return new SliceTopology(
            "sub-slice", "org.example:sub-slice:1.0.0",
            List.of(), List.of(), List.of(),
            List.of(),
            List.of(new SliceTopology.TopicSub(config, "onEvent", "Event"))
        );
    }

    private static SliceTopology topologyWithNoPubSub() {
        return new SliceTopology(
            "plain-slice", "org.example:plain-slice:1.0.0",
            List.of(), List.of(), List.of(),
            List.of(), List.of()
        );
    }
}
