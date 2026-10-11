// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1883 G3: the REAL stream manager supplies the enforced config and applies each committed Put
/// AFTER the announcer, in the production handler order. Hand-feeds nothing about what is enforced.
class NotAppliedAgainstEnforcedConfigTest {
    private final StreamPartitionManager manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
    private final List<OperationalEvent> events = new ArrayList<>();
    private final StreamIsrAnnouncer announcer = StreamIsrAnnouncer.streamIsrAnnouncer(manager::confirmationFactorFor,
                                                                                      manager::confirmationFactorAfter,
                                                                                      manager::enforcedConfig,
                                                                                      (_, _) -> Option.none(),
                                                                                      events::add);
    private Option<StreamConfigValue> committed = Option.none();
    private long clock = 100L;

    @Test
    void threeToOneToTwo_announcesBoth_adoptedIsSilent_redeliveryKeepsTheId_newLifeIsSilent() {
        commit(config(4, 3, 3, 1L));
        assertThat(manager.enforcedConfig("orders").map(StreamConfig::confirmationFactor)).as("premise: hydrated at CF 3")
                                                                                            .isEqualTo(Option.some(3));
        assertThat(notApplied()).as("first commit").isEmpty();

        var toOne = commit(config(4, 3, 1, 1L));
        var toTwo = commit(config(4, 3, 2, 1L));

        assertThat(notApplied()).extracting(OperationalEvent.StreamConfigChangeNotApplied::requestedConfirmationFactor)
                                .as("3 -> 1 -> 2: both requests are unapplied").containsExactly(1, 2);
        assertThat(notApplied()).allSatisfy(e -> assertThat(e.effectiveConfirmationFactor()).isEqualTo(3));
        assertThat(manager.confirmationFactorFor("orders")).as("still enforced").isEqualTo(3);

        var before = notApplied().size();
        deliver(toTwo);
        assertThat(notApplied()).as("a redelivered Put").hasSize(before + 1);
        assertThat(notApplied().getLast().eventId()).as("redelivery keeps the id").isEqualTo(notApplied().get(before - 1).eventId());

        events.clear();
        commit(config(4, 5, 1, 1L));
        assertThat(notApplied()).as("adopted: RF raise with a CF lowering").isEmpty();
        assertThat(manager.confirmationFactorFor("orders")).as("adopted").isEqualTo(1);

        events.clear();
        commit(config(4, 3, 1, 9L));
        assertThat(notApplied()).as("a new life").isEmpty();
        assertThat(toOne).isNotNull();
    }

    @Test
    void partitionCountChange_bothDirections_announceThePartitionReason_andAreNotApplied() {
        commit(config(4, 3, 2, 1L));
        commit(config(8, 3, 2, 1L));
        commit(config(2, 3, 2, 1L));
        commit(config(8, 5, 3, 1L));

        assertThat(notApplied()).hasSize(3)
                                .allSatisfy(e -> assertThat(e.reason()).startsWith("partition count of an existing stream cannot change"));
        assertThat(manager.enforcedConfig("orders").map(StreamConfig::partitions)).isEqualTo(Option.some(4));
        assertThat(manager.confirmationFactorFor("orders")).as("a stronger config with a new count is not adopted either").isEqualTo(2);
    }

    /// Announced once per committed Put, as documented: re-committing the identical unapplied config (a new Put, new createdAt) announces again.
    @Test
    void identicalUnappliedConfigRecommitted_announcesOncePerCommittedPut() {
        commit(config(4, 3, 3, 1L));
        commit(config(4, 3, 1, 1L));
        commit(config(4, 3, 1, 1L));

        assertThat(notApplied()).hasSize(2);
    }

    private List<OperationalEvent.StreamConfigChangeNotApplied> notApplied() {
        return events.stream()
                     .filter(OperationalEvent.StreamConfigChangeNotApplied.class::isInstance)
                     .map(OperationalEvent.StreamConfigChangeNotApplied.class::cast)
                     .toList();
    }

    private ValuePut<StreamConfigKey, StreamConfigValue> commit(StreamConfig config) {
        var put = new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey("orders"),
                                                     StreamConfigValue.streamConfigValue(config, clock++)),
                                 committed);

        deliver(put);
        committed = Option.some(put.cause().value());

        return put;
    }

    /// Production order: the announcer first, then the manager.
    private void deliver(ValuePut<StreamConfigKey, StreamConfigValue> put) {
        announcer.onConfigPut(put);
        manager.onStreamConfigPut(put);
    }

    private static StreamConfig config(int partitions, int rf, int cf, long incarnation) {
        return StreamConfig.streamConfig("orders", partitions, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest")
                           .withReplication(new ReplicationFactors(rf, cf))
                           .withIncarnation(incarnation);
    }
}
