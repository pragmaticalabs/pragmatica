// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1216 — CO-DEPLOYED TOPIC ADDRESSING, the case nothing in this repo exercised.
///
/// `examples/notification-hub/blueprint.toml` declares three co-deployed slices with three DISTINCT
/// artifacts — exactly the shape that was broken — and dodged the defect only because it publishes
/// over `StreamPublisher`. No example and no test put two distinct slice artifacts on two ends of one
/// TOPIC, so the defect was invisible: `TopicSubscriptionRegistry` matches on exact string equality,
/// the two ends derived two different namespaces from their own coordinates, and `TopicPublisher`
/// returned a SUCCESSFUL promise having delivered nothing, with no log line on either side.
///
/// WHAT THIS PINS, AND WHAT IT DOES NOT. Both ends now scope a bare topic to the OWNING BLUEPRINT,
/// each reading it from the same committed `SliceTargetValue.owningBlueprint`:
///  - the subscriber (`NodeDeploymentState.owningBlueprintOf`) calls
///    [OwningBlueprintResolver#owningBlueprintOf(KVStore, Artifact)] directly, holding the store;
///  - the publisher (`PublisherFactory.owningBlueprintOf`) calls the node-registered
///    [OwningBlueprintResolver#kvBacked] extension, holding only the slice id string.
/// Those are two different entry points onto one fact, which is precisely where they could drift
/// apart again — so this exercises BOTH and asserts they agree, rather than asserting either alone.
///
/// It does NOT drive `NodeDeploymentState` itself, which needs a full node context; the publisher's
/// end-to-end delivery is covered by `PublisherFactoryTest.NamespaceAlignment` through the real
/// factory and a real `TopicSubscriptionRegistry`.
class CoDeployedTopicAddressingTest {
    private static final String TOPIC = "orders";

    private static final Artifact BLUEPRINT = Artifact.artifact("org.example:orders-app:1.0.0").unwrap();
    private static final Artifact OTHER_BLUEPRINT = Artifact.artifact("org.example:billing-app:1.0.0").unwrap();
    private static final Artifact PUBLISHER_SLICE = Artifact.artifact("org.example:order-intake:1.0.0").unwrap();
    private static final Artifact SUBSCRIBER_SLICE = Artifact.artifact("org.example:order-audit:1.0.0").unwrap();

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    /// The deploy-time Put the blueprint apply makes for each of its slices
    /// (`ClusterDeploymentState`), which is the fact both ends read back.
    private void deploy(Artifact slice, Artifact owningBlueprint) {
        var value = SliceTargetValue.sliceTargetValue(slice.version(),
                                                      3,
                                                      Option.some(BlueprintId.blueprintId(owningBlueprint)));

        apply(SliceTargetKey.sliceTargetKey(slice.base()), value);
    }

    private void apply(AetherKey key, AetherValue value) {
        kvStore.process(kvStore.createBatch(List.of(new Put<>(key, value))));
    }

    /// Verbatim the expression `NodeDeploymentState.resolveSubscriptionAddress` now evaluates.
    private String subscriberAddress(Artifact slice) {
        return TopicAddressResolver.resolve(OwningBlueprintResolver.owningBlueprintOf(kvStore, slice), slice, TOPIC)
                                   .unwrap()
                                   .asString();
    }

    /// Verbatim the expression `PublisherFactory.resolveTopicAddress` now evaluates — same fact,
    /// reached through the node-registered extension from a slice-id STRING rather than an Artifact.
    private String publisherAddress(Artifact slice) {
        var owning = OwningBlueprintResolver.kvBacked(kvStore).owningBlueprintOf(slice.asString());

        return TopicAddressResolver.resolve(owning, slice, TOPIC).unwrap().asString();
    }

    @Nested
    class OneBlueprint {
        @Test
        void twoDistinctSlices_resolveTheSameTopicAddress_bothDirections() {
            deploy(PUBLISHER_SLICE, BLUEPRINT);
            deploy(SUBSCRIBER_SLICE, BLUEPRINT);

            assertThat(publisherAddress(PUBLISHER_SLICE))
                    .as("#1216: co-deployed distinct slices must meet on one address")
                    .isEqualTo(subscriberAddress(SUBSCRIBER_SLICE));

            assertThat(publisherAddress(SUBSCRIBER_SLICE))
                    .as("the reverse direction must hold too — either slice may publish")
                    .isEqualTo(subscriberAddress(PUBLISHER_SLICE));
        }

