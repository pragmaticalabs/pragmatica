// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.topic.TopicAddress;
import org.pragmatica.aether.slice.topic.TopicVersion;
import org.pragmatica.lang.Result;

/// Pub/sub topic configuration.
///
/// The wire/TOML shape is a single `topicName` field, kept verbatim for backward compatibility:
/// existing un-namespaced declarations (`topicName = "order-events"`) keep deserializing unchanged.
///
/// The namespaced [TopicAddress] is a derived view, not a stored field, so config deserialization
/// is unaffected. [#address] resolves the declared string to a canonical
/// `namespace:topic:version`:
///  - a value that already parses as a full `namespace:topic:version` is used verbatim;
///  - a bare topic name resolves to [TopicAddress#DEFAULT_NAMESPACE] + [TopicVersion#defaultVersion]
///    (the deploy path replaces the default namespace with the blueprint-derived one via
///    `resolveTopicName`).
///
/// [#topicName] remains the bare-name convenience accessor used for runtime pub/sub routing.
public record TopicConfig(String topicName) {
    /// Resolve the declared topic string to a canonical [TopicAddress].
    ///
    /// Back-compat: a bare name (no `:` separators) is lifted to
    /// `default:<topic>:1.0.0`; an already-namespaced `namespace:topic:version` value is parsed
    /// as-is. The deploy path overrides the placeholder namespace with the blueprint-derived one.
    public Result<TopicAddress> address() {
        return topicName != null && topicName.contains(":")
              ? TopicAddress.topicAddress(topicName)
              : TopicAddress.topicAddress(TopicAddress.DEFAULT_NAMESPACE, topicName, TopicVersion.defaultVersion());
    }
}
