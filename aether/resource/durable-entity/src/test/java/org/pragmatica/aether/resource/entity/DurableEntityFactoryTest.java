// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.PartitionOwnerEpochGate;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies the durable-entity resource is registered purely through the SPI
/// (`META-INF/services/org.pragmatica.aether.resource.ResourceFactory`), that provisioning constructs a
/// working FENCED entity from the node's [ProvisioningContext] extensions without external IO, and that
/// a missing fence collaborator is REFUSED rather than silently downgraded (#345 I1).
///
/// The refusal tests are the load-bearing ones. Before I1 this factory ignored both its config and its
/// context and always returned an unfenced in-process map, so a five-node cluster gave every node its own
/// private copy of every key — and nothing anywhere could tell. A silent fallback would let a future
/// refactor that dropped one `registerExtension` call reintroduce that invisibly.
class DurableEntityFactoryTest {
    private static final TimeSpan AWAIT = timeSpan(5).seconds();
    private static final NodeId SELF = new NodeId("self-node");
    private static final String KEYSPACE = "orders";

    @Test
    void serviceLoader_discoversDurableEntityFactory() {
        var discovered = ServiceLoader.load(ResourceFactory.class)
                                      .stream()
                                      .map(ServiceLoader.Provider::get)
                                      .anyMatch(factory -> factory instanceof DurableEntityFactory);

        assertThat(discovered)
            .as("DurableEntityFactory must be discoverable via the ResourceFactory SPI")
            .isTrue();
    }

    @Test
    void factory_reportsResourceAndConfigTypes() {
        var factory = new DurableEntityFactory();

        assertThat(factory.resourceType()).isEqualTo(DurableEntity.class);
        assertThat(factory.configType()).isEqualTo(DurableEntityConfig.class);
    }

    @Nested
    class HappyPath {
        @Test
        void provision_returnsResolvedWorkingEntity_withFenceExtensions() {
            config().onFailure(DurableEntityFactoryTest::failCause)
                    .onSuccess(config -> assertProvisions(config,
                                                          fencedContext().withExtension(EntityLinearizableBarrier.class,
                                                                                        noOpBarrier())));
        }

        /// The barrier is optional: an entity provisions without one and serves BOUNDED_STALE reads. The
        /// per-read refusal of LINEARIZABLE is [LinearizableEntityServe]'s job, proven in
        /// [EntityLinearizableReadTest].
        @Test
        void provision_succeeds_withoutBarrierExtension() {
            config().onFailure(DurableEntityFactoryTest::failCause)
                    .onSuccess(config -> assertProvisions(config, fencedContext()));
        }
    }

    @Nested
    class FenceRefusals {
        /// The context-free overload cannot reach the node's extensions, so it can only build an unfenced
        /// entity — which is exactly what it did before I1.
        @Test
        void provision_refusesWithFenceUnavailable_withoutProvisioningContext() {
            config().onFailure(DurableEntityFactoryTest::failCause)
                    .onSuccess(config -> new DurableEntityFactory().provision(config)
                                                                   .await(AWAIT)
                                                                   .onSuccess(DurableEntityFactoryTest::failUnfenced)
                                                                   .onFailure(DurableEntityFactoryTest::assertFenceUnavailable));
        }

        @Test
        void provision_refusesWithFenceUnavailable_whenLogSubstrateMissing() {
            assertRefusesWithout(EntityLogSubstrate.class);
        }

        @Test
        void provision_refusesWithFenceUnavailable_whenCommittedOwnerSourceMissing() {
            assertRefusesWithout(CommittedPartitionOwnerSource.class);
        }

        @Test
        void provision_refusesWithFenceUnavailable_whenEpochHighWaterMissing() {
            assertRefusesWithout(OwnershipEpochHighWater.class);
        }

        @Test
        void provision_refusesWithFenceUnavailable_whenNodeIdMissing() {
            assertRefusesWithout(NodeId.class);
        }

        /// Without the registrar the keyspace never reaches the leader's ownership reconcile, so every
        /// write would refuse forever with a cause that says "transient" — a permanent outage wearing a
        /// retry message. Refusing at deploy is the honest failure.
        @Test
        void provision_refusesWithFenceUnavailable_whenKeyspaceRegistrarMissing() {
            assertRefusesWithout(EntityKeyspaceRegistrar.class);
        }

