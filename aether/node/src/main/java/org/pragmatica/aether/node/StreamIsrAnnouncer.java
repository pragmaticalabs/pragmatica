// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// #1883 (owner rule: an operator-facing condition emits an event on its transition): announces a stream partition's
/// in-sync set falling BELOW its confirmation factor — from then every acknowledged publish is refused with
/// `NOT_ENOUGH_REPLICAS` — and its return to the factor.
///
/// Derived from the COMMITTED ISR transition, exactly like [StreamFailoverAnnouncer]: every node applies the same
/// ownership Put and derives the same event from its old and new value, and the cluster-events aggregator publishes
/// only on the owner of the cluster-events partition, so one copy reaches the stream. The condition is a pure function
/// of the committed record and the stream's configured factor, so a commit that keeps it unchanged (an ISR change that
/// stays at or above, or stays below, the factor; an owner move; a fence change) announces nothing: the committed
/// ISR is the dedupe, and no per-commit event can make ISR churn an event storm.
///
/// The same condition moves when the FACTOR moves with the ISR unchanged: raising `confirmation_factor` above
/// `|ISR|` stalls every ack just as a shrink does. [#onConfigPut] derives that from the committed config Put, comparing
/// each partition's committed ISR against the factor this node enforced before and enforces after the Put, so an
/// unchanged factor (a no-op config change, a lowering the node does not adopt) announces nothing.
///
/// An ISR always holds its owner, so a factor of `<= 1` (no confirmation required, or not known on this node) has no
/// minimum to fall below and announces nothing.
///
/// Every event carries a deterministic `eventId` ([StreamEventIds]; the config path keys on the config's `createdAt`
/// and the factor pair as well), so two nodes that both pass the events-owner gate during a membership change publish
/// ONE event as far as every reader that de-duplicates by `eventId` is concerned.
public interface StreamIsrAnnouncer {
    @Contract
    void onOwnershipPut(ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> put);

    /// Derive and route the events, if any, of one committed stream config Put. Must run BEFORE the stream manager
    /// applies the same Put: the factor it enforces BEFORE the Put is the baseline.
    @Contract
    void onConfigPut(ValuePut<StreamConfigKey, StreamConfigValue> put);

    /// `confirmationFactor` is the factor this node enforces for a stream now; `confirmationFactorAfter` the one it will
    /// enforce once a committed config has been applied; `committedRecord` the committed ownership record of a partition.
    static StreamIsrAnnouncer streamIsrAnnouncer(ToIntFunction<String> confirmationFactor,
                                                 ToIntFunction<StreamConfig> confirmationFactorAfter,
                                                 Function<String, Option<StreamConfig>> enforcedConfig,
                                                 BiFunction<String, Integer, Option<StreamPartitionOwnershipValue>> committedRecord,
                                                 Consumer<OperationalEvent> sink) {
        return new StreamIsrAnnouncer() {
            @Override
            @Contract
            public void onOwnershipPut(ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> put) {
                transition(put.cause().key(),
                           put.oldValue(),
                           put.cause().value(),
                           confirmationFactor.applyAsInt(put.cause().key().stream())).onPresent(sink);
            }

            @Override
            @Contract
            public void onConfigPut(ValuePut<StreamConfigKey, StreamConfigValue> put) {
                var value = put.cause()
                               .value();
                var config = value.config();
                var before = confirmationFactor.applyAsInt(config.name());
                var after = confirmationFactorAfter.applyAsInt(config);

                configTransitions(value, before, after, committedRecord).forEach(sink);
                notApplied(value, enforcedConfig.apply(config.name())).onPresent(sink);
            }
        };
    }

    /// The events a committed config of `value` calls for when the enforced factor moves from `before` to `after`: per
    /// partition with a committed ISR, falling below the new factor is a breach and reaching it a restoration. A stream
    /// this node did not hold (`before <= 0`) has no baseline, and an unchanged factor moves nothing.
    static List<OperationalEvent> configTransitions(StreamConfigValue value,
                                                    int before,
                                                    int after,
                                                    BiFunction<String, Integer, Option<StreamPartitionOwnershipValue>> committedRecord) {
        var config = value.config();

        return before <= 0 || before == after
               ? List.of()
               : IntStream.range(0,
                                 config.partitions())
                          .mapToObj(partition -> configTransition(value, partition, before, after, committedRecord))
                          .flatMap(Option::stream)
                          .toList();
    }

