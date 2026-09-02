// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// The #492-class boot guard: every ROUTED `Message.Wired` type must have a codec, or the node refuses
/// to start.
///
/// Why this exists at all: a generated codec registry that was never aggregated into `NodeCodecs` made
/// every message of that type vanish at the transport, with ZERO log lines — runs 2 through 5 burned on
/// exactly that. The guard turns a silent, days-long forensic hunt into a boot failure that names the
/// type.
///
/// The load-bearing test here is [#verifyRoutedTypesEncodable_exemptsLocalTypes_whileFlaggingTheSameShapeAsWired].
/// `Message.Local` types legitimately have no codec, and the exemption is the SEALED HIERARCHY rather
/// than a name list — a list would rot the first time someone added a local message. That test proves
/// the discriminator is the interface and not something incidental about the fixtures.
class VerifyRoutedTypesEncodableTest {
    /// A wired type with no registered codec — the #492 shape.
    record OrphanedWired(String payload) implements Message.Wired {
        @Override
        public StreamType streamType() {
            return StreamType.CONTROL;
        }
    }

    /// A SECOND orphaned wired type, so the accumulation (not just first-failure) is observable.
    record AlsoOrphanedWired(String payload) implements Message.Wired {
        @Override
        public StreamType streamType() {
            return StreamType.CONTROL;
        }
    }

    /// Structurally identical to [OrphanedWired] except for the branch of the sealed hierarchy it sits
    /// on — which is the whole point: same absence of a codec, opposite verdict.
    record ProcessLocal(String payload) implements Message.Local {}

    @Test
    void verifyRoutedTypesEncodable_fails_namingTheTypeAndTheAggregationHint() {
        var result = verify(routes(OrphanedWired.class));

        assertThat(result.isFailure()).isTrue();
        assertThat(messageOf(result))
            .as("an operator reading a boot failure must learn WHICH type is unencodable")
            .contains(OrphanedWired.class.getName());
        assertThat(messageOf(result))
            .as("and where to look — the lived cause was a generated registry that existed but was"
                + " never aggregated, so the hint is the fix, not decoration")
            .contains("NodeCodecs");
    }

    /// The sealed-hierarchy discriminator, armed both ways in one test: a local type with no codec is
    /// exempt, and the SAME shape declared wired is flagged. Neither half proves anything alone — the
    /// first could pass because the guard is broken, the second because local types are also caught.
    @Test
    void verifyRoutedTypesEncodable_exemptsLocalTypes_whileFlaggingTheSameShapeAsWired() {
        assertThat(verify(routes(ProcessLocal.class)).isSuccess())
            .as("a Message.Local type never crosses the transport, so a missing codec is not a defect")
            .isTrue();
        assertThat(verify(routes(OrphanedWired.class)).isFailure())
            .as("the same missing codec on the WIRED branch must fail — else the exemption above is"
                + " just a guard that never fires")
            .isTrue();
    }

    /// One boot failure must report the WHOLE set. Reporting only the first turns a single fix-and-boot
    /// cycle into one cycle per missing type, which on a cloud run is an hour each.
    @Test
    void verifyRoutedTypesEncodable_namesEveryMissingType_notJustTheFirst() {
        var result = verify(routes(OrphanedWired.class, AlsoOrphanedWired.class));

        assertThat(messageOf(result)).contains(OrphanedWired.class.getName())
                                     .contains(AlsoOrphanedWired.class.getName());
    }

    /// The negative control: a real wired type with a real registered codec passes. Without this the
    /// suite could not distinguish "the guard works" from "the guard rejects everything".
    @Test
    void verifyRoutedTypesEncodable_succeeds_whenEveryRoutedWiredTypeHasACodec() {
        assertThat(verify(routes(NetworkMessage.KeepAlive.class)).isSuccess())
            .as("KeepAlive is registered in NetCodecs — a routed wired type WITH a codec must boot")
            .isTrue();
    }

    @Test
    void verifyRoutedTypesEncodable_succeeds_whenNothingIsRouted() {
        assertThat(AetherNode.verifyRoutedTypesEncodable(List.of(), codec()).isSuccess())
            .as("a node routing nothing has nothing to verify — an empty set is not a failure")
            .isTrue();
    }

    // === helpers ===

    private static Result<Unit> verify(List<Entry<?>> entries) {
        return AetherNode.verifyRoutedTypesEncodable(entries, codec());
    }

    /// A no-op route per type — `verifyRoutedTypesEncodable` reads only the routed TYPES, never the
    /// handlers, so the consumers are deliberately inert.
    @SafeVarargs
    private static List<Entry<?>> routes(Class<? extends Message>... types) {
        var entries = new ArrayList<Entry<?>>();

        for (var type : types) {
            entries.add(entryFor(type));
        }

        return entries;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Entry<?> entryFor(Class<? extends Message> type) {
        return Entry.route((Class) type, _ -> {});
    }

    /// The REAL node-side codec set (framework + consensus + net), not a stub that answers a fixed
    /// boolean: the guard's whole job is to agree with what the transport can actually encode, and a
    /// stub would let the two drift apart silently — the very failure mode being guarded.
    private static SliceCodec codec() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), all);
    }

    private static String messageOf(Result<Unit> result) {
        return result.fold(Cause::message, _ -> "unexpectedly succeeded");
    }
}
