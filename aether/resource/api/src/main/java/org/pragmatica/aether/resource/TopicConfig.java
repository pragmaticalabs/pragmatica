package org.pragmatica.aether.resource;
/// Configuration for a topic publisher, loaded from aether.toml.
///
/// Example configuration:
/// ```toml
/// [messaging.orders]
/// topicName = "order-events"
/// ```
///
/// @param topicName The name of the topic to publish to
public record TopicConfig(String topicName) {}
