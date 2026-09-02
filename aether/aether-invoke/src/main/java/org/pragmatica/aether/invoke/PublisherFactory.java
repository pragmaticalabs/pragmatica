// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.blueprint.TopicAddressResolver;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.topic.DurableTopicSubstrate;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;


public final class PublisherFactory implements ResourceFactory<Publisher, TopicConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("Publisher requires ProvisioningContext with runtime extensions");

    @Override
    public Class<Publisher> resourceType() {
        return Publisher.class;
    }

    @Override
    public Class<TopicConfig> configType() {
        return TopicConfig.class;
    }

    @Override
    public Promise<Publisher> provision(TopicConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override
    public Promise<Publisher> provision(TopicConfig config, ProvisioningContext context) {
        return config.durableSpec()
                     .fold(cause -> cause.promise(),
                           spec -> provisionForTier(config, spec, context));
    }

    /// The D1 tier switch (durable-pubsub-spec §5, ratified on #386): the declared durability class
    /// selects the publisher. EPHEMERAL keeps today's RPC fan-out byte-for-byte; DURABLE provisions
    /// the stream-backed publisher — topic + DLQ streams activated eagerly in the same step, each
    /// `publish` resolving at the replication floor, not at subscriber processing.
    private Promise<Publisher> provisionForTier(TopicConfig config,
                                                Option<DurableTopicSpec> durableSpec,
                                                ProvisioningContext context) {
        return durableSpec.fold(() -> provisionEphemeral(config, context),
                                spec -> provisionDurable(config, spec, context));
    }

    private Promise<Publisher> provisionEphemeral(TopicConfig config, ProvisioningContext context) {
        var topicAddress = resolveTopicAddress(config, context);

        return context.extension(TopicSubscriptionRegistry.class)
                      .flatMap(registry -> context.extension(SliceInvoker.class)
                                                  .map(invoker -> (Publisher) new TopicPublisher<>(topicAddress,
                                                                                                   registry,
                                                                                                   invoker)))
                      .async();
    }

    /// The durable stream name derives from the SAME resolved address the ephemeral path routes on
    /// (and the deployment FSM registers subscriptions under), so publisher and subscriber sides of
    /// a durable topic agree on the backing stream by construction — the RC2 #274 rule extended to
    /// the stream layer.
    private Promise<Publisher> provisionDurable(TopicConfig config,
                                                DurableTopicSpec spec,
                                                ProvisioningContext context) {
        var topicAddress = resolveTopicAddress(config, context);

        return DurableTopicSubstrate.durablePublisher(topicAddress, spec, context)
                                    .map(publisher -> (Publisher) publisher)
                                    .async();
    }

    /// Resolve the publisher's full routing address (`namespace:name:version`) using the SAME rule
    /// the deployment FSM applies to subscriptions, so a co-deployed pub/sub pair always agrees on
    /// the namespace (RC2 #274). The owning slice's [Artifact] is read from the provisioning
    /// context's slice-id extension (set by `SliceLoadingContext`); a bare topic name derives its
    /// namespace from that artifact's blueprint coordinates. When the slice-id is absent or
    /// unparseable, falls back to the config's own default-namespace resolution so the address is
    /// always deterministic.
    private static String resolveTopicAddress(TopicConfig config, ProvisioningContext context) {
        return context.extension(String.class)
                      .flatMap(Artifact::artifact)
                      .flatMap(artifact -> TopicAddressResolver.resolve(artifact,
                                                                        config.topicName()))
                      .orElse(config::address)
                      .map(ResourceAddress::asString)
                      .or(config.topicName());
    }
}