        @Test
        void address_isNamespacedByTheBlueprint_notBySlice() {
            deploy(PUBLISHER_SLICE, BLUEPRINT);

            var blueprintNamespace = BlueprintNamespace.deriveNamespace(BLUEPRINT).unwrap();
            var sliceNamespace = BlueprintNamespace.deriveNamespace(PUBLISHER_SLICE).unwrap();

            assertThat(publisherAddress(PUBLISHER_SLICE)).startsWith(blueprintNamespace + ":");
            // The discriminator: before #1216 the address carried the SLICE namespace and this passed
            // for the wrong reason. The two namespaces must be genuinely different for the assertion
            // above to mean anything.
            assertThat(blueprintNamespace).isNotEqualTo(sliceNamespace);
            assertThat(publisherAddress(PUBLISHER_SLICE)).doesNotStartWith(sliceNamespace + ":");
        }
    }

    @Nested
    class TwoBlueprints {
        /// The controlled counterpart to [OneBlueprint#twoDistinctSlices_resolveTheSameTopicAddress_bothDirections]:
        /// the SAME two distinct slice artifacts, so slice-distinctness is held constant and the
        /// OWNING BLUEPRINT is the only variable that changes between the two tests. That is what
        /// makes this a statement about blueprints rather than about slices — the distinction the
        /// pre-#1216 version of this test silently failed to draw.
        @Test
        void slicesInDifferentBlueprints_doNotShareATopicAddress() {
            deploy(PUBLISHER_SLICE, BLUEPRINT);
            deploy(SUBSCRIBER_SLICE, OTHER_BLUEPRINT);

            assertThat(publisherAddress(PUBLISHER_SLICE))
                    .as("isolation across blueprints is the property; only the owner differs here")
                    .isNotEqualTo(subscriberAddress(SUBSCRIBER_SLICE));
        }
    }

    @Nested
    class NoOwningBlueprint {
        /// A unit test, a programmatic publisher, or a slice not deployed under a blueprint: nothing
        /// is committed under `SliceTargetKey`, so there is no second spelling to disagree with and
        /// both ends scope to the slice's own coordinates. The two ends must still AGREE — the
        /// absence must be benign, not merely tolerated on one side.
        @Test
        void undeployedSlice_fallsBackToItsOwnCoordinates_onBothEnds() {
            var expected = BlueprintNamespace.deriveNamespace(PUBLISHER_SLICE).unwrap() + ":" + TOPIC + ":1.0.0";

            assertThat(OwningBlueprintResolver.owningBlueprintOf(kvStore, PUBLISHER_SLICE)).isEqualTo(Option.none());
            assertThat(subscriberAddress(PUBLISHER_SLICE)).isEqualTo(expected);
            assertThat(publisherAddress(PUBLISHER_SLICE)).isEqualTo(expected);
        }

        /// A slice target committed WITHOUT an owner (the standalone-deployment factory) is the same
        /// state as no target at all for addressing purposes.
        @Test
        void sliceTargetWithoutOwner_fallsBackToItsOwnCoordinates() {
            var value = SliceTargetValue.sliceTargetValue(PUBLISHER_SLICE.version(), 3);

            apply(SliceTargetKey.sliceTargetKey(PUBLISHER_SLICE.base()), value);

            assertThat(OwningBlueprintResolver.owningBlueprintOf(kvStore, PUBLISHER_SLICE)).isEqualTo(Option.none());
            assertThat(publisherAddress(PUBLISHER_SLICE)).isEqualTo(subscriberAddress(PUBLISHER_SLICE));
        }
    }

    @Nested
    class FullyNamespacedDeclaration {
        /// A declaration that already carries its namespace is parsed verbatim and the owning
        /// blueprint never enters the derivation — this is how a topic deliberately addresses
        /// ACROSS blueprints, and it is the reason the topic path needs only the blueprint identity
        /// where the stream path needed a published address map.
        @Test
        void explicitAddress_isUnaffectedByTheOwningBlueprint() {
            deploy(PUBLISHER_SLICE, BLUEPRINT);

            var declared = "other.ns:orders:2.0.0";
            var owned = TopicAddressResolver.resolve(OwningBlueprintResolver.owningBlueprintOf(kvStore, PUBLISHER_SLICE),
                                                     PUBLISHER_SLICE,
                                                     declared)
                                            .unwrap();
            var unowned = TopicAddressResolver.resolve(Option.none(), PUBLISHER_SLICE, declared).unwrap();

            assertThat(owned.asString()).isEqualTo(declared);
            assertThat(unowned.asString()).isEqualTo(declared);
        }
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
