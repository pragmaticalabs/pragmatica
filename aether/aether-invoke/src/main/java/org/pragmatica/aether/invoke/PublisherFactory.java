// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;


public final class PublisherFactory implements ResourceFactory<Publisher, TopicConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("Publisher requires ProvisioningContext with runtime extensions");

    @Override public Class<Publisher> resourceType() {
        return Publisher.class;
    }

    @Override public Class<TopicConfig> configType() {
        return TopicConfig.class;
    }

    @Override public Promise<Publisher> provision(TopicConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override public Promise<Publisher> provision(TopicConfig config, ProvisioningContext context) {
        return context.extension(TopicSubscriptionRegistry.class).flatMap(registry -> context.extension(SliceInvoker.class)
                                                                                                       .map(invoker -> (Publisher) new TopicPublisher<>(config.topicName(),
                                                                                                                                                        registry,
                                                                                                                                                        invoker)))
                                .async();
    }
}
