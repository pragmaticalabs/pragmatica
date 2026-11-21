// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionDeclaration;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3554: "retention bounds changed" is exactly a change in the bounds a node ENFORCES. It is raised from the stream
/// manager's adopt site, so there is no event without an adoption and no adoption of new bounds without an event, and
/// `oldBounds` are the bounds the node enforced before. Each node here is a REAL stream manager fed the committed Puts
/// after its announcer, in the production handler order, with the announcer wired to the manager's adopt site as
/// `StreamAssembly` wires it; nothing about what a node enforces is hand-fed.
class RetentionChangedAtAdoptSiteTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    private Option<StreamConfigValue> committed = Option.none();
    private long clock = 100L;

    /// F1: a refused Put raises no retention event, though its retention section differs from the enforced one; its
    /// not-applied event stays as it is.
    @Test
    void refusedPut_raisesNoRetentionEvent_andKeepsItsNotApplied() {
        var lowered = refused(declaring(GIB, 3, 2));
        var repartitioned = refused(declaring(GIB, 3, 3).withPartitions(8));
        var truncated = refused(declaring(GIB, 3, 3).withDeclaration(StreamDeclaration.streamDeclaration(retention(GIB),
                                                                                                         Option.some(TruncationDeclaration.truncationDeclaration(Option.none(),
                                                                                                                                                                Option.some(60_000L))),
                                                                                                         Option.none(),
                                                                                                         Option.none())));

        assertThat(lowered.retentionChanges()).as("a lowered confirmation factor").isEmpty();
        assertThat(lowered.notApplied()).as("its not-applied event").hasSize(1);
        assertThat(repartitioned.retentionChanges()).as("another partition count").isEmpty();
        assertThat(repartitioned.notApplied()).as("its not-applied event").hasSize(1);
        assertThat(truncated.retentionChanges()).as("a truncation change").isEmpty();
        assertThat(truncated.notApplied()).as("an immutable-key change has no not-applied event").isEmpty();
        assertThat(truncated.enforcedMaxBytes()).as("premise: the node keeps enforcing the old bounds").isEqualTo(2 * GIB);
    }

    /// Case Y (v3554d): A (cf 3, 2 GiB) is enforced, B (cf 2, 1 GiB) is refused, then C (cf 3, 5 GiB) is adopted. ONE event,
    /// whose old bounds are the ENFORCED 2 GiB, never B's 1 GiB, which was committed but never enforced. Mutants: raise
    /// from the announcer again (old = B), or take the old bounds from the committed prior.
    @Test
    void refusedPutThenAdoptedPut_raisesOneEvent_withTheEnforcedOldBounds() {
        var node = new Node();

        commit(declaring(2 * GIB, 3, 3), node);
        commit(declaring(GIB, 3, 2), node);

        assertThat(node.enforcedMaxBytes()).as("premise: B refused").isEqualTo(2 * GIB);
        node.events.clear();
        commit(declaring(5 * GIB, 3, 3), node);

        assertThat(node.enforcedMaxBytes()).as("premise: C adopted").isEqualTo(5 * GIB);
        assertThat(node.retentionChanges()).singleElement()
                                           .satisfies(event -> {
                                               assertThat(event.oldBounds()).as("the enforced bounds before").isEqualTo(boundsOf(2 * GIB));
                                               assertThat(event.newBounds()).isEqualTo(boundsOf(5 * GIB));
                                           });
    }

    /// v3554d p2: after a refused Put, a second Put that is refused too (cf 2 against the enforced cf 3) raises no retention
    /// event, although its bounds differ from the previously committed ones; its not-applied event stays.
    @Test
    void refusedPutThenRefusedPut_raisesNoRetentionEvent() {
        var node = new Node();

        commit(declaring(2 * GIB, 3, 3), node);
        commit(declaring(GIB, 3, 2), node);
        node.events.clear();
        commit(declaring(5 * GIB, 3, 2), node);

        assertThat(node.enforcedMaxBytes()).as("premise: not adopted").isEqualTo(2 * GIB);
        assertThat(node.notApplied()).as("premise: its not-applied event").hasSize(1);
        assertThat(node.retentionChanges()).as("no adoption, no event").isEmpty();
    }

    /// Case Z (v3554d): A (cf 3, 2 GiB) is enforced, B (cf 1, 1 GiB) is refused, then C (cf 3, 1 GiB) is adopted: its bounds
    /// equal the previously committed B's, yet it changes what the node enforces, so EXACTLY ONE event, 2 GiB -> 1 GiB.
    /// Mutant: raise from the announcer again (zero, or two with both raisers).
    @Test
    void adoptedPutAfterARefusedOne_raisesExactlyOneEvent() {
        var node = new Node();

        commit(declaring(2 * GIB, 3, 3), node);
        commit(declaring(GIB, 3, 1), node);
        node.events.clear();
        commit(declaring(GIB, 3, 3), node);

        assertThat(node.enforcedMaxBytes()).as("premise: C adopted").isEqualTo(GIB);
        assertThat(node.retentionChanges()).singleElement()
                                           .satisfies(event -> {
                                               assertThat(event.oldBounds()).isEqualTo(boundsOf(2 * GIB));
                                               assertThat(event.newBounds()).isEqualTo(boundsOf(GIB));
                                           });
    }

    /// The controls of the adopt site: a first commit (hydration), an adoption that keeps the bounds (a raised factor), an
    /// unchanged re-commit and a new life replace no enforced bounds of a life, and raise nothing.
    @Test
    void hydrationKeptBoundsAndNewLife_raiseNothing() {
        var node = new Node();

        commit(declaring(2 * GIB, 3, 2), node);
        commit(declaring(2 * GIB, 3, 3), node);
        commit(declaring(2 * GIB, 3, 3), node);
        commit(declaring(GIB, 3, 3).withIncarnation(2L), node);

        assertThat(node.enforcedConfirmationFactor()).as("premise: the factor raise was adopted").isEqualTo(3);
        assertThat(node.enforcedMaxBytes()).as("premise: the new life is enforced").isEqualTo(GIB);
        assertThat(node.retentionChanges()).as("hydration; factor only; unchanged; another life").isEmpty();
    }

    /// Item 3: nodes with EQUAL enforced history (here both refused B) raise the same event with the same id.
    @Test
    void nodesWithEqualEnforcedHistory_raiseTheSameId() {
        var a = new Node();
        var b = new Node();

        commit(declaring(2 * GIB, 3, 3), a, b);
        commit(declaring(GIB, 3, 2), a, b);
        commit(declaring(3 * GIB, 3, 3), a, b);

        assertThat(a.retentionChanges()).hasSize(1);
        assertThat(b.retentionChanges()).singleElement()
                                        .satisfies(event -> assertThat(event.eventId()).isEqualTo(a.retentionChanges()
                                                                                                   .getFirst()
                                                                                                   .eventId()));
    }

    /// Nodes whose enforced configs DIVERGED (a refused B, b hydrated from it) each name the bounds THEY enforced before the
    /// next adoption, so their ids differ: no equality is claimed across diverged nodes (t3554g-divergence.md). Mutant: take
    /// the old bounds from the committed prior, which makes both name B's bounds.
    @Test
    void nodesWhoseEnforcedConfigsDiverged_nameTheirOwnEnforcedOldBounds() {
        var a = new Node();
        var b = new Node();

        commit(declaring(2 * GIB, 3, 3), a);
        commit(declaring(GIB, 3, 2), a, b);

        assertThat(a.enforcedMaxBytes()).as("premise: a refused the lowering and keeps the old bounds").isEqualTo(2 * GIB);
        assertThat(b.enforcedMaxBytes()).as("premise: b hydrated from the refused Put").isEqualTo(GIB);
        commit(declaring(3 * GIB, 3, 3), a, b);

        assertThat(a.retentionChanges()).singleElement()
                                        .satisfies(event -> assertThat(event.oldBounds()).isEqualTo(boundsOf(2 * GIB)));
        assertThat(b.retentionChanges()).singleElement()
                                        .satisfies(event -> assertThat(event.oldBounds()).isEqualTo(boundsOf(GIB)));
    }

    /// A fresh node enforcing 2 GiB at confirmation factor 3, then the committed `config`, which it must refuse.
    private Node refused(StreamConfig config) {
        var node = new Node();

        commit(declaring(2 * GIB, 3, 3), node);
        node.events.clear();
        commit(config, node);

        return node;
    }

    /// One committed Put, its prior committed value as `oldValue`, delivered to each of `nodes`.
    private void commit(StreamConfig config, Node... nodes) {
        var put = new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey("orders"),
                                                     StreamConfigValue.streamConfigValue(config, clock++)),
                                 committed);

        List.of(nodes)
            .forEach(node -> node.deliver(put));
        committed = Option.some(put.cause()
                                   .value());
    }

    private static String boundsOf(long maxBytes) {
        return String.valueOf(retention(maxBytes));
    }

    private static Option<RetentionDeclaration> retention(long maxBytes) {
        return Option.some(RetentionDeclaration.retentionDeclaration(Option.none(), Option.some(maxBytes), Option.none(), Option.none()));
    }

    /// The policy carries the declared `max_bytes` as the deploy path does.
    private static StreamConfig declaring(long maxBytes, int rf, int cf) {
        return StreamConfig.streamConfig("orders", 4, RetentionPolicy.retentionPolicy(1000, maxBytes, 600_000), "earliest")
                           .withReplication(new ReplicationFactors(rf, cf))
                           .withIncarnation(1L)
                           .withDeclaration(StreamDeclaration.streamDeclaration(retention(maxBytes),
                                                                                Option.none(),
                                                                                Option.none(),
                                                                                Option.none()));
    }

    /// One node: a real manager, and the announcer that reads it.
    private static final class Node {
        private final StreamPartitionManager manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        private final List<OperationalEvent> events = new ArrayList<>();
        private final StreamIsrAnnouncer announcer = StreamIsrAnnouncer.streamIsrAnnouncer(manager::confirmationFactorFor,
                                                                                          manager::confirmationFactorAfter,
                                                                                          manager::enforcedConfig,
                                                                                          (_, _) -> Option.none(),
                                                                                          events::add);

        private Node() {
            manager.onConfigAdopted(announcer::onConfigAdopted);
        }

        /// Production order: the announcer first, then the manager.
        private void deliver(ValuePut<StreamConfigKey, StreamConfigValue> put) {
            announcer.onConfigPut(put);
            manager.onStreamConfigPut(put);
        }

        private long enforcedMaxBytes() {
            return manager.enforcedConfig("orders")
                          .map(config -> config.retention()
                                               .maxBytes())
                          .or(-1L);
        }

        private int enforcedConfirmationFactor() {
            return manager.enforcedConfig("orders")
                          .map(StreamConfig::confirmationFactor)
                          .or(-1);
        }

        private List<OperationalEvent.StreamRetentionChanged> retentionChanges() {
            return events.stream()
                         .filter(OperationalEvent.StreamRetentionChanged.class::isInstance)
                         .map(OperationalEvent.StreamRetentionChanged.class::cast)
                         .toList();
        }

        private List<OperationalEvent.StreamConfigChangeNotApplied> notApplied() {
            return events.stream()
                         .filter(OperationalEvent.StreamConfigChangeNotApplied.class::isInstance)
                         .map(OperationalEvent.StreamConfigChangeNotApplied.class::cast)
                         .toList();
        }
    }
}
