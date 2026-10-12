// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static org.assertj.core.api.Assertions.assertThat;

/// NT-96 (deployment state machine spec R-1.5.1): a round-trip differs, or a pin is missing, for any new type.
///
/// The five keys, five values, `Generation` and `ProcessIncarnation` cross consensus, so each needs a pinned system
/// tag (an unpinned one falls into the hashed user range and `NodeCodecs` refuses to build); the four types their
/// components need (`SliceGoal`, `UnresponsiveEntry`, `DepartureState`, `DepartureCause`) are pinned with them.
/// Every value below sets each component to a value no other component shares, so a codec that swaps two fields
/// round-trips to a different record.
class DeploymentFsmKvRecordsCodecTest {
    private static final String KEY = "org.pragmatica.aether.slice.kvstore.AetherKey.";
    private static final String VALUE = "org.pragmatica.aether.slice.kvstore.AetherValue.";

    /// The pinned type names and the tags they were assigned, in assignment order.
    private static final Map<String, Integer> PINS = pins();

    private static final NodeId NODE = NodeId.nodeId("core-7").unwrap();
    private static final NodeId OTHER_NODE = NodeId.nodeId("worker-9").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders-slice:1.2.3").unwrap();
    private static final BlueprintId BLUEPRINT = BlueprintId.blueprintId("org.example:orders-app:1.2.3").unwrap();
    private static final Generation GENERATION = new Generation(41L, 7L);
    private static final Generation OTHER_GENERATION = new Generation(43L, 2L);
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1_760_000_000_123L, 0x5EEDFACE0BADL);

    private static Map<String, Integer> pins() {
        var pins = new LinkedHashMap<String, Integer>();

        pins.put(KEY + "SliceDesiredKey", 2143);
        pins.put(KEY + "SliceObservedKey", 2144);
        pins.put(KEY + "DeploymentApplyKey", 2145);
        pins.put(KEY + "ArtifactJudgmentKey", 2146);
        pins.put(KEY + "NodeDepartureKey", 2147);
        pins.put(VALUE + "SliceDesiredValue", 2148);
        pins.put(VALUE + "SliceObservedValue", 2149);
        pins.put(VALUE + "DeploymentApplyValue", 2150);
        pins.put(VALUE + "ArtifactJudgmentValue", 2151);
        pins.put(VALUE + "NodeDepartureValue", 2152);
        pins.put(VALUE + "Generation", 2153);
        pins.put(VALUE + "ProcessIncarnation", 2154);
        pins.put(VALUE + "SliceGoal", 2155);
        pins.put(VALUE + "UnresponsiveEntry", 2156);
        pins.put(VALUE + "DepartureState", 2157);
        pins.put(VALUE + "DepartureCause", 2158);

        return Map.copyOf(pins);
    }

    @Nested
    class Pins {
        @Test
        void everyNewType_hasItsAssignedSystemTag() {
            PINS.forEach((name, tag) -> assertThat(SystemTags.tagFor(name)).as(name).isEqualTo(tag));
        }

        @Test
        void everyNewType_isRegisteredInTheNodeCodecUnderItsPin() {
            var registered = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()).registeredTypes();
            var tagsByName = new HashMap<String, Integer>();

            registered.forEach((type, codec) -> tagsByName.put(type.getCanonicalName(), codec.tag()));

            PINS.forEach((name, tag) -> assertThat(tagsByName.get(name)).as(name).isEqualTo(tag));
        }

        /// The sealed hierarchies are the authority on what a key or value variant is, so a variant added later
        /// without a pin fails here by name instead of at the first node that boots.
        @Test
        void everyAetherKeyVariant_isPinned() {
            assertThat(unpinned(AetherKey.class)).isEmpty();
        }

        @Test
        void everyAetherValueVariant_isPinned() {
            assertThat(unpinned(AetherValue.class)).isEmpty();
        }

        private List<String> unpinned(Class<?> sealedRoot) {
            var missing = new ArrayList<String>();

            collectUnpinned(sealedRoot, missing);

            return missing;
        }

        private void collectUnpinned(Class<?> type, List<String> missing) {
            if (type.isInterface() && type.isSealed() && type.getPermittedSubclasses() != null) {
                for (var permitted : type.getPermittedSubclasses()) {
                    collectUnpinned(permitted, missing);
                }
            }

            if (!type.isInterface() && SystemTags.tagFor(type.getCanonicalName()) == SystemTags.NOT_PINNED) {
                missing.add(type.getCanonicalName());
            }
        }
    }

    @Nested
    class RoundTrips {
        @Test
        void sliceDesired_roundTripsEveryGoal() {
            for (var goal : SliceGoal.values()) {
                roundTrip(SliceDesiredKey.sliceDesiredKey(NODE, ARTIFACT),
                          new SliceDesiredValue(goal, GENERATION, 1_760_000_111_000L));
            }
        }

        @Test
        void sliceObserved_roundTripsEveryComponentDistinctly() {
            var value = new SliceObservedValue(SliceState.FAILED,
                                               12L,
                                               GENERATION,
                                               PROCESS,
                                               Option.some("load failed"),
                                               true,
                                               5,
                                               List.of("handle", "describe"),
                                               1_760_000_222_000L);

            assertThat(value.methods()).as("the constructor keeps what it is given").containsExactly("handle", "describe");
            roundTrip(SliceObservedKey.sliceObservedKey(NODE, ARTIFACT), value);
        }

        @Test
        void sliceObserved_roundTripsAbsentFailureReasonAndNoMethods() {
            roundTrip(SliceObservedKey.sliceObservedKey(OTHER_NODE, ARTIFACT),
                      new SliceObservedValue(SliceState.ACTIVE,
                                             1L,
                                             OTHER_GENERATION,
                                             new ProcessIncarnation(0L, 0L),
                                             Option.none(),
                                             false,
                                             0,
                                             List.of(),
                                             0L));
        }

        @Test
        void deploymentApply_roundTripsWithAndWithoutPrevious() {
            var key = DeploymentApplyKey.deploymentApplyKey(BLUEPRINT);
            var previous = AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(BLUEPRINT, List.of()));

            roundTrip(key, new DeploymentApplyValue("attempt-17", Option.some(previous), 1_760_000_333_000L));
            roundTrip(key, new DeploymentApplyValue("attempt-18", Option.none(), 1_760_000_444_000L));
        }

        @Test
        void artifactJudgment_roundTripsUnresponsiveMap() {
            var key = ArtifactJudgmentKey.artifactJudgmentKey(ARTIFACT);
            var unresponsive = Map.of(NODE,
                                      new UnresponsiveEntry(GENERATION, SliceGoal.ACTIVE, 1_760_000_555_000L),
                                      OTHER_NODE,
                                      new UnresponsiveEntry(OTHER_GENERATION, SliceGoal.LOADED, 1_760_000_666_000L));

            var judgment = new ArtifactJudgmentValue(true, 4, unresponsive);

            assertThat(judgment.unresponsive()).as("the constructor keeps what it is given").isEqualTo(unresponsive);
            roundTrip(key, judgment);
            roundTrip(key, new ArtifactJudgmentValue(false, 0, Map.of()));
        }

        @Test
        void nodeDeparture_roundTripsEveryStateAndCause() {
            var key = NodeDepartureKey.nodeDepartureKey(NODE);

            for (var cause : DepartureCause.values()) {
                roundTrip(key, new NodeDepartureValue(DepartureState.DEPARTED, 6L, PROCESS, Option.some(cause), 1_760_000_777_000L));
            }
            roundTrip(key, new NodeDepartureValue(DepartureState.ADMITTED, 7L, PROCESS, Option.none(), 1_760_000_888_000L));
        }

        @Test
        void nodeDeparture_roundTripsUnknownEpochAndProcess() {
            roundTrip(NodeDepartureKey.nodeDepartureKey(OTHER_NODE),
                      new NodeDepartureValue(DepartureState.DEPARTED,
                                             0L,
                                             new ProcessIncarnation(0L, 0L),
                                             Option.some(DepartureCause.STALE),
                                             1L));
        }

        /// Writes the command, reads it back, then writes the decoded command again: the value must come back equal
        /// AND the second encoding must be the same bytes as the first, so a decoder that loses or reorders a
        /// component cannot hide behind a lenient `equals`.
        private void roundTrip(AetherKey key, AetherValue value) {
            var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
            var first = Unpooled.buffer();

            codec.write(first, new KVCommand.Put<AetherKey, AetherValue>(key, value));
            var encoded = ByteBufUtil.getBytes(first);
            KVCommand.Put<?, ?> decoded = codec.read(first);
            var second = Unpooled.buffer();

            codec.write(second, decoded);

            assertThat(decoded.key()).isEqualTo(key);
            assertThat(decoded.value()).isEqualTo(value);
            assertThat(ByteBufUtil.getBytes(second)).isEqualTo(encoded);
            assertThat(first.readableBytes()).as("bytes left after decoding").isZero();
        }
    }

    @Nested
    class Generations {
        @Test
        void compareTo_ordersLexicographically_viewSequenceBeforeCounter() {
            assertThat(new Generation(1L, 99L)).isLessThan(new Generation(2L, 0L));
            assertThat(new Generation(2L, 3L)).isLessThan(new Generation(2L, 4L));
            assertThat(new Generation(2L, 3L)).isEqualByComparingTo(new Generation(2L, 3L));
            assertThat(new Generation(3L, 0L)).isGreaterThan(new Generation(2L, Long.MAX_VALUE));
        }
    }

    @Nested
    class KeyStrings {
        @Test
        void keys_roundTripThroughTheirCanonicalString() {
            var desired = SliceDesiredKey.sliceDesiredKey(NODE, ARTIFACT);
            var observed = SliceObservedKey.sliceObservedKey(NODE, ARTIFACT);
            var apply = DeploymentApplyKey.deploymentApplyKey(BLUEPRINT);
            var judgment = ArtifactJudgmentKey.artifactJudgmentKey(ARTIFACT);
            var departure = NodeDepartureKey.nodeDepartureKey(NODE);

            assertThat(SliceDesiredKey.sliceDesiredKey(desired.asString()).unwrap()).isEqualTo(desired);
            assertThat(SliceObservedKey.sliceObservedKey(observed.asString()).unwrap()).isEqualTo(observed);
            assertThat(DeploymentApplyKey.deploymentApplyKey(apply.asString()).unwrap()).isEqualTo(apply);
            assertThat(ArtifactJudgmentKey.artifactJudgmentKey(judgment.asString()).unwrap()).isEqualTo(judgment);
            assertThat(NodeDepartureKey.nodeDepartureKey(departure.asString()).unwrap()).isEqualTo(departure);
        }

        @Test
        void desiredAndObservedKeys_neverShareAString() {
            assertThat(SliceDesiredKey.sliceDesiredKey(NODE, ARTIFACT).asString())
                .isNotEqualTo(SliceObservedKey.sliceObservedKey(NODE, ARTIFACT).asString());
            assertThat(SliceDesiredKey.sliceDesiredKey(SliceObservedKey.sliceObservedKey(NODE, ARTIFACT).asString()).isFailure())
                .isTrue();
            assertThat(SliceObservedKey.sliceObservedKey(SliceDesiredKey.sliceDesiredKey(NODE, ARTIFACT).asString()).isFailure())
                .isTrue();
        }

        @Test
        void malformedStrings_areRefused() {
            assertThat(SliceDesiredKey.sliceDesiredKey("slice-desired/core-7").isFailure()).isTrue();
            assertThat(SliceDesiredKey.sliceDesiredKey("slice-desired//org.example:orders-slice:1.2.3").isFailure()).isTrue();
            assertThat(SliceObservedKey.sliceObservedKey("slice-observed/core-7/").isFailure()).isTrue();
            assertThat(DeploymentApplyKey.deploymentApplyKey("deployment-apply/").isFailure()).isTrue();
            assertThat(ArtifactJudgmentKey.artifactJudgmentKey("artifact-judgment/").isFailure()).isTrue();
            assertThat(NodeDepartureKey.nodeDepartureKey("node-departure/").isFailure()).isTrue();
        }
    }
}