        /// The refusal names the missing collaborator, so an operator reading the `DEPLOYMENT_FAILED`
        /// event knows which `registerExtension` call is absent rather than only that "something" is.
        @Test
        void provision_namesTheMissingCollaborator_inTheRefusal() {
            config().onFailure(DurableEntityFactoryTest::failCause)
                    .onSuccess(config -> new DurableEntityFactory().provision(config, contextWithout(EntityLogSubstrate.class))
                                                                   .await(AWAIT)
                                                                   .onSuccess(DurableEntityFactoryTest::failUnfenced)
                                                                   .onFailure(cause -> assertThat(cause.message()).contains(EntityLogSubstrate.class.getSimpleName())
                                                                                                                  .contains(KEYSPACE)));
        }
    }

    /// The unload half of provisioning: `ResourceFactory.close` on a keyspace whose last local consumer
    /// slice stopped. Every collaborator the factory hooked at provision time has to be released, and the
    /// three losses are different sizes — a stranded REGISTRATION keeps this node a placement candidate
    /// for a keyspace it can no longer serve (the 02w failure mode, permanently refused writes); a
    /// stranded FORWARD target lets an arriving command reach an unloaded slice's classloader instead of
    /// an honest refusal; a stranded CHECKPOINT registration keeps the tick folding through a dead fold.
    @Nested
    class Unload {
        @Test
        void close_retractsTheKeyspace_andUnhooksTheForwardRegistryAndCheckpointDriver() {
            unloadFixture().onFailure(DurableEntityFactoryTest::failCause)
                           .onSuccess(DurableEntityFactoryTest::assertUnloadReleasesEverything);
        }

        /// The provider guards single-close per cached resource, but the hook must not depend on that:
        /// [PartitionFencedDurableEntity#unload] swaps the hook out atomically so a second close is inert.
        @Test
        void close_twice_retractsExactlyOnce() {
            unloadFixture().onFailure(DurableEntityFactoryTest::failCause)
                           .onSuccess(DurableEntityFactoryTest::assertSecondCloseIsInert);
        }
    }

    // ---- unload helpers ------------------------------------------------------------------------

    private record UnloadFixture(DurableEntityConfig config,
                                 ProvisioningContext context,
                                 RecordingRegistrar registrar,
                                 RecordingForwardRegistry registry,
                                 EntityCheckpointDriver driver,
                                 EntityTimerDriver timerDriver) {}

    /// A fully-wired provisioning context — the shape `AetherNode.registerEntityExtensionsOnSpi`
    /// produces — with the three unload collaborators reachable so the test can watch them.
    private static Result<UnloadFixture> unloadFixture() {
        var registrar = new RecordingRegistrar();
        var registry = new RecordingForwardRegistry();
        var driver = EntityCheckpointDriver.entityCheckpointDriver();
        // Both entity drivers, because `registerEntityExtensionsOnSpi` registers both together and
        // unconditionally: a fixture carrying only one is not the shape a node produces, and the factory
        // reports that asymmetry as an assembly defect.
        var timerDriver = EntityTimerDriver.entityTimerDriver();
        var context = fencedContext(registrar).withExtension(EntityForwardRegistry.class, registry)
                                              .withExtension(EntityCheckpointDriver.class, driver)
                                              .withExtension(EntityTimerDriver.class, timerDriver);

        return config().map(config -> new UnloadFixture(config,
                                                        context,
                                                        registrar,
                                                        registry,
                                                        driver,
                                                        timerDriver));
    }

    private static void assertUnloadReleasesEverything(UnloadFixture fixture) {
        var entity = provisioned(fixture);

        assertThat(fixture.registrar().declared)
            .as("provisioning must declare the keyspace — else the retract below has nothing to undo")
            .containsExactly(KEYSPACE);
        assertThat(fixture.registry().registered)
            .as("provisioning must register the forward target — else the unregister below is vacuous")
            .containsExactly(KEYSPACE);
        assertThat(driverKeyspaces(fixture))
            .as("provisioning must register for checkpointing — else the empty snapshot below is vacuous")
            .containsExactly(KEYSPACE);

        closeOnce(entity);

        assertThat(fixture.registrar().retracted)
            .as("retracting is what SHRINKS the hosting set the leader mints ownership over")
            .containsExactly(KEYSPACE);
        assertThat(fixture.registry().unregistered).containsExactly(KEYSPACE);
        assertThat(driverKeyspaces(fixture))
            .as("the checkpoint tick must stop folding through an unloaded entity")
            .isEmpty();
    }

    private static void assertSecondCloseIsInert(UnloadFixture fixture) {
        var entity = provisioned(fixture);

        closeOnce(entity);
        closeOnce(entity);

        assertThat(fixture.registrar().retracted)
            .as("the close hook runs exactly once, however many times close() is called")
            .containsExactly(KEYSPACE);
        assertThat(fixture.registry().unregistered).containsExactly(KEYSPACE);
    }

