// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintNamespace;
import org.pragmatica.aether.slice.blueprint.TopicAddressResolver;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicPublisherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicPublisherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicTier;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #1216 — THE SUBSCRIBER END'S WIRING, which no test reached before.
///
/// `CoDeployedTopicAddressingTest` pins the shared resolution seam and `PublisherFactoryTest` drives
/// the publisher end through its real factory, but neither can observe that `NodeDeploymentState`
/// actually PASSES the owning blueprint when it publishes a subscription. Measured on 2026-09-15:
/// reverting both of its call sites to the pre-#1216 form left all 1224 tests in this module green,
/// so the hunk was unpinned — a later revert would have been invisible exactly where the original
/// defect lived.
///
/// This drives the real LOAD → ACTIVE chain through the FSM harness with a slice whose generated
/// manifest declares a topic subscription, and asserts the `topic-sub/` key the node submits to
/// consensus carries the BLUEPRINT's namespace. The assertion is paired with its discriminator: the
/// blueprint and slice namespaces must genuinely differ, and the key must NOT carry the slice's —
/// otherwise the test would pass against the defect it exists to catch.
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStateTopicSubscriptionNamespaceTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:order-audit:1.0.0").unwrap();
    private static final Artifact CONTEXT_SLICE = Artifact.artifact("org.example:order-context:1.0.0").unwrap();
    private static final Artifact PUBLISHER_SLICE = Artifact.artifact("org.example:order-pub:1.0.0").unwrap();
    /// A publisher of the same topic that is not any slice under test, for seeding rows another deployment wrote.
    private static final Artifact OTHER_PUBLISHER = Artifact.artifact("org.example:other-pub:1.0.0").unwrap();
    private static final NodeId OTHER_NODE = NodeId.nodeId("other-node").unwrap();
    private static final Artifact BLUEPRINT = Artifact.artifact("org.example:orders-app:1.0.0").unwrap();
    private static final MethodName EXECUTE = MethodName.methodName("execute").unwrap();

    private KVStore<AetherKey, AetherValue> kvStore;
    private final Map<Artifact, ConfigurationProvider> composites = new HashMap<>();
    private RecordingClusterNode cluster;
    private SubscribingSliceStore store;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();

        // The deploy-time fact both ends read: this slice is OWNED by BLUEPRINT (#698).
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(SLICE.base()),
                                                                       SliceTargetValue.sliceTargetValue(SLICE.version(),
                                                                                                          1,
                                                                                                          Option.some(BlueprintId.blueprintId(BLUEPRINT)))),
                                                    new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(PUBLISHER_SLICE.base()),
                                                                       SliceTargetValue.sliceTargetValue(PUBLISHER_SLICE.version(),
                                                                                                          1,
                                                                                                          Option.some(BlueprintId.blueprintId(BLUEPRINT)))),
                                                    new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(CONTEXT_SLICE.base()),
                                                                       SliceTargetValue.sliceTargetValue(CONTEXT_SLICE.version(),
                                                                                                          1,
                                                                                                          Option.some(BlueprintId.blueprintId(BLUEPRINT)))))));

        cluster = new RecordingClusterNode(SELF);
        store = new SubscribingSliceStore();

        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory = fsm -> buildContext(fsm,
                                                                                                              ctxHolder,
                                                                                                              router,
                                                                                                              kvStore);
        harness = FsmTestHarness.harness("topic-sub-ns-" + SELF.id(), factory);
    }

    @Test
    void publishedSubscriptionKey_carriesTheBlueprintNamespace_notTheSlices() {
        var blueprintNamespace = BlueprintNamespace.deriveNamespace(BLUEPRINT).unwrap();
        var sliceNamespace = BlueprintNamespace.deriveNamespace(SLICE).unwrap();

        // Without this the assertions below could both hold under the defect.
        assertThat(blueprintNamespace).as("the discriminator: the two namespaces must genuinely differ")
                                      .isNotEqualTo(sliceNamespace);

        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(activePutFor(SELF)));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(subscriptionKeys()).as("the chain published a topic subscription")
                                                                  .isNotEmpty());

        var keys = subscriptionKeys().stream().map(TopicSubscriptionKey::asString).toList();

        assertThat(keys).as("#1216: the subscription must be registered under the OWNING BLUEPRINT's namespace")
                        .allMatch(key -> key.startsWith("topic-sub/" + blueprintNamespace + "/orders/1.0.0/"));
        assertThat(keys).as("and must NOT carry the subscribing slice's own namespace — that was the defect")
                        .noneMatch(key -> key.startsWith("topic-sub/" + sliceNamespace + "/"));
    }

    /// #1448 — THE KEY SHAPE THE NODE PUBLISHES, pinned at the writer rather than at the record.
    ///
    /// This harness is single-instance by construction (`RecordingClusterNode.apply` records commands
    /// and never feeds a shared `KVStore`), so it cannot host the survival test — one instance's unload
    /// cannot be observed to spare another's row here. That test lives in `StreamConsumerManagerTest`
    /// (`descaleOfAnotherInstance_leavesThisNodeAttached`). What this CAN pin, and what nothing else
    /// reaches, is that the writer scopes the key to THIS node: the whole fix is the node component
    /// arriving in the published key, and a `topicSubscriptionKey(...)` call that dropped it would
    /// still satisfy every namespace assertion above.
    @Test
    void publishedSubscriptionKey_isScopedToTheWritingNode() {
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(activePutFor(SELF)));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(subscriptionKeys()).as("the chain published a topic subscription")
                                                                  .isNotEmpty());

        assertThat(subscriptionKeys()).allSatisfy(key -> assertThat(key.nodeId()).as("#1448: the publishing node is part of the key, so one instance's unload Removes only its own row")
                                                                                 .isEqualTo(SELF));
        assertThat(subscriptionKeys()).as("and it is the LAST path segment, after the method — the string codec is the snapshot format")
                                      .allSatisfy(key -> assertThat(key.asString()).endsWith("/" + key.methodName()
                                                                                                       .name() + "/" + SELF.id()));
    }

    /// #1448 — THE UNLOAD WRITER, which is the half that actually caused the outage and was the only
    /// production hunk of this fix with no test of its own.
    ///
    /// `buildTopicSubscriptionRemoveCommand` must reconstruct the SAME key
    /// `buildTopicSubscriptionPutCommand` wrote, node component included. The two failure modes are
    /// opposite and both silent: a Remove built without the node (the original defect) deletes every
    /// other instance's row, and a Remove built with the WRONG node deletes nothing, so a subscription
    /// outlives the instance that served it and publishes keep routing to a slice that is gone. Neither
    /// raises. Asserting set equality against the published keys pins both directions at once.
    @Test
    void unloadRemovesExactlyTheKeyThisNodePublished() {
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(activePutFor(SELF)));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(subscriptionKeys()).as("precondition: the load chain published a topic subscription")
                                                                  .isNotEmpty());

        var published = subscriptionKeys();

        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, SliceState.UNLOAD)));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(removedSubscriptionKeys()).as("the unload chain reached unpublishTopicSubscriptions")
                                                                          .isNotEmpty());

        assertThat(removedSubscriptionKeys()).as("#1448: the unload Removes THIS node's own row — same address, artifact, method AND node as the Put")
                                             .containsExactlyElementsOf(published);
    }

    /// #1337 — THE TIER THE WRITER RECORDS, which no reader of the row can check. A bare handler's row says ANY; a handler the slice
    /// processor marked `context=message` says DURABLE, which is what lets a publisher refuse it without an RPC. The two tests are a
    /// pair: a writer that recorded one constant for both would fail one of them.
    @Test
    void publishedSubscriptionRow_forABareHandler_recordsAny() {
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(activePutFor(SELF)));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(subscriptionTiers()).as("the bare slice published a subscription row")
                                                                   .containsKey(SLICE.asString()));

        assertThat(subscriptionTiers().get(SLICE.asString())).as("a bare handler requires nothing of the tier").isEqualTo(TopicTier.ANY);
    }

    @Test
    void publishedSubscriptionRow_forAContextHandler_recordsDurable() {
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, CONTEXT_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(subscriptionTiers()).as("the context slice published a subscription row")
                                                                   .containsKey(CONTEXT_SLICE.asString()));

        assertThat(subscriptionTiers().get(CONTEXT_SLICE.asString())).as("a context handler (manifest context=message) requires the durable tier")
                                                                     .isEqualTo(TopicTier.DURABLE);
    }

    // --- #1337: the publisher row and the symmetric refusal -----------------------------------------------------------------

    private static final String EPHEMERAL_ORDERS = "ephemeral";
    private static final String DURABLE_ORDERS = "durable";

    /// A slice composite whose `orders` section is `durable`, `ephemeral`, or ABSENT (any other value of `kind`).
    private void declarePublisherTopic(Artifact slice, String kind) {
        var values = new HashMap<String, String>();

        switch (kind) {
            case DURABLE_ORDERS -> {
                values.put("orders.topic_name", "orders");
                values.put("orders.durability", "durable");
            }
            case EPHEMERAL_ORDERS -> values.put("orders.topic_name", "orders");
            default -> values.put("unrelated.key", "value");
        }

        composites.put(slice, ConfigurationProvider.builder().withSource(MapConfigSource.mapConfigSource("test", values).unwrap()).build());
    }

    private ResourceAddress orders() {
        return TopicAddressResolver.resolve(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders").unwrap();
    }

    private void seedPublisherRow(Artifact slice, NodeId node, TopicTier tier) {
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(TopicPublisherKey.topicPublisherKey(orders(), slice, node),
                                                                                             TopicPublisherValue.topicPublisherValue(node, tier)))));
    }

    private void seedSubscriberRow(Artifact slice, NodeId node, TopicTier tier) {
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(TopicSubscriptionKey.topicSubscriptionKey(orders(),
                                                                                                                                     slice,
                                                                                                                                     MethodName.methodName("onMessage").unwrap(),
                                                                                                                                     node),
                                                                                             TopicSubscriptionValue.topicSubscriptionValue(node, tier)))));
    }

    /// Commits what the node submitted into the store the way consensus would: the checks read committed rows.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitRecordedCommands() {
        var batch = cluster.commands().stream().filter(command -> command instanceof KVCommand.Put<?, ?> put && (put.key() instanceof TopicPublisherKey || put.key() instanceof TopicSubscriptionKey)
                                                                  || command instanceof KVCommand.Remove<?> remove && (remove.key() instanceof TopicPublisherKey || remove.key() instanceof TopicSubscriptionKey)).toList();

        if (!batch.isEmpty()) {
            kvStore.process(kvStore.createBatch((List) batch));
        }

        cluster.clear();
    }

    private Map<String, TopicTier> publisherTiers() {
        var tiers = new HashMap<String, TopicTier>();

        cluster.commands()
               .stream()
               .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put && put.key() instanceof TopicPublisherKey)
               .map(command -> (KVCommand.Put<AetherKey, ?>) command)
               .forEach(put -> tiers.put(((TopicPublisherKey) put.key()).artifact().asString(), ((TopicPublisherValue) put.value()).tier()));

        return tiers;
    }

    private boolean sliceFailedWith(String text) {
        return cluster.commands()
                      .stream()
                      .anyMatch(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                           && put.value() instanceof NodeArtifactValue value
                                           && value.state() == SliceState.FAILED
                                           && value.failureReason().map(reason -> reason.contains(text)).or(false));
    }

    @Test
    void publishedPublisherRow_recordsTheTierTheTopicSectionDeclares() {
        declarePublisherTopic(PUBLISHER_SLICE, DURABLE_ORDERS);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(publisherTiers()).containsKey(PUBLISHER_SLICE.asString()));

        assertThat(publisherTiers().get(PUBLISHER_SLICE.asString())).isEqualTo(TopicTier.DURABLE);
    }

    @Test
    void publishedPublisherRow_forAnEphemeralSection_recordsEphemeral() {
        declarePublisherTopic(PUBLISHER_SLICE, EPHEMERAL_ORDERS);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(publisherTiers()).containsKey(PUBLISHER_SLICE.asString()));

        assertThat(publisherTiers().get(PUBLISHER_SLICE.asString())).isEqualTo(TopicTier.EPHEMERAL);
    }

    /// An ABSENT section is the ephemeral default at provisioning, so it is what the row records; this is the case that motivated the
    /// ticket (a publisher that simply never declares durability).
    @Test
    void publishedPublisherRow_forAnAbsentSection_recordsEphemeral() {
        declarePublisherTopic(PUBLISHER_SLICE, "absent");
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(publisherTiers()).containsKey(PUBLISHER_SLICE.asString()));

        assertThat(publisherTiers().get(PUBLISHER_SLICE.asString())).isEqualTo(TopicTier.EPHEMERAL);
    }

    /// The row lives exactly as long as the deployment: the unload removes the key the activation wrote, node component included.
    @Test
    void unloadRemovesExactlyThePublisherRowThisNodeWrote() {
        declarePublisherTopic(PUBLISHER_SLICE, EPHEMERAL_ORDERS);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.ACTIVE)));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(publisherTiers()).containsKey(PUBLISHER_SLICE.asString()));
        var written = TopicPublisherKey.topicPublisherKey(orders(), PUBLISHER_SLICE, SELF);

        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.UNLOAD)));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(cluster.commands()).anyMatch(command -> command instanceof KVCommand.Remove<AetherKey> remove && remove.key().equals(written)));
    }

    @Test
    void durableSubscriber_isRefused_whileAnEphemeralPublisherRowStands() {
        seedPublisherRow(OTHER_PUBLISHER, OTHER_NODE, TopicTier.EPHEMERAL);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, CONTEXT_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(sliceFailedWith("topic tier mismatch")).isTrue());

        assertThat(subscriptionTiers()).as("a refused activation writes no subscription row").doesNotContainKey(CONTEXT_SLICE.asString());
        assertThat(cluster.commands().stream().filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof NodeArtifactValue value && value.failureReason().map(r -> r.contains("other-pub") && r.contains("EPHEMERAL")).or(false)).count())
            .as("the refusal names the conflicting publisher").isPositive();
    }

    @Test
    void ephemeralPublisher_isRefused_whileADurableSubscriberRowStands() {
        declarePublisherTopic(PUBLISHER_SLICE, EPHEMERAL_ORDERS);
        seedSubscriberRow(OTHER_PUBLISHER, OTHER_NODE, TopicTier.DURABLE);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(sliceFailedWith("topic tier mismatch")).isTrue());

        assertThat(publisherTiers()).as("a refused activation writes no publisher row").isEmpty();
    }

    /// Controls: the rows that do NOT conflict are accepted, so the refusals above key on the tiers and not on the mere presence of a row.
    @Test
    void durableSubscriber_isAccepted_whenThePublisherRowIsDurable() {
        seedPublisherRow(OTHER_PUBLISHER, OTHER_NODE, TopicTier.DURABLE);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, CONTEXT_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(subscriptionTiers()).containsKey(CONTEXT_SLICE.asString()));
        assertThat(sliceFailedWith("topic tier mismatch")).isFalse();
    }

    @Test
    void bareSubscriber_isAccepted_whileAnEphemeralPublisherRowStands() {
        seedPublisherRow(OTHER_PUBLISHER, OTHER_NODE, TopicTier.EPHEMERAL);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(activePutFor(SELF)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(subscriptionTiers()).containsKey(SLICE.asString()));
        assertThat(sliceFailedWith("topic tier mismatch")).isFalse();
    }

    @Test
    void durablePublisher_isAccepted_whileADurableSubscriberRowStands() {
        declarePublisherTopic(PUBLISHER_SLICE, DURABLE_ORDERS);
        seedSubscriberRow(OTHER_PUBLISHER, OTHER_NODE, TopicTier.DURABLE);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(publisherTiers()).containsKey(PUBLISHER_SLICE.asString()));
        assertThat(sliceFailedWith("topic tier mismatch")).isFalse();
    }

    /// Rows are matched on the topic's address: an ephemeral publisher of ANOTHER topic is no reason to refuse.
    @Test
    void durableSubscriber_isAccepted_whenTheEphemeralPublisherIsOnAnotherTopic() {
        var elsewhere = ResourceAddress.resourceAddress(ResourceAddress.DEFAULT_NAMESPACE, "invoices", org.pragmatica.aether.slice.resource.ResourceVersion.defaultVersion()).unwrap();

        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(TopicPublisherKey.topicPublisherKey(elsewhere, OTHER_PUBLISHER, OTHER_NODE),
                                                                                             TopicPublisherValue.topicPublisherValue(OTHER_NODE, TopicTier.EPHEMERAL)))));
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, CONTEXT_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(subscriptionTiers()).containsKey(CONTEXT_SLICE.asString()));
    }

    /// An upgrade replaces its predecessor: the same slice's rows at ANOTHER version are not a conflict.
    @Test
    void rowsOfTheSameSliceAtAnotherVersion_doNotBlockItsUpgrade() {
        seedPublisherRow(Artifact.artifact("org.example:order-context:0.9.0").unwrap(), OTHER_NODE, TopicTier.EPHEMERAL);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, CONTEXT_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(subscriptionTiers()).containsKey(CONTEXT_SLICE.asString()));
    }

    /// LIFECYCLE: the real publisher's own rows, committed as consensus would, block the durable subscriber while the publisher is
    /// deployed and stop blocking it once the publisher is undeployed. A stale row blocking a legitimate deployment is a defect.
    @Test
    void undeployThenDurableSubscriber_succeeds() {
        declarePublisherTopic(PUBLISHER_SLICE, EPHEMERAL_ORDERS);
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.ACTIVE)));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(publisherTiers()).containsKey(PUBLISHER_SLICE.asString()));
        commitRecordedCommands();
        assertThat(kvStore.get(TopicPublisherKey.topicPublisherKey(orders(), PUBLISHER_SLICE, SELF)).isPresent()).as("precondition: the row stands").isTrue();

        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, PUBLISHER_SLICE, SliceState.UNLOAD)));
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(cluster.commands()).anyMatch(command -> command instanceof KVCommand.Remove<AetherKey> remove && remove.key() instanceof TopicPublisherKey));
        commitRecordedCommands();
        assertThat(kvStore.get(TopicPublisherKey.topicPublisherKey(orders(), PUBLISHER_SLICE, SELF)).isPresent()).as("the unload removed the row").isFalse();

        harness.dispatch(new NodeArtifactPutReceived(putFor(SELF, CONTEXT_SLICE, SliceState.ACTIVE)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(subscriptionTiers()).containsKey(CONTEXT_SLICE.asString()));
        assertThat(sliceFailedWith("topic tier mismatch")).isFalse();
    }

    private java.util.Map<String, TopicTier> subscriptionTiers() {
        var tiers = new java.util.HashMap<String, TopicTier>();

        cluster.commands()
               .stream()
               .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put && put.key() instanceof TopicSubscriptionKey)
               .map(command -> (KVCommand.Put<AetherKey, ?>) command)
               .forEach(put -> tiers.put(((TopicSubscriptionKey) put.key()).artifact().asString(),
                                         ((TopicSubscriptionValue) put.value()).tier()));

        return tiers;
    }

    private List<TopicSubscriptionKey> subscriptionKeys() {
        return cluster.commands()
                      .stream()
                      .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                         && put.key() instanceof TopicSubscriptionKey)
                      .map(command -> (TopicSubscriptionKey) ((KVCommand.Put<AetherKey, ?>) command).key())
                      .toList();
    }

    private List<TopicSubscriptionKey> removedSubscriptionKeys() {
        return cluster.commands()
                      .stream()
                      .filter(command -> command instanceof KVCommand.Remove<AetherKey> remove
                                         && remove.key() instanceof TopicSubscriptionKey)
                      .map(command -> (TopicSubscriptionKey) ((KVCommand.Remove<AetherKey>) command).key())
                      .toList();
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> kvStore) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                store,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                kvStore,
                                                stubInvocationHandler(),
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> activePutFor(NodeId node) {
        return putFor(node, SliceState.ACTIVE);
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> putFor(NodeId node, SliceState state) {
        return putFor(node, SLICE, state);
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> putFor(NodeId node, Artifact slice, SliceState state) {
        var key = NodeArtifactKey.nodeArtifactKey(node, slice);
        var value = NodeArtifactValue.nodeArtifactValue(state);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    /// A slice whose class implements a NAMED interface, so `readReactiveBindingsFromManifest` looks
    /// up `META-INF/slice/TopicSubscriberSlice.manifest` (it skips `Slice` itself, which is why the
    /// sibling tests' lambda slices declare no bindings).
    interface TopicSubscriberSlice extends Slice {}

    /// The same slice with the context-carrying binding (#1337); its manifest is `ContextSubscriberSlice.manifest`.
    interface ContextSubscriberSlice extends Slice {}

    /// A slice that only PUBLISHES `orders` (#1337); its manifest is `PublisherSlice.manifest`.
    interface PublisherSlice extends Slice {}

    private static final class PublishingSlice extends SubscribingSlice implements PublisherSlice {}

    private static final class ContextSubscribingSlice extends SubscribingSlice implements ContextSubscriberSlice {}

    private static class SubscribingSlice implements TopicSubscriberSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of(SliceMethod.sliceMethod(EXECUTE,
                                                   (Unit unit) -> Promise.unitPromise(),
                                                   TypeToken.typeToken(Unit.class),
                                                   TypeToken.typeToken(Unit.class))
                                      .unwrap());
        }
    }

    private final class SubscribingSliceStore implements SliceStore {
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();

        private static LoadedSlice loadedSlice(Artifact artifact) {
            Slice slice = artifact.equals(CONTEXT_SLICE)
                          ? new ContextSubscribingSlice()
                          : artifact.equals(PUBLISHER_SLICE)
                            ? new PublishingSlice()
                            : new SubscribingSlice();

            return new LoadedSlice() {
                @Override public Artifact artifact() {
                    return artifact;
                }

                @Override public Slice slice() {
                    return slice;
                }
            };
        }

        @Override public List<LoadedSlice> loaded() {
            return List.copyOf(loadedSlices);
        }

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            var slice = loadedSlice(artifact);

            loadedSlices.add(slice);

            return Promise.success(slice);
        }

        @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<Unit> unloadSlice(Artifact artifact) {
            return Promise.unitPromise();
        }

        @Override public Option<ConfigurationProvider> sliceComposite(Artifact artifact) {
            return Option.option(composites.get(artifact));
        }
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<KVCommand<AetherKey>> commands = new CopyOnWriteArrayList<>();

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        List<KVCommand<AetherKey>> commands() {
            return List.copyOf(commands);
        }

        void clear() {
            commands.clear();
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(List.of());
        }
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 1;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private InvocationHandler stubInvocationHandler() {
        return new InvocationHandler() {
            @Override public void onInvokeRequest(InvokeRequest request) {}

            @Override public void registerSlice(Artifact artifact, SliceBridge bridge) {}

            @Override public void unregisterSlice(Artifact artifact) {}

            @Override public Option<SliceBridge> localSlice(Artifact artifact) {
                return Option.some(stubBridge());
            }

            @Override public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override public Option<InvocationMetricsCollector> metricsCollector() {
                return Option.none();
            }
        };
    }

    private static SliceBridge stubBridge() {
        return new SliceBridge() {
            @Override public Promise<byte[]> invoke(String methodName, byte[] input) {
                return Promise.success(new byte[0]);
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public ClassLoader classLoader() {
                return getClass().getClassLoader();
            }

            @Override public List<String> methodNames() {
                return List.of(EXECUTE.name());
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
