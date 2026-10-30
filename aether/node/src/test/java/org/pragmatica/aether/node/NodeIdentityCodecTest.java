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
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdentityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.OperationId;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisionIntent;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static org.assertj.core.api.Assertions.assertThat;

/// ID-1b wire tags (memory `new-kv-record-needs-a-wire-tag`): the identity record's types cross consensus, so each needs a pinned system
/// tag, disjoint from every tag taken on rc5 and on any open branch (2191 and 2192 are `StreamRegistryHolder*`, #2505, scanned 2026-10-31).
/// The value shape changed before its first merge, so there is no migration, and a round trip of every state pins the layout.
class NodeIdentityCodecTest {
    private static final String KEY = "org.pragmatica.aether.slice.kvstore.AetherKey.";
    private static final String VALUE = "org.pragmatica.aether.slice.kvstore.AetherValue.";
    private static final Map<String, Integer> PINS = pins();

    private static final NodeId NODE = NodeId.nodeId("heal-7").unwrap();
    private static final NodeId SUCCESSOR = NodeId.nodeId("heal-9").unwrap();
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1_760_000_000_123L, 0x5EEDFACE0BADL);
    private static final OperationId OPERATION = OperationId.operationId("heal-7").unwrap();

    private static Map<String, Integer> pins() {
        var pins = new LinkedHashMap<String, Integer>();

        pins.put(KEY + "NodeIdentityKey", 2193);
        pins.put(VALUE + "NodeIdentityValue", 2194);
        pins.put(VALUE + "IdentityState", 2195);
        pins.put(VALUE + "IdentityCause", 2196);
        pins.put(VALUE + "ProvisionIntent", 2197);
        pins.put(VALUE + "OperationId", 2198);

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
        void intended_roundTripsTheIntentAndNoProcess() {
            roundTrip(new NodeIdentityValue(IdentityState.INTENDED, 0L, Option.none(), "core", Option.none(), Option.none(), 100L, Option.some(intent(Option.none()))));
        }

        @Test
        void provisioning_roundTripsTheServerId() {
            roundTrip(new NodeIdentityValue(IdentityState.PROVISIONING, 0L, Option.none(), "core", Option.none(), Option.none(), 110L, Option.some(intent(Option.some("srv-42")))));
        }

        @Test
        void admitted_roundTripsTheProcessWithAndWithoutAnIntent() {
            roundTrip(new NodeIdentityValue(IdentityState.ADMITTED, 3L, Option.some(PROCESS), "worker", Option.none(), Option.none(), 120L, Option.some(intent(Option.some("srv-42")))));
            roundTrip(new NodeIdentityValue(IdentityState.ADMITTED, 1L, Option.some(PROCESS), "core", Option.none(), Option.none(), 120L, Option.none()));
        }

        @Test
        void terminated_roundTripsEveryCauseWithTheSuccessor() {
            for (var cause : IdentityCause.values()) {
                roundTrip(new NodeIdentityValue(IdentityState.TERMINATED, 2L, Option.some(PROCESS), "core", Option.some(SUCCESSOR), Option.some(cause), 130L, Option.none()));
            }
        }

        @Test
        void everyState_roundTrips() {
            for (var state : IdentityState.values()) {
                roundTrip(new NodeIdentityValue(state, 4L, Option.some(PROCESS), "core", Option.none(), Option.none(), 140L, Option.none()));
            }
        }

        private ProvisionIntent intent(Option<String> serverId) {
            return new ProvisionIntent(OPERATION, "cpx31", "fsn1", "dc-1", serverId, 99L);
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
            assertThat(IdentityState.values()).extracting(Enum::name)
                                              .containsExactly("INTENDED", "PROVISIONING", "ADMITTED", "DEPARTED", "TERMINATED", "UNKNOWN");
        }

        @Test
        void identityCause_listsTheSixWriterRanksThenTheFourAbandonments_unknownLast() {
            assertThat(IdentityCause.values()).extracting(Enum::name)
                                              .containsExactly("OPERATOR",
                                                               "OPERATOR_FORCE",
                                                               "REPLACED",
                                                               "RETIRED",
                                                               "DRAINED",
                                                               "GONE",
                                                               "PROVISION_FAILED",
                                                               "PROVISION_TIMEOUT",
                                                               "CANCELLED",
                                                               "VANISHED",
                                                               "UNKNOWN");
        }
    }
}
