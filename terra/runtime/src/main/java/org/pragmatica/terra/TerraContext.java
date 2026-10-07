// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Construction-only view. Every resource acquisition belongs to exactly one application slice.
public final class TerraContext {
    private final Map<Class<?>, Object> slices;
    private final ConfigurationProvider configuration;
    private final TerraTopics topics;
    private final Scope scope;

    TerraContext(String artifact,
                 Map<Class<?>, Object> slices,
                 ConfigurationProvider configuration,
                 ResourceProviderFacade resources,
                 TerraTopics topics) {
        this.slices = slices;
        this.configuration = configuration;
        this.topics = topics;
        this.scope = new Scope(artifact, resources);
    }

    public <T> Promise<T> slice(Class<T> type) {
        return org.pragmatica.lang.Option.option(slices.get(type))
                                         .map(type::cast)
                                         .toResult(new TerraError.InvalidGraph("Dependency not constructed: " + type.getName()))
                                         .async();
    }

    public ResourceProviderFacade resources() {
        return scope;
    }

    public ConfigFacade config() {
        return ConfigFacade.configFacade(scope.artifact, configuration);
    }

    public <T> Promise<Publisher<T>> publisher(Topic<T> topic) {
        return topicName(topic.name()).map(name -> (Publisher<T>) message -> topics.publish(name, message))
                        .async();
    }

    public <T> Result<Unit> subscribe(String section, Class<T> type, Fn1<Promise<Unit>, T> handler) {
        return topicName(section).flatMap(name -> topics.subscribe(name, type, handler));
    }

    public <T> Result<Unit> subscribe(Topic<T> topic, Fn1<Promise<Unit>, T> handler) {
        return subscribe(topic.name(),
                         topic.payloadType().rawType(),
                         value -> handler.apply((T) value));
    }

    private Result<String> topicName(String section) {
        var durability = configuration.getString(section + ".durability").or("ephemeral");

        if (!"ephemeral".equalsIgnoreCase(durability)) {
            return new TerraError.UnsupportedCapability("Terra supports ephemeral topics only: " + section).result();
        }

        var name = configuration.getString(section + ".topic_name").or(section);

        return name.isBlank()
               ? new TerraError.InvalidBlueprint("Topic name cannot be blank: " + section).result()
               : Result.success(name);
    }

    Promise<Unit> close() {
        return scope.close();
    }

    private final class Scope implements ResourceProviderFacade {
        private final String artifact;
        private final ResourceProviderFacade delegate;
        private final List<Promise<Unit>> acquisitions = new ArrayList<>();
        private final Promise<Unit> released = Promise.promise();
        private boolean sealed;

        private Scope(String artifact, ResourceProviderFacade delegate) {
            this.artifact = artifact;
            this.delegate = delegate;
        }

        @Override
        public <T> Promise<T> provide(Class<T> type, String section) {
            return provide(type, section, ProvisioningContext.provisioningContext());
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) {
            if (sealed) {
                return new TerraError.NotRunning("Resource scope closed: " + artifact).promise();
            }

            if (type == Publisher.class) {
                return topicName(section).map(name -> (T)(Publisher<Object>) event -> topics.publish(name, event))
                                .async();
            }

            var enriched = context.withExtension(String.class, artifact)
                                  .withExtension(ConfigurationProvider.class, configuration);
            Promise<T> acquisition = Result.lift(Causes::fromThrowable,
                                                 () -> delegate.provide(type, section, enriched))
                                           .fold(Promise::failure, promise -> promise);

            acquisitions.add(acquisition.mapToUnit());

            return acquisition;
        }

        @Override
        public Promise<Unit> releaseAll(String ignored) {
            return close();
        }

        private Promise<Unit> release() {
            return Result.lift(Causes::fromThrowable,
                               () -> delegate.releaseAll(artifact))
                         .fold(Promise::failure, promise -> promise);
        }

        private synchronized Promise<Unit> close() {
            if (!sealed) {
                sealed = true;
                Promise.allOf(List.copyOf(acquisitions)).flatMap(_ -> release()).withResult(released::resolve);
            }

            return released;
        }
    }
}
