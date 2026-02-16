package org.pragmatica.aether.infra.pubsub;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Error hierarchy for pub/sub operations.
public sealed interface PubSubError extends Cause {
    record TopicNotFound(String topic) implements PubSubError {
        public static Result<TopicNotFound> topicNotFound(String topic) {
            return success(new TopicNotFound(topic));
        }

        @Override
        public String message() {
            return "Topic not found: " + topic;
        }
    }

    record TopicAlreadyExists(String topic) implements PubSubError {
        public static Result<TopicAlreadyExists> topicAlreadyExists(String topic) {
            return success(new TopicAlreadyExists(topic));
        }

        @Override
        public String message() {
            return "Topic already exists: " + topic;
        }
    }

    record SubscriptionNotFound(String subscriptionId) implements PubSubError {
        public static Result<SubscriptionNotFound> subscriptionNotFound(String subscriptionId) {
            return success(new SubscriptionNotFound(subscriptionId));
        }

        @Override
        public String message() {
            return "Subscription not found: " + subscriptionId;
        }
    }

    record PublishFailed(String topic, String detail) implements PubSubError {
        public static Result<PublishFailed> publishFailed(String topic, String detail) {
            return success(new PublishFailed(topic, detail));
        }

        @Override
        public String message() {
            return "Failed to publish to topic '" + topic + "': " + detail;
        }
    }

    record unused() implements PubSubError {
        @Override
        public String message() {
            return "";
        }
    }
}
