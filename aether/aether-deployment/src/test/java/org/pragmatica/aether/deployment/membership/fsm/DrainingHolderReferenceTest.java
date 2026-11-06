package org.pragmatica.aether.deployment.membership.fsm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.KvBackedStreamRegistry;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.slice.stream.StreamRegistryHolderReconciler;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;

/// #2505 R1: the level reconcile is wired with the not-DEAD projection (`MembershipFsm::broadcastEligibleMembers`). A DRAINING node is
/// DEPARTING, which is not counted, while its slices are still ACTIVE: its references must not be swept, and when the drain is refuted
/// (DEPARTING -> MEMBER) the live member must still hold them. The counted projection (MEMBER + SUSPECT) fails this test.
class DrainingHolderReferenceTest {
    private static final ResourceAddress ORDERS = resourceAddress("com.example.app:orders:1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("com.example:orders-slice:1.0.0").unwrap();
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();
    private static final long SWIM_BOOT = 1_790_018_000_000L;

    @Test
    void aDepartingHolder_isNotSwept_andKeepsItsReferenceWhenTheDrainIsRefuted() {
        var outcome = run(MembershipFsm::broadcastEligibleMembers);

        assertThat(outcome.heldWhileMember).as("control: the member's reference is held").isTrue();
        assertThat(outcome.departing).as("control: the node is DEPARTING during the passes").isTrue();
        assertThat(outcome.heldAfterDrainPasses).as("a draining node's live reference is not swept").isTrue();
        assertThat(outcome.memberAgain).as("control: the drain was refuted").isTrue();
        assertThat(outcome.heldAfterRecovery).as("the live member still holds its reference").isTrue();
    }

    record Outcome(boolean heldWhileMember, boolean departing, boolean heldAfterDrainPasses, boolean memberAgain, boolean heldAfterRecovery) {}

    private Outcome run(Function<MembershipFsm, Set<NodeId>> projection) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        membership.onSwimHealthy(SELF, SWIM_BOOT);
        membership.onWorkerAdmissionHealthy(WORKER, 0x5eed_0001L, new MemberDescriptor(Option.none(), "worker", "source"));
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var registry = KvBackedStreamRegistry.kvBackedStreamRegistry(cluster(kvStore), kvStore);
        var reconciler = StreamRegistryHolderReconciler.streamRegistryHolderReconciler(registry,
                                                                                       SELF,
                                                                                       () -> Option.some(projection.apply(membership)),
                                                                                       () -> true);
        kvStore.process(kvStore.createBatch(registry.acquireCommands(StreamRegistryEntry.blueprint(ORDERS,
                                                                                                   RetentionPolicy.retentionPolicy(),
                                                                                                   Instant.EPOCH),
                                                                     WORKER,
                                                                     SLICE,
                                                                     0L,
                                                                     1_000L)));
        reconciler.tick().await().unwrap();
        reconciler.tick().await().unwrap();
        var heldWhileMember = heldBy(kvStore, WORKER) && "Member".equals(membership.memberStates().get(WORKER));

        membership.onDrainRequested(WORKER);
        reconciler.tick().await().unwrap();
        reconciler.tick().await().unwrap();
        var heldAfterDrain = heldBy(kvStore, WORKER);
        var departing = "Departing".equals(membership.memberStates().get(WORKER));

        membership.onSwimHealthy(WORKER, SWIM_BOOT + 1);
        var memberAgain = "Member".equals(membership.memberStates().get(WORKER));
        reconciler.tick().await().unwrap();

        return new Outcome(heldWhileMember, departing, heldAfterDrain, memberAgain, heldBy(kvStore, WORKER));
    }

    private static boolean heldBy(KVStore<AetherKey, AetherValue> kvStore, NodeId node) {
        var nodes = new ArrayList<NodeId>();

        kvStore.forEach(AetherKey.StreamRegistryHolderKey.class,
                        AetherValue.StreamRegistryHolderValue.class,
                        (key, _) -> {
                            if (SLICE.equals(key.artifact())) {
                                nodes.add(key.node());
                            }
                        });
        return nodes.contains(node);
    }

    private static ClusterNode<KVCommand<AetherKey>> cluster(KVStore<AetherKey, AetherValue> kvStore) {
        return new ClusterNode<>() {
            @Override
            public NodeId self() {
                return SELF;
            }

            @Override
            public TopologyManager topologyManager() {
                return null;
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                kvStore.process(kvStore.createBatch(commands));

                return Promise.success(List.of());
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
