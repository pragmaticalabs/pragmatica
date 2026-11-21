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

/// #3554: "retention bounds changed" is raised exactly when the cluster ADOPTS new bounds, and the decision and the id are
/// pure functions of COMMITTED data. Each node here is a REAL stream manager fed the committed Puts after its announcer,
/// in the production handler order; nothing about what a node enforces is hand-fed.
class RetentionChangedOnCommittedDataTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    private Option<StreamConfigValue> committed = Option.none();
    private long clock = 100L;

    /// (a) F1: a refused Put raises no retention event, though its retention section differs from the enforced one; its
    /// not-applied event stays as it is. Mutant: drop the adoption filter.
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

    /// (b) Node a refused an earlier Put and enforces the bounds before it; node b hydrated from that refused Put and
    /// enforces its bounds. The next Put is adopted on both, and both raise ONE event with the SAME id, whose old bounds
    /// are the previously COMMITTED ones. Mutant: take the old bounds from the enforced config.
    @Test
    void nodesWhoseEnforcedConfigsDiverged_raiseTheSameEventWithTheSameId() {
        var a = new Node();
        var b = new Node();

        commit(declaring(2 * GIB, 3, 3), a);
        commit(declaring(GIB, 3, 2), a, b);

        assertThat(a.enforcedMaxBytes()).as("premise: a refused the lowering and keeps the old bounds").isEqualTo(2 * GIB);
        assertThat(b.enforcedMaxBytes()).as("premise: b hydrated from the refused Put").isEqualTo(GIB);
        a.events.clear();
        b.events.clear();

        commit(declaring(3 * GIB, 3, 3), a, b);

        assertThat(a.enforcedMaxBytes()).as("premise: adopted on a").isEqualTo(3 * GIB);
        assertThat(b.enforcedMaxBytes()).as("premise: adopted on b").isEqualTo(3 * GIB);
        assertThat(a.retentionChanges()).singleElement()
                                        .satisfies(event -> assertThat(event.oldBounds()).as("the previously committed bounds")
                                                                                         .isEqualTo(String.valueOf(retention(GIB))));
        assertThat(b.retentionChanges()).singleElement()
                                        .satisfies(event -> assertThat(event.eventId()).isEqualTo(a.retentionChanges()
                                                                                                   .getFirst()
                                                                                                   .eventId()));
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
