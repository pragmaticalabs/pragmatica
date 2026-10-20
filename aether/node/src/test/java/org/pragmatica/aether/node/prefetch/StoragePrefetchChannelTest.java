// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.prefetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SystemTags;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.PrefetchHint;
import org.pragmatica.storage.PrefetchManager;
import org.pragmatica.storage.PrefetchStats;
import org.pragmatica.swim.PiggybackBuffer;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.AppGossip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.PrefetchHint.prefetchHint;

/// The storage prefetch channel over the production codec registry: what it puts on the wire, how much, and what it
/// does with what comes back -- including the datagram SWIM would build around it.
class StoragePrefetchChannelTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final int BUDGET = PiggybackBuffer.piggybackBudgetFor(SELF);

    /// A prefetch manager that hands out the hints it is given, once, and remembers what it is told.
    private static final class ScriptedManager implements PrefetchManager {
        final List<PrefetchHint> toCollect = new CopyOnWriteArrayList<>();
        final List<List<PrefetchHint>> processed = new CopyOnWriteArrayList<>();
        int collections;

        @Override
        public void recordAccess(BlockId blockId) {}

        @Override
        public List<PrefetchHint> collectHints() {
            collections++;

            var collected = List.copyOf(toCollect);

            toCollect.clear();

            return collected;
        }

        @Override
        public void processHints(List<PrefetchHint> hints) {
            processed.add(hints);
        }

        @Override
        public PrefetchStats stats() {
            return PrefetchStats.empty();
        }

        @Override
        public Result<Unit> activate() {
            return Result.unitResult();
        }

        @Override
        public Result<Unit> deactivate() {
            return Result.unitResult();
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }

    private final SliceCodec codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private ScriptedManager artifacts;
    private ScriptedManager content;
    private StoragePrefetchChannel channel;

    @BeforeEach
    void setUp() {
        artifacts = new ScriptedManager();
        content = new ScriptedManager();
        channel = StoragePrefetchChannel.storagePrefetchChannel(Map.of("artifacts", artifacts, "content", content), codec, codec);
    }

    private static String idHex(int index) {
        return String.format("%064x", index);
    }

    private static List<PrefetchHint> hints(int count) {
        return IntStream.range(0, count).mapToObj(i -> prefetchHint(idHex(i), 100 - i)).toList();
    }

    private StoragePrefetchGossip decoded(byte[] bytes) {
        return codec.decode(bytes);
    }

    @Test
    void outgoing_hintsOfOneInstance_goOutNamingThatInstance() {
        artifacts.toCollect.addAll(hints(3));

        var gossip = decoded(channel.outgoing(BUDGET).unwrap());

        assertThat(gossip.instance()).isEqualTo("artifacts");
        assertThat(gossip.toPrefetchHints()).containsExactlyElementsOf(hints(3));
    }

    @Test
    void outgoing_withNothingToSay_isNone() {
        assertThat(channel.outgoing(BUDGET).isEmpty()).isTrue();
    }

    @Test
    void outgoing_oneInstancePerRound_inRotation_soABusyInstanceDoesNotStarveAnother() {
        artifacts.toCollect.addAll(hints(2));
        content.toCollect.addAll(hints(2));

        var first = decoded(channel.outgoing(BUDGET).unwrap()).instance();
        artifacts.toCollect.addAll(hints(2));
        content.toCollect.addAll(hints(2));
        var second = decoded(channel.outgoing(BUDGET).unwrap()).instance();

        assertThat(List.of(first, second)).containsExactlyInAnyOrder("artifacts", "content");
    }

    @Test
    void outgoing_skipsAnInstanceWithNothingToSay_ratherThanSpendTheRound() {
        content.toCollect.addAll(hints(1));

        // Whichever instance the rotation starts at, the round goes to the one that has hints.
        assertThat(decoded(channel.outgoing(BUDGET).unwrap()).instance()).isEqualTo("content");
        content.toCollect.addAll(hints(1));
        assertThat(decoded(channel.outgoing(BUDGET).unwrap()).instance()).isEqualTo("content");
    }

    @Test
    void outgoing_hintsThatDoNotFitTheBudget_areTrimmedHottestKept_andCounted() {
        artifacts.toCollect.addAll(hints(40));

        var whole = codec.encode(StoragePrefetchGossip.from("artifacts", hints(40)));
        var tight = whole.length / 2;
        var bytes = channel.outgoing(tight).unwrap();

        assertThat(bytes.length).isLessThanOrEqualTo(tight);
        var kept = decoded(bytes).toPrefetchHints();

        assertThat(kept).isNotEmpty().hasSizeLessThan(40);
        assertThat(kept).as("the hottest (first-collected) are kept").containsExactlyElementsOf(hints(kept.size()));
        assertThat(channel.stats().trimmed()).isEqualTo(1);
    }

    @Test
    void outgoing_whenEvenOneHintDoesNotFit_isNone() {
        artifacts.toCollect.addAll(hints(1));

        assertThat(channel.outgoing(4).isEmpty()).isTrue();
    }

    @Test
    void outgoing_atTheDefaultHintCount_fitsTheBudgetWithoutTrimming() {
        // The default is five hints per round.
        artifacts.toCollect.addAll(hints(5));

        var bytes = channel.outgoing(BUDGET).unwrap();

        assertThat(decoded(bytes).hints()).hasSize(5);
        assertThat(bytes.length).as("five hints are a small fraction of the budget").isLessThan(BUDGET / 2);
        assertThat(channel.stats().trimmed()).isZero();
    }

    @Test
    void appGossip_aFullBudgetPayload_stillFitsTheDatagramCeilingAfterTheEnvelopeAndEncryption() {
        var payload = new byte[BUDGET];
        var datagram = codec.encode((SwimMessage) AppGossip.appGossip(SELF, StoragePrefetchChannel.CHANNEL, payload));

        assertThat(datagram.length + 32).as("encoded envelope + payload + AES-GCM framing")
                                         .isLessThanOrEqualTo(PiggybackBuffer.MAX_DATAGRAM_BYTES);
    }

    @Test
    void incoming_routesTheHintsToTheManagerOfTheInstanceTheyName() {
        var payload = codec.encode(StoragePrefetchGossip.from("content", hints(2)));

        channel.incoming(new NodeId("node-peer"), payload);

        assertThat(content.processed).hasSize(1);
        assertThat(content.processed.getFirst()).containsExactlyElementsOf(hints(2));
        assertThat(artifacts.processed).isEmpty();
    }

    @Test
    void incoming_forAnInstanceThisNodeDoesNotHave_isDroppedAndCounted_neverFails() {
        var payload = codec.encode(StoragePrefetchGossip.from("somewhere-else", hints(2)));

        channel.incoming(new NodeId("node-peer"), payload);

        assertThat(artifacts.processed).isEmpty();
        assertThat(content.processed).isEmpty();
        assertThat(channel.stats().droppedUnknownInstance()).isEqualTo(1);
    }

    @Test
    void incoming_bytesThatAreNotHints_areDroppedAndCounted_neverThrow() {
        channel.incoming(new NodeId("node-peer"), new byte[]{1, 2, 3, 4, 5});
        channel.incoming(new NodeId("node-peer"), new byte[0]);

        assertThat(channel.stats().droppedMalformed()).isEqualTo(2);
        assertThat(artifacts.processed).isEmpty();
    }

    @Test
    void wireTags_areTheOnesPinnedForThePrefetchPayloadAndTheGossipDatagram() {
        assertThat(SystemTags.tagFor("org.pragmatica.swim.SwimMessage.AppGossip")).isEqualTo(2169);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.node.prefetch.StoragePrefetchGossip")).isEqualTo(2170);
        assertThat(SystemTags.tagFor("org.pragmatica.aether.node.prefetch.StoragePrefetchGossip.Hint")).isEqualTo(2171);
    }

    @Test
    void appGossip_roundTripsThroughTheProductionRegistry_payloadIntact() {
        var inner = codec.encode(StoragePrefetchGossip.from("artifacts", hints(3)));
        var original = AppGossip.appGossip(SELF, StoragePrefetchChannel.CHANNEL, inner);

        AppGossip decoded = codec.decode(codec.encode((SwimMessage) original));

        assertThat(decoded.from()).isEqualTo(SELF);
        assertThat(decoded.channel()).isEqualTo(StoragePrefetchChannel.CHANNEL);
        assertThat(decoded.payload()).isEqualTo(inner);
        assertThat(this.<StoragePrefetchGossip> decodeAny(decoded.payload()).hints()).hasSize(3);
    }

    private <T> T decodeAny(byte[] bytes) {
        return codec.decode(bytes);
    }

    @Test
    void storagePrefetchGossip_copiesItsHints() {
        var mutable = new ArrayList<>(List.of(StoragePrefetchGossip.Hint.hint(idHex(1), 5)));
        var gossip = StoragePrefetchGossip.storagePrefetchGossip("artifacts", mutable);

        mutable.clear();

        assertThat(gossip.hints()).hasSize(1);
    }
}