    private static List<String> driverKeyspaces(UnloadFixture fixture) {
        return fixture.driver()
                      .snapshot()
                      .keyspaces()
                      .stream()
                      .map(EntityCheckpointDriver.KeyspaceCheckpoints::keyspace)
                      .toList();
    }

    private static DurableEntity<?, ?, ?> provisioned(UnloadFixture fixture) {
        return new DurableEntityFactory().provision(fixture.config(), fixture.context())
                                         .await(AWAIT)
                                         .fold(cause -> fail(cause.message()), entity -> entity);
    }

    /// Unload through the SPI path a resource provider takes: the factory's `close` override runs the
    /// entity's package-private unload. Deliberately NOT `AutoCloseable` — a slice holding the entity
    /// must not be able to unhook a live keyspace — so the override is the ONLY route to the hook.
    private static void closeOnce(DurableEntity<?, ?, ?> entity) {
        new DurableEntityFactory().close(entity)
                                  .await(AWAIT)
                                  .onFailure(DurableEntityFactoryTest::failCause);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static void assertRefusesWithout(Class<?> omitted) {
        config().onFailure(DurableEntityFactoryTest::failCause)
                .onSuccess(config -> new DurableEntityFactory().provision(config, contextWithout(omitted))
                                                               .await(AWAIT)
                                                               .onSuccess(DurableEntityFactoryTest::failUnfenced)
                                                               .onFailure(DurableEntityFactoryTest::assertFenceUnavailable));
    }

    private static void assertProvisions(DurableEntityConfig config, ProvisioningContext context) {
        var promise = new DurableEntityFactory().provision(config, context);

        assertThat(promise.isResolved())
            .as("provision() must return an already-resolved Promise — no async IO permitted")
            .isTrue();

        promise.await(AWAIT)
               .onFailure(DurableEntityFactoryTest::failCause)
               .onSuccess(DurableEntityFactoryTest::assertWorks);
    }

    @SuppressWarnings("unchecked")
    private static void assertWorks(DurableEntity entity) {
        DurableEntity<String, Integer, IntOp> typed = entity;

        typed.create("k", 1)
             .await(AWAIT)
             .onFailure(DurableEntityFactoryTest::failCause)
             .onSuccess(state -> assertThat(state).isEqualTo(1));
    }

    private static Result<DurableEntityConfig> config() {
        return DurableEntityConfig.durableEntityConfig(KEYSPACE);
    }

    /// The node's registrar is level-triggered and IO-free at this seam, so a test one that simply
    /// records the declaration is faithful — the committing half lives in `EntityOwnershipReconciler`.
    /// BOTH directions are recorded: the retract is what the unload tests are watching for.
    private static EntityKeyspaceRegistrar recordingRegistrar() {
        return new RecordingRegistrar();
    }

    private static final class RecordingRegistrar implements EntityKeyspaceRegistrar {
        private final List<String> declared = new ArrayList<>();
        private final List<String> retracted = new ArrayList<>();

        @Override
        public Unit declare(String keyspace, int partitionCount) {
            declared.add(keyspace);

            return Unit.unit();
        }

        @Override
        public Unit retract(String keyspace) {
            retracted.add(keyspace);

            return Unit.unit();
        }
    }

    /// The node's forward registry, reduced to what the unload test needs to see. `void` on both halves
    /// is the interface's own contract — a registration sink has no outcome to fold.
    private static final class RecordingForwardRegistry implements EntityForwardRegistry {
        private final List<String> registered = new ArrayList<>();
        private final List<String> unregistered = new ArrayList<>();

        @Override
        @Contract
        public void register(String keyspace, ForwardTarget target) {
            registered.add(keyspace);
        }

        @Override
        @Contract
        public void unregister(String keyspace) {
            unregistered.add(keyspace);
        }
    }

    private static EntityLinearizableBarrier noOpBarrier() {
        return (_, _) -> Promise.success(Unit.unit());
    }

    /// A context carrying every mandatory fence collaborator — the shape
    /// `AetherNode.registerEntityExtensionsOnSpi` produces. The committed-owner source names SELF for
    /// every arc, which is what a provisioned entity sees on the node that owns its keys; with the
    /// no-owner source the write path would (correctly) refuse every write as transient.
    private static ProvisioningContext fencedContext() {
        return fencedContext(recordingRegistrar());
    }

    private static ProvisioningContext fencedContext(EntityKeyspaceRegistrar registrar) {
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());

        return ProvisioningContext.provisioningContext()
                                  .withExtension(EntityLogSubstrate.class, inMemoryLog())
                                  .withExtension(CommittedPartitionOwnerSource.class, selfOwnsEveryArc())
                                  .withExtension(OwnershipEpochHighWater.class, highWater)
                                  .withExtension(EntityKeyspaceRegistrar.class, registrar)
                                  .withExtension(NodeId.class, SELF)
                                  .withExtension(Serializer.class, intSerializer())
                                  .withExtension(Deserializer.class, intDeserializer());
    }

