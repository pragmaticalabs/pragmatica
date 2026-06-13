// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

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
                List.of(new SliceTopology.TopicPub("internal-events", "internal-events", "Event")),
                List.of(new SliceTopology.TopicSub("internal-events", "internal-events", "onEvent", "Event"))
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

    @Nested
    class TopicAddressValidation {
        @Test
        void validate_rejects_systemNamespaceForAppTopic() {
            var publisher = topologyWithPub("system:audit-events:1.0.0");
            var subscriber = topologyWithSub("system:audit-events:1.0.0");

            PubSubValidator.validate(List.of(publisher, subscriber))
                           .onSuccessRun(() -> fail("Expected failure for reserved system namespace"))
                           .onFailure(cause -> {
                               assertThat(cause).isInstanceOf(ExpanderError.InvalidTopicAddresses.class);
                               assertThat(cause.message()).contains("system:audit-events:1.0.0");
                               assertThat(cause.message().toLowerCase()).contains("reserved");
                           });
        }

        @Test
        void validate_rejects_systemDotPrefixForAppTopic() {
            var publisher = topologyWithPub("system.audit:events:1.0.0");
            var subscriber = topologyWithSub("system.audit:events:1.0.0");

            PubSubValidator.validate(List.of(publisher, subscriber))
                           .onSuccessRun(() -> fail("Expected failure for reserved system.* namespace"))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ExpanderError.InvalidTopicAddresses.class));
        }

        @Test
        void validate_accepts_explicitlyNamespacedAppTopic() {
            var publisher = topologyWithPub("com.example.app:order-events:2.0.0");
            var subscriber = topologyWithSub("com.example.app:order-events:2.0.0");

            PubSubValidator.validate(List.of(publisher, subscriber))
                           .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                           .onSuccess(topologies -> assertThat(topologies).hasSize(2));
        }

        @Test
        void validate_rejects_invalidBareTopicName() {
            var publisher = topologyWithPub("Order_Events");
            var subscriber = topologyWithSub("Order_Events");

            PubSubValidator.validate(List.of(publisher, subscriber))
                           .onSuccessRun(() -> fail("Expected failure for invalid bare topic name"))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ExpanderError.InvalidTopicAddresses.class));
        }
    }

    private static SliceTopology topologyWithPub(String config) {
        return new SliceTopology(
            "pub-slice", "org.example:pub-slice:1.0.0",
            List.of(), List.of(), List.of(),
            List.of(new SliceTopology.TopicPub(config, config, "Event")),
            List.of()
        );
    }

    private static SliceTopology topologyWithSub(String config) {
        return new SliceTopology(
            "sub-slice", "org.example:sub-slice:1.0.0",
            List.of(), List.of(), List.of(),
            List.of(),
            List.of(new SliceTopology.TopicSub(config, config, "onEvent", "Event"))
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
