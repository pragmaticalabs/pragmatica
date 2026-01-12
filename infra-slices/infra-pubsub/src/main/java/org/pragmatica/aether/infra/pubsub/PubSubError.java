package org.pragmatica.aether.infra.pubsub;

import org.pragmatica.lang.Cause;

/**
 * Error hierarchy for pub/sub operations.
 */
public sealed interface PubSubError extends Cause {
    record TopicNotFound(String topic) implements PubSubError {
        @Override
        public String message() {
            return "Topic not found: " + topic;
        }
    }

    record TopicAlreadyExists(String topic) implements PubSubError {
        @Override
        public String message() {
            return "Topic already exists: " + topic;
        }
    }

    record SubscriptionNotFound(String subscriptionId) implements PubSubError {
        @Override
        public String message() {
            return "Subscription not found: " + subscriptionId;
        }
    }

    record PublishFailed(String topic, String detail) implements PubSubError {
        @Override
        public String message() {
            return "Failed to publish to topic '" + topic + "': " + detail;
        }
    }
}
