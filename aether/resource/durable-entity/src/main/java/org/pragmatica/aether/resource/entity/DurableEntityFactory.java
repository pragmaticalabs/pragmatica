// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.dht.PartitionOwnerEpochSource;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.pragmatica.lang.Result.all;


/// [ResourceFactory] for the [DurableEntity] primitive, discovered via the resource SPI
/// (`META-INF/services/org.pragmatica.aether.resource.ResourceFactory`). Registering a
/// durable-entity resource is therefore pure SPI — no slice-processor, envelope, or framework edits.
///
/// Provisioning is synchronous and IO-free: the entity holds only in-process state and reads its
/// collaborators out of the [ProvisioningContext], so the returned [Promise] is already resolved.
///
/// ## What it builds, and why the context is mandatory (#345 I1)
/// Until I1 this factory ignored its config and unconditionally returned the NO-ARG
/// [InMemoryDurableEntity] — a bare map with no fence and no owner routing — so a five-node cluster gave
/// every node its own private copy of every key. It now builds the fenced
/// [PartitionFencedDurableEntity] from node-wide SPI extensions registered by
/// `AetherNode.registerEntityExtensionsOnSpi`, following the `StreamAccessFactory` template.
///
/// The context-free [#provision(DurableEntityConfig)] overload cannot reach those extensions, so it
/// REFUSES rather than silently rebuilding the unfenced entity — the #345 I1 owner ruling on an absent
/// fence. In a running node it is unreachable: `SliceLoadingContext`'s composite-aware facade upgrades
/// the generated factory's two-argument `provide(...)` call to the context overload whenever a
/// `ConfigurationProvider` is present, and without one the node installs a no-op resource facade and no
/// resource-backed slice loads at all.
@SuppressWarnings("rawtypes")
public final class DurableEntityFactory implements ResourceFactory<DurableEntity, DurableEntityConfig> {
    @Override
    public Class<DurableEntity> resourceType() {
        return DurableEntity.class;
    }

    @Override
    public Class<DurableEntityConfig> configType() {
        return DurableEntityConfig.class;
    }

    @Override
    public Promise<DurableEntity> provision(DurableEntityConfig config) {
        return new DurableEntityProvisioningError.FenceUnavailable(config.keyspace(),
                                                                   "a ProvisioningContext carrying the node's fence extensions").promise();
    }

    @Override
    public Promise<DurableEntity> provision(DurableEntityConfig config, ProvisioningContext context) {
        return fenceCollaborators(config, context).map(fence -> fencedEntity(config, context, fence))
                                 .async();
    }

    /// The mandatory write-fence collaborators, gathered from the context. [Result#all] accumulates, so a
    /// node missing several extensions reports all of them at once instead of one per redeploy.
    private static Result<FenceCollaborators> fenceCollaborators(DurableEntityConfig config,
                                                                 ProvisioningContext context) {
        return all(required(context, StorageEngine.class, config),
                   required(context, CommittedPartitionOwnerSource.class, config),
                   required(context, OwnershipEpochHighWater.class, config),
                   required(context, EntityKeyspaceRegistrar.class, config),
                   required(context, NodeId.class, config),
                   required(context, Serializer.class, config),
                   required(context, Deserializer.class, config)).map(FenceCollaborators::new);
    }

    /// A context extension whose absence means the entity could only be built UNFENCED, renamed from the
    /// context's generic "does not contain" cause to the domain refusal an operator can act on.
    private static <T> Result<T> required(ProvisioningContext context, Class<T> type, DurableEntityConfig config) {
        return context.extension(type)
                      .mapError(_ -> new DurableEntityProvisioningError.FenceUnavailable(config.keyspace(),
                                                                                         type.getSimpleName()));
    }

    /// The fenced entity: one [EntityPartitionArc] shared by the write fence and the linearizable read
    /// pipeline, so the two can never disagree about which ownership arc a key belongs to. The arc is
    /// per-config (it carries this keyspace's own partition count) rather than a node-wide extension.
    private static DurableEntity fencedEntity(DurableEntityConfig config,
                                              ProvisioningContext context,
                                              FenceCollaborators fence) {
        // Declare the keyspace BEFORE handing back the entity. Synchronous and IO-free (see
        // EntityKeyspaceRegistrar): it records intent, and the node's level-triggered driver commits and
        // re-asserts it. Until the leader mints ownership records from it, every write on this entity
        // refuses with the transient OwnershipNotYetCommitted rather than being admitted unfenced.
        fence.registrar().declare(config.keyspace(), config.partitionCount());

        return PartitionFencedDurableEntity.partitionFencedDurableEntity(fence.storage(),
                                                                         ownerEpochSource(fence.committedOwners(),
                                                                                          EntityPartitionArc.arcName(config.keyspace())),
                                                                         EntityPartitionArc.entityPartitionArc(config.keyspace(),
                                                                                                               config.partitionCount()),
                                                                         fence.serializer(),
                                                                         fence.deserializer(),
                                                                         fence.self(),
                                                                         fence.committedOwners(),
                                                                         Option.some(fence.epochHighWater()),
                                                                         context.extension(EntityLinearizableBarrier.class)
                                                                                .option());
    }

    /// The write-fence stamp for a partition: the committed `StreamPartitionOwnershipValue.ownerEpoch` of
    /// the keyspace's arc, read through the SAME committed-owner lookup the read path routes on — one
    /// source, so the stamped epoch and the routed owner can never come from different records. Equivalent
    /// to `KvPartitionOwnerEpochSource` over the same KV store, without needing the store itself as a
    /// second extension. [Epoch#ZERO] — the unfenced floor, never rejected and never advancing a
    /// high-water — for an arc with no committed ownership record yet.
    ///
    /// `arcName` is the NAMESPACED `entity:<keyspace>` name, matching what [EntityPartitionArc] embeds in
    /// the DHT key bytes and what the ownership writer registers — so the stamp, the gate's re-derived arc
    /// and the committed record are one coordinate.
    private static PartitionOwnerEpochSource ownerEpochSource(CommittedPartitionOwnerSource committedOwners,
                                                              String arcName) {
        return partition -> committedOwners.committedOwner(arcName, partition)
                                           .map(CommittedOwner::ownerEpoch)
                                           .or(Epoch.ZERO);
    }

    private record FenceCollaborators(StorageEngine storage,
                                      CommittedPartitionOwnerSource committedOwners,
                                      OwnershipEpochHighWater epochHighWater,
                                      EntityKeyspaceRegistrar registrar,
                                      NodeId self,
                                      Serializer serializer,
                                      Deserializer deserializer) {}
}