    private static CommittedPartitionOwnerSource selfOwnsEveryArc() {
        return (_, _) -> Option.some(new CommittedOwner(SELF, Epoch.ZERO));
    }

    /// [#fencedContext] rebuilt with ONE collaborator left out — the shape a node produces after a
    /// refactor drops a single `registerExtension` call.
    private static ProvisioningContext contextWithout(Class<?> omitted) {
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());
        var context = ProvisioningContext.provisioningContext();

        context = addUnless(context, omitted, EntityLogSubstrate.class, inMemoryLog());
        context = addUnless(context, omitted, CommittedPartitionOwnerSource.class, selfOwnsEveryArc());
        context = addUnless(context, omitted, OwnershipEpochHighWater.class, highWater);
        context = addUnless(context, omitted, EntityKeyspaceRegistrar.class, recordingRegistrar());
        context = addUnless(context, omitted, NodeId.class, SELF);
        context = addUnless(context, omitted, Serializer.class, intSerializer());

        return addUnless(context, omitted, Deserializer.class, intDeserializer());
    }

    private static <T> ProvisioningContext addUnless(ProvisioningContext context,
                                                     Class<?> omitted,
                                                     Class<T> type,
                                                     T value) {
        return omitted.equals(type) ? context : context.withExtension(type, value);
    }

    /// A minimal in-memory log. The factory's job is to REFUSE without its collaborators and to build a
    /// working entity with them; the fence itself is proven against a fence-enforcing substrate in
    /// PartitionFencedDurableEntityFenceTest, so this one only has to behave like a log.
    private static EntityLogSubstrate inMemoryLog() {
        var records = new java.util.concurrent.ConcurrentHashMap<Integer, java.util.List<byte[]>>();

        return new EntityLogSubstrate() {
            @Override
            public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
                return Result.unitResult();
            }

            @Override
            public Promise<Long> append(String keyspace, int partition, byte[] record) {
                var partitionRecords = records.computeIfAbsent(partition, _ -> new java.util.ArrayList<>());

                partitionRecords.add(record);

                return Promise.success((long) partitionRecords.size() - 1);
            }

            @Override
            public Promise<java.util.List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
                var partitionRecords = records.getOrDefault(partition, java.util.List.of());
                var start = (int) fromOffset;

                return Promise.success(start >= partitionRecords.size()
                                       ? java.util.List.of()
                                       : java.util.List.copyOf(partitionRecords.subList(start,
                                                                                        Math.min(partitionRecords.size(),
                                                                                                 start + maxRecords))));
            }

            @Override
            public long headOffset(String keyspace, int partition) {
                return records.getOrDefault(partition, java.util.List.of()).size() - 1L;
            }

            @Override
            public long earliestRetainedOffset(String keyspace, int partition) {
                return records.getOrDefault(partition, java.util.List.of()).isEmpty() ? -1L : 0L;
            }

            @Override
            public boolean holdsPartition(String keyspace, int partition) {
                return true;
            }

            @Override
            public boolean localLogComplete(String keyspace, int partition) {
                return true;
            }

            @Override
            public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), intSerializer(), intDeserializer());
    }

    private static Serializer intSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {
                byteBuf.writeInt((Integer) object);
            }
        };
    }

    private static Deserializer intDeserializer() {
        return new Deserializer() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T read(ByteBuf byteBuf) {
                return (T) Integer.valueOf(byteBuf.readInt());
            }
        };
    }

    /// The factory gathers its collaborators with [Result#all], which composes every violation into one
    /// cause so a node missing several extensions reports all of them at once. The refusal is therefore
    /// asserted over [Cause#stream] — uniform for a composite and for a single cause — rather than by
    /// matching the outer instance, which for a composite would be the wrapper, not the domain refusal.
    private static void assertFenceUnavailable(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityProvisioningError.FenceUnavailable.class);
    }

    private static void failUnfenced(Object entity) {
        fail("provisioning must refuse rather than return an unfenced entity, got " + entity);
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
