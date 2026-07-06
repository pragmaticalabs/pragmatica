// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.stream.StreamPublisherFactory.GovernorResolver;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.function.Function;


public final class StreamAccessFactory implements ResourceFactory<StreamAccess, StreamConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("StreamAccess requires ProvisioningContext with runtime extensions");

    @Override
    public Class<StreamAccess> resourceType() {
        return StreamAccess.class;
    }

    @Override
    public Class<StreamConfig> configType() {
        return StreamConfig.class;
    }

    @Override
    public Promise<StreamAccess> provision(StreamConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override
    public Promise<StreamAccess> provision(StreamConfig config, ProvisioningContext context) {
        return context.extension(StreamPartitionManager.class)
                      .flatMap(manager -> context.extension(Serializer.class)
                                                 .flatMap(serializer -> context.extension(Deserializer.class)
                                                                               .flatMap(deserializer -> buildAccess(manager,
                                                                                                                    serializer,
                                                                                                                    deserializer,
                                                                                                                    config,
                                                                                                                    context))))
                      .async();
    }

    @SuppressWarnings("unchecked")
    private static Result<StreamAccess> buildAccess(StreamPartitionManager manager,
                                                    Serializer serializer,
                                                    Deserializer deserializer,
                                                    StreamConfig config,
                                                    ProvisioningContext context) {
        return ensureStreamExists(manager, config).map(_ -> assembleAccess(manager,
                                                                           serializer,
                                                                           deserializer,
                                                                           config,
                                                                           context));
    }

    @SuppressWarnings("unchecked")
    private static StreamAccess assembleAccess(StreamPartitionManager manager,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               StreamConfig config,
                                               ProvisioningContext context) {
        var keyExtractor = extractPartitionKeyFunction(context);
        var self = context.extension(NodeId.class).option();
        // A4/A5: durable consumer cursors + tiered cold reads are wired fail-soft from optional runtime
        // extensions (absent in tests / minimal runtimes => the access behaves exactly as before).
        var cursorStore = context.extension(CursorStore.class).option();
        var tieredReader = context.extension(TieredStreamReader.class).option();
        // A6: when the runtime supplies a forward client + owner-resolver + self identity, wire
        // owner-routed publish (non-owner producers write-forward to the partition owner). Otherwise
        // fall back to the plain local-publish access (tests / minimal runtimes).
        return self.map(selfNodeId -> ownerRoutedAccess(manager,
                                                        serializer,
                                                        deserializer,
                                                        config,
                                                        context,
                                                        keyExtractor,
                                                        selfNodeId,
                                                        tieredReader,
                                                        cursorStore))
                   .or(() -> plainAccess(manager,
                                         serializer,
                                         deserializer,
                                         config,
                                         keyExtractor,
                                         tieredReader,
                                         cursorStore));
    }

    @SuppressWarnings("unchecked")
    private static <T> StreamAccess ownerRoutedAccess(StreamPartitionManager manager,
                                                      Serializer serializer,
                                                      Deserializer deserializer,
                                                      StreamConfig config,
                                                      ProvisioningContext context,
                                                      Option<Function<T, Object>> keyExtractor,
                                                      NodeId self,
                                                      Option<TieredStreamReader> tieredReader,
                                                      Option<CursorStore> cursorStore) {
        var forwardClient = context.extension(StreamForwardClient.class).option();
        var governor = context.extension(GovernorResolver.class).option();
        var ownerResolver = governor.map(GovernorResolver::resolver);
        // A6 read-forwarding: thread the node-level replica registry (when the runtime exposes it) so the
        // app-stream NEAREST read path can forward a NON-replica read to the partition's HRW owner instead
        // of reading its own empty local partition. Absent (minimal runtimes / tests) => NEAREST degrades
        // to a local read.
        var replicaRegistry = context.extension(ReplicaRegistry.class).option();
        // #47: bind the stream name into the partition-aware HRW owner-resolver so app-stream publishes
        // route to the same HRW owner the ReplicaSetController places the replica set on (one placement
        // authority). Absent => owner-routed access keeps the prior arg-less (leader) resolver.
        var streamName = config.name();
        Option<Function<Integer, Option<NodeId>>> partitionOwnerResolver = governor.flatMap(GovernorResolver::partitionOwnerResolver).map(resolver -> partition -> resolver.apply(streamName,
                                                                                                                                                                                  partition));
        // #345 item 1e-c: thread the three LINEARIZABLE pipeline components (committed-owner source,
        // ownership epoch high-water, no-op-round barrier) from the node runtime so a typed LINEARIZABLE
        // read runs the SAME owner-routed fence/round/catch-up pipeline as the raw StreamReadRouter path.
        // Absent (minimal runtimes / tests) => the LINEARIZABLE arm degrades to the replica-routed read.
        // The read preference stays NEAREST — the app default is unchanged; the components are inert for
        // every non-linearizable preference.
        var committedOwnerSource = context.extension(CommittedStreamOwnerSource.class).option();
        var epochHighWater = context.extension(OwnershipEpochHighWater.class).option();
        var linearizableBarrier = context.extension(LinearizableBarrier.class).option();

        return PartitionedStreamAccess.streamAccess(manager,
                                                    serializer,
                                                    deserializer,
                                                    config.name(),
                                                    config.partitions(),
                                                    keyExtractor,
                                                    forwardClient,
                                                    self,
                                                    ownerResolver,
                                                    partitionOwnerResolver,
                                                    config.minSyncReplicas(),
                                                    tieredReader,
                                                    cursorStore,
                                                    replicaRegistry,
                                                    ReadPreference.NEAREST,
                                                    committedOwnerSource,
                                                    epochHighWater,
                                                    linearizableBarrier);
    }

    @SuppressWarnings("unchecked")
    private static <T> StreamAccess plainAccess(StreamPartitionManager manager,
                                                Serializer serializer,
                                                Deserializer deserializer,
                                                StreamConfig config,
                                                Option<Function<T, Object>> keyExtractor,
                                                Option<TieredStreamReader> tieredReader,
                                                Option<CursorStore> cursorStore) {
        return PartitionedStreamAccess.streamAccess(manager,
                                                    serializer,
                                                    deserializer,
                                                    config.name(),
                                                    config.partitions(),
                                                    keyExtractor,
                                                    tieredReader,
                                                    cursorStore);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Option<Function<T, Object>> extractPartitionKeyFunction(ProvisioningContext context) {
        return context.keyExtractor()
                      .map(fn -> (Function<T, Object>) input -> ((org.pragmatica.lang.Functions.Fn1) fn).apply(input));
    }

    private static Result<Unit> ensureStreamExists(StreamPartitionManager manager, StreamConfig config) {
        return StreamCreateOutcome.tolerateAlreadyExists(manager.createStream(config));
    }
}
