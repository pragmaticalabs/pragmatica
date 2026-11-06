// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.FreedCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static org.assertj.core.api.Assertions.assertThat;

/// O3-1 wire tags (memory `new-kv-record-needs-a-wire-tag`): the identity record's types cross consensus, so each needs a pinned system
/// tag, disjoint from every tag taken on rc5 and on any open branch (2191 and 2192 are `StreamRegistryHolder*`, #2505, scanned 2026-10-31).
/// The value shape changed before its first merge, so there is no migration, and a round trip of every state pins the layout.
class NodeIdentityCodecTest {
    private static final String KEY = "org.pragmatica.aether.slice.kvstore.AetherKey.";
    private static final String VALUE = "org.pragmatica.aether.slice.kvstore.AetherValue.";
    private static final Map<String, Integer> PINS = pins();

    private static final NodeId NODE = NodeId.nodeId("heal-7").unwrap();
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1_760_000_000_123L, 0x5EEDFACE0BADL);

    private static Map<String, Integer> pins() {
        var pins = new LinkedHashMap<String, Integer>();

        pins.put(KEY + "NodeIdentityKey", 2193);
        pins.put(VALUE + "NodeLifecycleValue", 2194);
        pins.put(VALUE + "IdentityState", 2195);
        pins.put(VALUE + "IdentityCause", 2196);
        pins.put(VALUE + "IdentityFacet", 2197);
        pins.put(VALUE + "CapacityFacet", 2198);
        pins.put(VALUE + "CapacityPhase", 2199);
        pins.put(VALUE + "FreedCause", 2200);

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

        @Test
        void theNewTags_areDisjointFromEveryOtherPinnedType() {
            var registered = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()).registeredTypes();
            var owners = new HashMap<Integer, String>();

            registered.forEach((type, codec) -> owners.merge(codec.tag(), type.getCanonicalName(), (a, b) -> a + " AND " + b));

            PINS.forEach((name, tag) -> assertThat(owners.get(tag)).as("tag %d", tag).isEqualTo(name));
        }
    }

    @Nested
    class RoundTrips {
        @Test
        void identityOnly_roundTrips() {
            roundTrip(new NodeLifecycleValue(Option.some(identity(IdentityState.ADMITTED, Option.none())), Option.none()));
        }

        @Test
        void capacityOnly_roundTripsEveryPhase() {
            for (var phase : CapacityPhase.values()) {
                roundTrip(new NodeLifecycleValue(Option.none(), Option.some(capacity(phase, Option.none(), Option.none()))));
            }
        }

        @Test
        void bothFacets_roundTripWithTheTombstoneFieldsAndTheCause() {
            for (var cause : FreedCause.values()) {
                roundTrip(new NodeLifecycleValue(Option.some(identity(IdentityState.TERMINATED, Option.some(IdentityCause.REPLACED))),
                                                 Option.some(capacity(CapacityPhase.FREED, Option.some(cause), Option.some(1_760_000_555_000L)))));
            }
        }

        @Test
        void everyIdentityStateAndCause_roundTrips() {
            for (var state : IdentityState.values()) {
                roundTrip(new NodeLifecycleValue(Option.some(identity(state, Option.none())), Option.none()));
            }

            for (var cause : IdentityCause.values()) {
                roundTrip(new NodeLifecycleValue(Option.some(identity(IdentityState.TERMINATED, Option.some(cause))), Option.none()));
            }
        }

        @Test
        void anUncountedExternalHold_roundTrips() {
            roundTrip(new NodeLifecycleValue(Option.none(), Option.some(new CapacityFacet("hetzner-fsn", "external-uncounted", "worker", CapacityPhase.DISPATCHED, Option.none(), Option.none()))));
        }

        @Test
        void emptyRecord_roundTrips() {
            roundTrip(new NodeLifecycleValue(Option.none(), Option.none()));
        }

        private IdentityFacet identity(IdentityState state, Option<IdentityCause> cause) {
            return new IdentityFacet(state, 4L, PROCESS, "worker", cause, 140L);
        }

        private CapacityFacet capacity(CapacityPhase phase, Option<FreedCause> freedCause, Option<Long> freedAt) {
            return new CapacityFacet("hetzner-fsn", "project-1", "core", phase, freedCause, freedAt);
        }

        private void roundTrip(AetherValue value) {
            var key = NodeIdentityKey.nodeIdentityKey(NODE);
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
    class Enums {
        @Test
        void identityState_keepsTheLifecycleOrder_unknownLast() {
            assertThat(IdentityState.values()).extracting(Enum::name).containsExactly("ADMITTED", "DEPARTED", "TERMINATED", "UNKNOWN");
        }

        @Test
        void identityCause_listsTheSixWriterRanks_unknownLast() {
            assertThat(IdentityCause.values()).extracting(Enum::name)
                                              .containsExactly("OPERATOR", "OPERATOR_FORCE", "REPLACED", "RETIRED", "DRAINED", "GONE", "UNKNOWN");
        }

        @Test
        void capacityPhase_keepsTodaysOrdinals_freedBeforeUnknown() {
            assertThat(CapacityPhase.values()).extracting(Enum::name).containsExactly("DISPATCHED", "OBSERVED", "RELEASED", "FREED", "UNKNOWN");
        }

        @Test
        void freedCause_listsTheFiveCauses_unknownLast() {
            assertThat(FreedCause.values()).extracting(Enum::name)
                                           .containsExactly("REFUSED", "ABSENT_CONFIRMED", "OPERATOR_CLEAR", "TERMINATED", "EXTERNAL_RELEASED", "UNKNOWN");
        }
    }
}
