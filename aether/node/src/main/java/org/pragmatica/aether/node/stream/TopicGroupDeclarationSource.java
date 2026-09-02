// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.function.Predicate;

import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry.TopicSubscription;
import org.pragmatica.aether.node.stream.StreamConsumerRegistry.ConsumerDeclaration;
import org.pragmatica.aether.stream.topic.DurableGroupIdentity;
import org.pragmatica.aether.stream.topic.DurableTopicNames;
import org.pragmatica.aether.stream.topic.TopicEventEnvelope;


/// The durable-topic registration entry point into [StreamConsumerManager] (durable-pubsub-spec
/// §6, wired per the option-(a) ruling on #386): topic subscriptions become consumer declarations
/// over the topic's backing stream, so durable dispatch inherits the manager's single-assignment
/// placement, failover, and forwarding wholesale instead of growing a second placement authority
/// (the #488/#535 drift shape).
///
/// A subscription qualifies exactly when its `topic:<address>` stream EXISTS in this node's view —
/// stream existence IS the durability declaration made real (streams are created eagerly at
/// durable-topic activation, and ephemeral topics never get one). An ephemeral topic's
/// subscriptions therefore never synthesize declarations, and a durable topic whose stream is not
/// yet visible joins on a later reconcile tick — the same bootstrap-window behavior declarative
/// consumers already have.
///
/// The consumer group is the version-stable [DurableGroupIdentity] (§6): during a blue-green
/// window two artifact VERSIONS of one subscriber synthesize two declarations that collapse to the
/// SAME (stream, partition, group) subscription keys — the manager's key-level dedup admits one
/// loop, and whichever version's bridge attaches processes for the group, which is exactly the
/// §6 upgrade semantics (the cursor belongs to the group, not the version).
@FunctionalInterface
public interface TopicGroupDeclarationSource {
    List<ConsumerDeclaration> declarations();

    static TopicGroupDeclarationSource none() {
        return List::of;
    }

    static TopicGroupDeclarationSource topicGroupDeclarationSource(TopicSubscriptionRegistry subscriptions,
                                                                   Predicate<String> topicStreamExists) {
        return () -> subscriptions.allSubscriptions()
                                  .stream()
                                  .filter(subscription -> topicStreamExists.test(DurableTopicNames.topicStream(subscription.routingKey())))
                                  .map(TopicGroupDeclarationSource::toDeclaration)
                                  .toList();
    }

    private static ConsumerDeclaration toDeclaration(TopicSubscription subscription) {
        return new ConsumerDeclaration(DurableTopicNames.topicStream(subscription.routingKey()),
                                       "",
                                       subscription.artifact(),
                                       subscription.methodName(),
                                       DurableGroupIdentity.groupId(subscription.artifact(), subscription.methodName()),
                                       false,
                                       TopicEventEnvelope.class.getName());
    }
}
