// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.function.Function;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Serializer;


public final class StreamPublisherFactory implements ResourceFactory<StreamPublisher, StreamConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("StreamPublisher requires ProvisioningContext with runtime extensions");

    @Override
    public Class<StreamPublisher> resourceType() {
        return StreamPublisher.class;
    }

    @Override
    public Class<StreamConfig> configType() {
        return StreamConfig.class;
    }

    @Override
    public Promise<StreamPublisher> provision(StreamConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override
    public Promise<StreamPublisher> provision(StreamConfig config, ProvisioningContext context) {
        return context.extension(StreamPartitionManager.class)
                      .flatMap(manager -> context.extension(Serializer.class)
                                                 .flatMap(serializer -> buildPublisher(manager,
                                                                                       serializer,
                                                                                       config,
                                                                                       context)))
                      .async();
    }

    @SuppressWarnings("unchecked")
    private static Result<StreamPublisher> buildPublisher(StreamPartitionManager manager,
                                                          Serializer serializer,
                                                          StreamConfig config,
                                                          ProvisioningContext context) {
        return ensureStreamExists(manager, config).map(_ -> assemblePublisher(manager, serializer, config, context));
    }

    @SuppressWarnings("unchecked")
    private static StreamPublisher assemblePublisher(StreamPartitionManager manager,
                                                     Serializer serializer,
                                                     StreamConfig config,
                                                     ProvisioningContext context) {
        var keyExtractor = extractPartitionKeyFunction(context);
        var forwardClient = context.extension(StreamForwardClient.class).option();
        var governorResolver = context.extension(GovernorResolver.class).option().map(GovernorResolver::resolver);

        return DefaultStreamPublisher.streamPublisher(manager,
                                                      serializer,
                                                      config.name(),
                                                      config.partitions(),
                                                      keyExtractor,
                                                      config.consistencyMode(),
                                                      Option.none(),
                                                      config.minSyncReplicas(),
                                                      forwardClient,
                                                      governorResolver);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Option<Function<T, Object>> extractPartitionKeyFunction(ProvisioningContext context) {
        return context.keyExtractor()
                      .map(fn -> (Function<T, Object>) input -> ((org.pragmatica.lang.Functions.Fn1) fn).apply(input));
    }

    private static Result<Unit> ensureStreamExists(StreamPartitionManager manager, StreamConfig config) {
        return StreamCreateOutcome.tolerateAlreadyExists(manager.createStream(config));
    }

    /// Owner-resolver extension consumed by the app stream publish path.
    ///
    /// `resolver` is the arg-less leader resolver (consensus leader for the STREAMING group); it is
    /// retained for the STRONG / forward-fallback path. `partitionOwnerResolver` is the #47 HRW
    /// owner-resolver keyed by `(streamName, partition)` — it returns the SAME HRW owner the
    /// `ReplicaSetController` uses for the replica set, so app-stream publishes route to that owner
    /// instead of the leader. When absent (test / minimal runtime) the publish path keeps the prior
    /// leader-routed behaviour.
    public record GovernorResolver(Fn0<Option<NodeId>> resolver,
                                   Option<Fn2<Option<NodeId>, String, Integer>> partitionOwnerResolver) {
        public GovernorResolver(Fn0<Option<NodeId>> resolver) {
            this(resolver, Option.none());
        }
    }
}
