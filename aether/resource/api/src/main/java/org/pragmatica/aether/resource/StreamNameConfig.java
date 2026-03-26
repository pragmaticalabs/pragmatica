package org.pragmatica.aether.resource;
/// Configuration for a stream resource, loaded from aether.toml.
///
/// Example configuration:
/// ```toml
/// [streams.order-events]
/// streamName = "order-events"
/// partitions = 4
/// ```
///
/// @param streamName The name of the stream
public record StreamNameConfig(String streamName) {}
