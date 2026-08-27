// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.dht.PartitionOwnerEpochSource;
import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(DurableEntityFactory.class);

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
        return new EntityProvisioningError.FenceUnavailable(config.keyspace(),
                                                            "a ProvisioningContext carrying the node's fence extensions").promise();
    }

    /// The unload half of the resource lifecycle, invoked by the provider when the keyspace's last local
    /// consumer slice stops. Runs the entity's package-private [PartitionFencedDurableEntity#unload]
    /// rather than relying on the `AutoCloseable` default — the entity deliberately is NOT
    /// `AutoCloseable`, so slice code holding it as `DurableEntity` cannot unhook a live keyspace.
    ///
    /// The catch is an adapter-boundary lift, same justification as the reconcile tick's: the provider
    /// composes this promise with `flatMap`, which does not catch a mapper throw, so an escaping
    /// exception would silently break the whole `releaseAll` chain — and a swallowed one would strand
    /// the registration with nothing anywhere saying why. Logging is the middle path: the unload keeps
    /// its failure visible without taking down the release of every other resource.
    @Override
    public Promise<Unit> close(DurableEntity resource) {
        if (resource instanceof PartitionFencedDurableEntity<?, ?, ?> fenced) {
            try {
                fenced.unload();
            } catch (RuntimeException e) {
                LOG.warn("Durable-entity unload hook failed — the keyspace registration may be stranded"
                        + " until the node restarts without the slice: {}",
                         e.toString(),
                         e);
            }
        }

        return Promise.unitPromise();
    }

    @Override
    public Promise<DurableEntity> provision(DurableEntityConfig config, ProvisioningContext context) {
        return fenceCollaborators(config, context).flatMap(fence -> fencedEntity(config, context, fence))
                                 .async();
    }

    /// The mandatory write-fence collaborators, gathered from the context. [Result#all] accumulates, so a
    /// node missing several extensions reports all of them at once instead of one per redeploy.
    private static Result<FenceCollaborators> fenceCollaborators(DurableEntityConfig config,
                                                                 ProvisioningContext context) {
        return all(required(context, EntityLogSubstrate.class, config),
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
                      .mapError(_ -> new EntityProvisioningError.FenceUnavailable(config.keyspace(),
                                                                                  type.getSimpleName()));
    }

    /// The fenced entity: one [EntityPartitionArc] shared by the write fence and the linearizable read
    /// pipeline, so the two can never disagree about which ownership arc a key belongs to. The arc is
    /// per-config (it carries this keyspace's own partition count) rather than a node-wide extension.
    ///
    /// The durable log is materialized BEFORE the entity is handed back, and a failure to materialize it
    /// REFUSES provisioning. An entity whose log could not be created has no durability at all — the same
    /// class of silent wrongness as an absent fence — so the #345 I1 ruling applies unchanged: a slice
    /// declaring a durable entity fails to start rather than starting wrong.
    ///
    /// The epoch STAMP is gone from this path. Until I3 the entity stamped each write with the partition's
    /// committed owner epoch and the storage engine's gate checked it; now the log's own append gate
    /// derives and checks that epoch itself, over the same arc, ahead of both the ring append and the WAL
    /// fsync. One fence, at the point the data actually lands.
    private static Result<DurableEntity> fencedEntity(DurableEntityConfig config,
                                                      ProvisioningContext context,
                                                      FenceCollaborators fence) {
        // Declare the keyspace BEFORE handing back the entity. Synchronous and IO-free (see
        // EntityKeyspaceRegistrar): it records intent, and the node's level-triggered driver commits and
        // re-asserts it. Until the leader mints ownership records from it, every write on this entity
        // refuses with the transient OwnershipNotYetCommitted rather than being admitted unfenced.
        fence.registrar().declare(config.keyspace(), config.partitionCount());

        return fence.substrate()
                    .ensureLog(config.keyspace(),
                               config.partitionCount(),
                               config.replicationFactor(),
                               config.minSyncReplicas())
                    .mapError(cause -> new EntityProvisioningError.LogUnavailable(config.keyspace(),
                                                                                  cause))
                    .map(_ -> buildEntity(config, context, fence));
    }

    private static DurableEntity buildEntity(DurableEntityConfig config,
                                             ProvisioningContext context,
                                             FenceCollaborators fence) {
        var entity = PartitionFencedDurableEntity.<Object, Object, Mutator<Object>> partitionFencedDurableEntity(config.keyspace(),
                                                                                                                 fence.substrate(),
                                                                                                                 EntityPartitionArc.entityPartitionArc(config.keyspace(),
                                                                                                                                                       config.partitionCount()),
                                                                                                                 fence.serializer(),
                                                                                                                 fence.deserializer(),
                                                                                                                 fence.self(),
                                                                                                                 fence.committedOwners(),
                                                                                                                 Option.some(fence.epochHighWater()),
                                                                                                                 context.extension(EntityLinearizableBarrier.class)
                                                                                                                        .option());

        registerForCheckpointing(config, context, fence, entity);

        return entity;
    }

    /// Hand the entity's fold to the node's checkpoint driver, if one is registered.
    ///
    /// OPTIONAL rather than mandatory, and the asymmetry with the fence is deliberate. An absent fence
    /// costs SAFETY, so provisioning refuses. An absent checkpoint driver costs BOUNDEDNESS: every write
    /// is still durable, fenced and replicated, and every read is still correct — the log simply never
    /// gets reclaimed and recovery replays further back. That is a real cost and it is why the node always
    /// registers one, but it is not a reason to refuse a resource that would otherwise work correctly.
    ///
    /// Unit tests provision without a driver and must keep working; the shape of the refusal has to match
    /// the size of the loss.
    private static void registerForCheckpointing(DurableEntityConfig config,
                                                 ProvisioningContext context,
                                                 FenceCollaborators fence,
                                                 DurableEntity<?, ?, ?> entity) {
        if (! (entity instanceof PartitionFencedDurableEntity<?, ?, ?> fenced)) {
            return;
        }

        var checkpointDriver = context.extension(EntityCheckpointDriver.class)
                                      .onSuccess(driver -> driver.register(config.keyspace(),
                                                                           config.partitionCount(),
                                                                           fenced.fold(),
                                                                           fence.substrate()));
        // Timers (#345 I4) are OPTIONAL on the same terms as checkpointing, and for the same reason the
        // shape of a refusal has to match the size of the loss: an absent driver costs TIMELINESS, not
        // safety. Every scheduled timer is still durable, fenced and replicated — it is in the log — so a
        // node that later runs a driver fires it. Refusing to provision would take down a keyspace whose
        // reads and writes are entirely correct.
        //
        var timerDriver = context.extension(EntityTimerDriver.class)
                                 .onSuccess(driver -> driver.register(config.keyspace(),
                                                                      fenced));

        reportDriverAsymmetry(config.keyspace(), checkpointDriver.isSuccess(), timerDriver.isSuccess());
        // Owner-forwarding (#596) is OPT-IN on both halves, and each half is independently inert:
        // without the transport a non-owner still refuses, and without the registry a forwarded command
        // finds no target. Neither absence silently degrades into applying a write on the wrong node.
        context.extension(EntityOwnerForward.class).onSuccess(transport -> fenced.withOwnerForward(transport));
        context.extension(EntityForwardRegistry.class)
               .onSuccess(registry -> registry.register(config.keyspace(),
                                                        fenced));
        fenced.withCloseHook(() -> retractOnUnload(config, context, fence));
    }

    /// The one entity-driver state worth shouting about, and the reason it keys on asymmetry rather than
    /// on absence.
    ///
    /// `AetherNode` registers both drivers unconditionally and side by side, so exactly two states are
    /// legitimate: BOTH present (a real node) and NEITHER present (a bare provisioning context — unit
    /// tests and harnesses provision without drivers on purpose, and that loss is deliberately tolerated
    /// rather than refused). **One present is neither of those**: something registered half the pair, and
    /// the missing half's work then silently never runs — no checkpoint driver means no entity log is
    /// ever reclaimed, no timer driver means every scheduled timer stays durably in the log and never
    /// fires. Either way the symptom surfaces far from its cause, as unbounded growth or as an
    /// application bug in whatever depended on the timer.
    ///
    /// Reporting mere ABSENCE at error grade was the obvious move and the wrong one: it fires on every
    /// bare-context unit test, and an error line that is expected to appear is an error line readers stop
    /// reading. Keying on the asymmetry keeps the shout for the state that is genuinely unreachable on a
    /// correctly assembled node.
    private static void reportDriverAsymmetry(String keyspace, boolean checkpointPresent, boolean timerPresent) {
        if (checkpointPresent == timerPresent) {
            return;
        }

        LOG.error("Entity keyspace '{}' provisioned with a HALF-REGISTERED entity driver pair "
                 + "(EntityCheckpointDriver {}, EntityTimerDriver {}). AetherNode registers both together "
                 + "and unconditionally, so this state is unreachable on a correctly assembled node. The "
                 + "absent half's work will silently never run on this keyspace.",
                  keyspace,
                  presence(checkpointPresent),
                  presence(timerPresent));
    }

    private static String presence(boolean present) {
        return present
               ? "present"
               : "ABSENT";
    }

    /// The unload mirror of provisioning, run through `ResourceFactory.close` when the keyspace's last
    /// local consumer slice stops. Retracting the keyspace declaration is what shrinks the hosting set
    /// the leader mints ownership over — without it, a node whose slice moved away stays a placement
    /// candidate forever and refuses every write it is handed. The registry, checkpoint and timer unhooks
    /// keep the forward path, the checkpoint tick and the timer tick from reaching into an unloaded
    /// slice's classloader.
    private static void retractOnUnload(DurableEntityConfig config,
                                        ProvisioningContext context,
                                        FenceCollaborators fence) {
        fence.registrar().retract(config.keyspace());
        context.extension(EntityForwardRegistry.class).onSuccess(registry -> registry.unregister(config.keyspace()));
        context.extension(EntityCheckpointDriver.class).onSuccess(driver -> driver.unregister(config.keyspace()));
        context.extension(EntityTimerDriver.class).onSuccess(driver -> driver.unregister(config.keyspace()));
    }

    private record FenceCollaborators(EntityLogSubstrate substrate,
                                      CommittedPartitionOwnerSource committedOwners,
                                      OwnershipEpochHighWater epochHighWater,
                                      EntityKeyspaceRegistrar registrar,
                                      NodeId self,
                                      Serializer serializer,
                                      Deserializer deserializer) {}
}