    /// A committed config that does not take effect over what the node ENFORCES (a different partition count, or not
    /// stronger durability: `StreamPartitionManager#notAppliedReason`) is an operator-attention condition: the operator
    /// asked for something the system will not do, and the stream keeps its enforced shape. Announced once per committed
    /// Put, compared with what is enforced (not with the previously committed config), so a second unapplied value
    /// announces too; the reason names the actual cause. Silent for an adopted change (including a lowering that came
    /// with a replication-factor raise), an unchanged config, a new life and a stream not held here. A later change does
    /// not resolve it: it is a point event.
    static Option<OperationalEvent> notApplied(StreamConfigValue value, Option<StreamConfig> enforced) {
        var config = value.config();

        return enforced.flatMap(current -> StreamPartitionManager.notAppliedReason(config, current)
                                                                 .map(reason -> OperationalEvent.StreamConfigChangeNotApplied.streamConfigChangeNotApplied(config.name(),
                                                                                                                                                     config.confirmationFactor(),
                                                                                                                                                     current.confirmationFactor(),
                                                                                                                                                     reason,
                                                                                                                                                     "stream-config-not-applied:"
                                                                                                                                                     + config.name()
                                                                                                                                                     + ":" + config.incarnation()
                                                                                                                                                     + ":" + value.createdAt()
                                                                                                                                                     + ":" + config.partitions()
                                                                                                                                                     + ":" + config.replicationFactor()
                                                                                                                                                     + ":" + config.confirmationFactor())));
    }

    private static Option<OperationalEvent> configTransition(StreamConfigValue value,
                                                             int partition,
                                                             int before,
                                                             int after,
                                                             BiFunction<String, Integer, Option<StreamPartitionOwnershipValue>> committedRecord) {
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey(value.config().name(),
                                                                          partition);

        return committedRecord.apply(key.stream(),
                                     partition)
                              .filter(record -> below(record, before) != below(record, after))
                              .map(record -> event(key,
                                                   record,
                                                   after,
                                                   below(record, after),
                                                   configId(value, before, after, key, record)));
    }

    private static String configId(StreamConfigValue value,
                                   int before,
                                   int after,
                                   StreamPartitionOwnershipKey key,
                                   StreamPartitionOwnershipValue record) {
        return StreamEventIds.of("stream-isr-cf:" + value.createdAt() + ":" + before + ">" + after, key, record);
    }

    /// The event a committed change from `before` to `after` calls for: falling below `confirmationFactor` is a
    /// breach, reaching it again a restoration, anything else is silent.
    static Option<OperationalEvent> transition(StreamPartitionOwnershipKey key,
                                               Option<StreamPartitionOwnershipValue> before,
                                               StreamPartitionOwnershipValue after,
                                               int confirmationFactor) {
        var wasBelow = before.map(record -> below(record, confirmationFactor)).or(false);
        var isBelow = below(after, confirmationFactor);

        return wasBelow == isBelow
               ? Option.none()
               : Option.some(event(key,
                                   after,
                                   confirmationFactor,
                                   isBelow,
                                   StreamEventIds.of(isBelow
                                                     ? "stream-isr-below"
                                                     : "stream-isr-restored",
                                                     key,
                                                     after)));
    }

    /// A record minted before #1730 (`isrVersion` 0) carries no committed ISR, so it is never below anything.
    private static boolean below(StreamPartitionOwnershipValue record, int confirmationFactor) {
        return record.isrVersion() > 0 && record.isr()
                                                .size() < confirmationFactor;
    }

    private static OperationalEvent event(StreamPartitionOwnershipKey key,
                                          StreamPartitionOwnershipValue record,
                                          int confirmationFactor,
                                          boolean below,
                                          String eventId) {
        var isr = ids(record.isr());
        var fenced = ids(record.fenced());

        return below
               ? OperationalEvent.StreamIsrBelowMinimum.streamIsrBelowMinimum(key.stream(),
                                                                              key.partition(),
                                                                              record.owner().id(),
                                                                              isr,
                                                                              fenced,
                                                                              confirmationFactor,
                                                                              eventId)
               : OperationalEvent.StreamIsrRestored.streamIsrRestored(key.stream(),
                                                                      key.partition(),
                                                                      record.owner().id(),
                                                                      isr,
                                                                      fenced,
                                                                      confirmationFactor,
                                                                      eventId);
    }

    private static List<String> ids(List<NodeId> nodes) {
        return nodes.stream()
                    .map(NodeId::id)
                    .toList();
    }
}
