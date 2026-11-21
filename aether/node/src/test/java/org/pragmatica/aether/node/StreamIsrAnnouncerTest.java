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
import org.pragmatica.aether.slice.HotWindowDeclaration;
import org.pragmatica.aether.slice.RetentionDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1883 (owner rule): the in-sync set falling below the confirmation factor, and its return, are announced on the
/// TRANSITION and only then. A commit that leaves the condition unchanged announces nothing, so ISR churn can never
/// become an event storm, and the ordinary paths (a CF-1 stream, an ISR that stays at the factor, an owner move) raise
/// no alert.
class StreamIsrAnnouncerTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final StreamPartitionOwnershipKey KEY = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 3);

    @Test
    void isrFallsBelowTheFactor_isAnnouncedOnce_withTheFencedMembers() {
        var before = record(List.of(A, B), 4L);
        var after = before.withIsrAndFenced(List.of(A), List.of(B));

        var event = StreamIsrAnnouncer.transition(KEY, Option.some(before), after, 2).unwrap();

        assertThat(event).isInstanceOfSatisfying(OperationalEvent.StreamIsrBelowMinimum.class, below -> {
            assertThat(below.stream()).isEqualTo("orders");
            assertThat(below.partition()).isEqualTo(3);
            assertThat(below.isr()).containsExactly("node-a");
            assertThat(below.fenced()).containsExactly("node-b");
            assertThat(below.confirmationFactor()).isEqualTo(2);
        });
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(after), after.withFailoverRefused(true), 2).isEmpty())
            .as("a further commit that stays below the factor announces nothing")
            .isTrue();
    }

    @Test
    void isrReachesTheFactorAgain_isAnnouncedRestored_once() {
        var below = record(List.of(A), 5L);
        var restored = below.withIsr(List.of(A, B));

        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(below), restored, 2).unwrap())
            .isInstanceOf(OperationalEvent.StreamIsrRestored.class);
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(restored), restored.withIsr(List.of(A, B, C)), 2).isEmpty())
            .as("growing past the factor announces nothing")
            .isTrue();
    }

    /// The false-alert controls: none of the ordinary commits may raise anything.
    @Test
    void ordinaryCommits_announceNothing() {
        var healthy = record(List.of(A, B, C), 4L);

        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), healthy.withIsr(List.of(A, B)), 2).isEmpty())
            .as("shrink that stays at the factor").isTrue();
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), healthy.withIsr(List.of(A)), 1).isEmpty())
            .as("CF 1 requires no confirmation: no minimum to fall below").isTrue();
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), healthy.withIsr(List.of(A)), 0).isEmpty())
            .as("factor not known on this node").isTrue();
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), healthy.withIsrAndFenced(List.of(A, B, C), List.of()), 2).isEmpty())
            .as("a fence change that keeps the ISR").isTrue();
    }

    /// A record minted before #1730 carries no committed ISR (`isrVersion` 0): it is not "below" anything.
    @Test
    void recordWithoutACommittedIsr_isNeverBelow() {
        var legacy = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(A, Epoch.ZERO, 1L, HlcTimestamp.ZERO);

        assertThat(StreamIsrAnnouncer.transition(KEY, Option.none(), legacy, 2).isEmpty()).isTrue();
    }

    /// The first committed record of a partition starting below the factor is itself the transition into the condition.
    @Test
    void firstRecordBelowTheFactor_isAnnounced() {
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.none(), record(List.of(A), 1L), 2).unwrap())
            .isInstanceOf(OperationalEvent.StreamIsrBelowMinimum.class);
    }

    /// #1883 F3: the id is a pure function of the committed record, so two nodes that both pass the events-owner gate
    /// publish the SAME `eventId`; a different ISR version is a different transition and a different id.
    @Test
    void eventId_isDeterministicOverTheCommittedPut_andDiffersAcrossIsrVersions() {
        var healthy = record(List.of(A, B), 4L);
        var below = healthy.withIsrAndFenced(List.of(A), List.of(B));
        var restored = below.withIsr(List.of(A, B));
        var belowAgain = restored.withIsrAndFenced(List.of(A), List.of(B));

        var onNodeOne = idOf(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), below, 2).unwrap());
        var onNodeTwo = idOf(StreamIsrAnnouncer.transition(KEY, Option.some(healthy), below, 2).unwrap());
        var secondBreach = idOf(StreamIsrAnnouncer.transition(KEY, Option.some(restored), belowAgain, 2).unwrap());
        var restoration = idOf(StreamIsrAnnouncer.transition(KEY, Option.some(below), restored, 2).unwrap());

        assertThat(onNodeOne).as("two derivations of one committed Put").isEqualTo(onNodeTwo).isNotBlank();
        assertThat(secondBreach).as("the same breach again, one ISR version later").isNotEqualTo(onNodeOne);
        assertThat(restoration).as("the restoration is not the breach").isNotEqualTo(onNodeOne);
    }

    /// #1883 F4: a factor raised above |ISR| stalls acks with no ISR commit at all; it is announced once, on every node
    /// from the same committed Put, and the same raise derived twice carries the same id.
    @Test
    void factorRaisedAboveTheIsr_isAnnouncedBelowOnce_withADeterministicId() {
        var announced = new ArrayList<OperationalEvent>();
        var announcer = configAnnouncer(2, 3, announced);
        var put = configPut(config(2, 3), 111L);

        announcer.onConfigPut(put);

        assertThat(announced).singleElement().isInstanceOfSatisfying(OperationalEvent.StreamIsrBelowMinimum.class, below -> {
            assertThat(below.stream()).isEqualTo("orders");
            assertThat(below.partition()).isEqualTo(3);
            assertThat(below.isr()).containsExactly("node-a", "node-b");
            assertThat(below.confirmationFactor()).isEqualTo(3);
        });
        var again = new ArrayList<OperationalEvent>();

        configAnnouncer(2, 3, again).onConfigPut(put);

        assertThat(idOf(again.getFirst())).as("another node deriving the same Put").isEqualTo(idOf(announced.getFirst()));

        var laterChange = new ArrayList<OperationalEvent>();

        configAnnouncer(2, 3, laterChange).onConfigPut(configPut(config(2, 3), 112L));

        assertThat(idOf(laterChange.getFirst())).as("a later config change is a different event").isNotEqualTo(idOf(announced.getFirst()));
    }

    @Test
    void factorMovedBackToTheIsr_isAnnouncedRestoredOnce() {
        var announced = new ArrayList<OperationalEvent>();

        configAnnouncer(3, 2, announced).onConfigPut(configPut(config(3, 2), 222L));

        assertThat(announced).singleElement().isInstanceOf(OperationalEvent.StreamIsrRestored.class);
    }

    /// The false-alert controls of the config path: a config change that moves no condition raises nothing.
    @Test
    void configChange_thatMovesNoCondition_announcesNothing() {
        var announced = new ArrayList<OperationalEvent>();

        configAnnouncer(2, 2, announced).onConfigPut(configPut(config(3, 2), 1L));
        configAnnouncer(2, 1, announced).onConfigPut(configPut(config(3, 1), 2L));
        configAnnouncer(1, 2, announced).onConfigPut(configPut(config(3, 2), 3L));
        configAnnouncer(0, 3, announced).onConfigPut(configPut(config(3, 3), 4L));

        assertThat(announced).as("unchanged factor; a CF-1 stream (no minimum); both factors <= |ISR|; a stream not held before").isEmpty();
    }

    @Test
    void configChange_skipsPartitionsWithoutACommittedIsr() {
        var legacy = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(A, Epoch.ZERO, 1L, HlcTimestamp.ZERO);

        assertThat(StreamIsrAnnouncer.configTransitions(StreamConfigValue.streamConfigValue(config(2, 3), 5L),
                                                        2,
                                                        3,
                                                        (_, _) -> Option.some(legacy))).as("isrVersion 0: no committed ISR").isEmpty();
        assertThat(StreamIsrAnnouncer.configTransitions(StreamConfigValue.streamConfigValue(config(2, 3), 5L),
                                                        2,
                                                        3,
                                                        (_, _) -> Option.none())).as("no committed record").isEmpty();
    }

    /// #1883 (owner rule): a committed config that does not take effect over what the node ENFORCES is announced once
    /// per committed Put, compared with the enforced config, with the actual cause as the reason.
    @Test
    void loweringThatIsNotApplied_isAnnouncedOnce_withADeterministicId() {
        var announced = new ArrayList<OperationalEvent>();
        var put = configPut(config(3, 1), 300L);

        enforcing(config(3, 3), announced).onConfigPut(put);

        assertThat(notApplied(announced)).singleElement().satisfies(event -> {
            assertThat(event.stream()).isEqualTo("orders");
            assertThat(event.requestedConfirmationFactor()).isEqualTo(1);
            assertThat(event.effectiveConfirmationFactor()).isEqualTo(3);
            assertThat(event.reason()).startsWith("durability only increases online");
        });
        var again = new ArrayList<OperationalEvent>();
        var later = new ArrayList<OperationalEvent>();

        enforcing(config(3, 3), again).onConfigPut(put);
        enforcing(config(3, 3), later).onConfigPut(configPut(config(3, 1), 301L));

        assertThat(idOf(again.getFirst())).as("another node, or a redelivery of the same Put").isEqualTo(idOf(announced.getFirst()));
        assertThat(idOf(later.getFirst())).as("a later lowering request").isNotEqualTo(idOf(announced.getFirst()));
    }

    /// 3 -> 1 announces, and a later commit to 2 announces too: the enforced factor is still 3, and 2 is not applied
    /// either. The second event names ITS requested value, not the first one's.
    @Test
    void secondUnappliedValue_isAnnouncedToo_comparedWithTheEnforcedConfig() {
        var announced = new ArrayList<OperationalEvent>();
        var announcer = enforcing(config(3, 3), announced);

        announcer.onConfigPut(configPut(config(3, 1), 1L));
        announcer.onConfigPut(configPut(config(3, 2), 2L));

        assertThat(notApplied(announced)).extracting(OperationalEvent.StreamConfigChangeNotApplied::requestedConfirmationFactor)
                                         .containsExactly(1, 2);
        assertThat(notApplied(announced)).extracting(OperationalEvent.StreamConfigChangeNotApplied::effectiveConfirmationFactor)
                                         .containsExactly(3, 3);
    }

    /// A different partition count is never adopted, up or down, and the reason says so rather than blaming durability.
    @Test
    void partitionCountChange_isAnnouncedNotApplied_upAndDown_withThePartitionReason() {
        var lowered = new ArrayList<OperationalEvent>();
        var raised = new ArrayList<OperationalEvent>();

        enforcing(config(3, 2), lowered).onConfigPut(configPut(config(2, 3, 2), 1L));
        enforcing(config(3, 2), raised).onConfigPut(configPut(config(8, 5, 3), 2L));

        assertThat(notApplied(lowered)).singleElement().satisfies(event -> {
            assertThat(event.reason()).startsWith("partition count of an existing stream cannot change");
            assertThat(event.reason()).doesNotContain("durability");
        });
        assertThat(notApplied(raised)).as("a RAISE (even with stronger durability) is not applied either").singleElement()
                                      .satisfies(event -> assertThat(event.reason()).startsWith("partition count of an existing stream cannot change"));
    }

    /// The false-alert controls: an adopted change (a replication-factor raise at unchanged confirmation factor), an
    /// unchanged config, a raise, a new life and a stream not held here announce nothing.
    @Test
    void configChange_thatIsAdoptedOrUnchanged_announcesNoNotApplied() {
        var announced = new ArrayList<OperationalEvent>();

        enforcing(config(3, 3), announced).onConfigPut(configPut(config(5, 3), 1L));
        enforcing(config(3, 3), announced).onConfigPut(configPut(config(3, 3), 2L));
        enforcing(config(3, 2), announced).onConfigPut(configPut(config(3, 3), 3L));
        enforcing(config(3, 3), announced).onConfigPut(configPut(config(3, 1).withIncarnation(9L), 4L));
        configAnnouncer(0, 0, announced).onConfigPut(configPut(config(3, 1), 5L));

        assertThat(notApplied(announced)).as("adopted with an RF raise; unchanged; a raise; another life; not held").isEmpty();
    }

    private static final long GIB = 1024L * 1024L * 1024L;

    /// #3334, #3554: an adopted committed change of the declared retention is announced ONCE per cluster, not once per
    /// partition, with the previously committed and the new bounds as text. Its id is a pure function of committed data
    /// (the Put and its prior committed value): what the node enforces moves neither, another Put or another bound pair
    /// differs.
    @Test
    void retentionChange_isAnnouncedOnce_withTheBounds_andADeterministicId() {
        var announced = new ArrayList<OperationalEvent>();
        var put = changePut(declaring(2 * GIB), declaring(GIB), 400L);

        enforcing(declaring(2 * GIB), announced).onConfigPut(put);

        assertThat(retentionChanges(announced)).as("one event for a stream of 4 partitions").singleElement().satisfies(event -> {
            assertThat(event.stream()).isEqualTo("orders");
            assertThat(event.oldBounds()).isEqualTo(boundsOf(2 * GIB));
            assertThat(event.newBounds()).isEqualTo(boundsOf(GIB));
        });
        var otherEnforced = new ArrayList<OperationalEvent>();
        var notHeld = new ArrayList<OperationalEvent>();
        var laterPut = new ArrayList<OperationalEvent>();
        var otherPair = new ArrayList<OperationalEvent>();

        enforcing(declaring(3 * GIB), otherEnforced).onConfigPut(put);
        configAnnouncer(0, 0, notHeld).onConfigPut(put);
        enforcing(declaring(2 * GIB), laterPut).onConfigPut(changePut(declaring(2 * GIB), declaring(GIB), 401L));
        enforcing(declaring(2 * GIB), otherPair).onConfigPut(changePut(declaring(3 * GIB), declaring(GIB), 400L));

        assertThat(idOf(retentionChanges(otherEnforced).getFirst())).as("a node enforcing something else")
                                                                    .isEqualTo(idOf(announced.getFirst()));
        assertThat(idOf(retentionChanges(notHeld).getFirst())).as("a node not holding the stream")
                                                              .isEqualTo(idOf(announced.getFirst()));
        assertThat(idOf(laterPut.getFirst())).as("another Put (createdAt)").isNotEqualTo(idOf(announced.getFirst()));
        assertThat(idOf(otherPair.getFirst())).as("another old bound").isNotEqualTo(idOf(announced.getFirst()));
    }

    /// #3554 (c): the id is exactly prefix:name:incarnation:createdAt:oldBounds:newBounds. Each part is asserted, so
    /// dropping or changing any one of them goes red here.
    @Test
    void retentionChangeId_isComposedOfEveryPart_inOrder() {
        var announced = new ArrayList<OperationalEvent>();

        configAnnouncer(0, 0, announced).onConfigPut(changePut(declaring(2 * GIB).withIncarnation(7L),
                                                               declaring(GIB).withIncarnation(7L),
                                                               4242L));

        assertThat(retentionChanges(announced)).singleElement()
                                               .satisfies(event -> assertThat(event.eventId()).isEqualTo("stream-retention-changed:orders:7:4242:"
                                                                                                         + boundsOf(2 * GIB)
                                                                                                         + ":"
                                                                                                         + boundsOf(GIB)));
    }

    /// The false-alert controls: an unchanged declaration, a first commit (no previous committed value), a new life and
    /// an adopted change of another storage key (the hot window) announce nothing.
    @Test
    void retentionChange_thatChangesNothing_orIsNotARetentionChange_announcesNothing() {
        var announced = new ArrayList<OperationalEvent>();
        var hotChanged = declaring(GIB).withDeclaration(StreamDeclaration.streamDeclaration(retentionSection(GIB),
                                                                                            Option.none(),
                                                                                            Option.some(HotWindowDeclaration.hotWindowDeclaration(Option.some(1L),
                                                                                                                                                  Option.none())),
                                                                                            Option.none()));

        configAnnouncer(0, 0, announced).onConfigPut(changePut(declaring(GIB), declaring(GIB), 1L));
        configAnnouncer(0, 0, announced).onConfigPut(configPut(declaring(GIB), 2L));
        configAnnouncer(0, 0, announced).onConfigPut(changePut(declaring(2 * GIB), declaring(GIB).withIncarnation(9L), 3L));
        configAnnouncer(0, 0, announced).onConfigPut(changePut(declaring(GIB), hotChanged, 4L));

        assertThat(StreamPartitionManager.adopts(hotChanged, declaring(GIB))).as("premise: the hot-window change is adopted").isTrue();
        assertThat(retentionChanges(announced)).as("unchanged; first commit; another life; hot window only").isEmpty();
    }

    /// #3554 F1: a Put the manager does not adopt (a lowered confirmation factor, another partition count, a truncation
    /// change) raises no retention event, though its retention section differs. Mutant: drop the adoption filter.
    @Test
    void retentionChange_thatIsNotAdopted_announcesNothing() {
        var announced = new ArrayList<OperationalEvent>();
        var truncated = declaring(GIB).withDeclaration(StreamDeclaration.streamDeclaration(retentionSection(GIB),
                                                                                           Option.some(TruncationDeclaration.truncationDeclaration(Option.none(),
                                                                                                                                                  Option.some(60_000L))),
                                                                                           Option.none(),
                                                                                           Option.none()));
        var lowered = declaring(GIB).withReplication(new ReplicationFactors(3, 2));
        var repartitioned = declaring(GIB).withPartitions(8);

        configAnnouncer(0, 0, announced).onConfigPut(changePut(declaring(2 * GIB), lowered, 1L));
        configAnnouncer(0, 0, announced).onConfigPut(changePut(declaring(2 * GIB), repartitioned, 2L));
        configAnnouncer(0, 0, announced).onConfigPut(changePut(declaring(2 * GIB), truncated, 3L));

        assertThat(retentionChanges(announced)).as("lowered factor; partition count; truncation").isEmpty();
    }

    /// A redelivered Put (a rejoin replaying it) derives the same event with the same id, whatever the node enforces by
    /// then: a reader that de-duplicates by `eventId` sees one event.
    @Test
    void replayedPut_derivesTheSameId_whateverTheNodeEnforces() {
        var original = new ArrayList<OperationalEvent>();
        var replayed = new ArrayList<OperationalEvent>();
        var put = changePut(declaring(2 * GIB), declaring(GIB), 500L);

        enforcing(declaring(2 * GIB), original).onConfigPut(put);
        enforcing(declaring(GIB), replayed).onConfigPut(put);

        assertThat(idOf(retentionChanges(replayed).getFirst())).isEqualTo(idOf(retentionChanges(original).getFirst()));
    }

    /// The bounds text is exactly the rendered retention section of the declaration, nothing of the rest.
    private static String boundsOf(long maxBytes) {
        return String.valueOf(retentionSection(maxBytes));
    }

    private static Option<RetentionDeclaration> retentionSection(long maxBytes) {
        return Option.some(RetentionDeclaration.retentionDeclaration(Option.none(), Option.some(maxBytes), Option.none(), Option.none()));
    }

    private static List<OperationalEvent.StreamRetentionChanged> retentionChanges(List<OperationalEvent> events) {
        return events.stream()
                     .filter(OperationalEvent.StreamRetentionChanged.class::isInstance)
                     .map(OperationalEvent.StreamRetentionChanged.class::cast)
                     .toList();
    }

    /// The policy carries the declared `max_bytes` as the deploy path does, so the manager's rule adopts a bounds change.
    private static StreamConfig declaring(long maxBytes) {
        return StreamConfig.streamConfig("orders", 4, RetentionPolicy.retentionPolicy(1000, maxBytes, 600_000), "earliest")
                           .withReplication(new ReplicationFactors(3, 3))
                           .withDeclaration(StreamDeclaration.streamDeclaration(retentionSection(maxBytes),
                                                                                Option.none(),
                                                                                Option.none(),
                                                                                Option.none()));
    }

    /// A committed Put of `next` whose prior committed value is `previous`.
    private static ValuePut<StreamConfigKey, StreamConfigValue> changePut(StreamConfig previous, StreamConfig next, long createdAt) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey("orders"),
                                                  StreamConfigValue.streamConfigValue(next, createdAt)),
                              Option.some(StreamConfigValue.streamConfigValue(previous, createdAt - 1)));
    }

    private static List<OperationalEvent.StreamConfigChangeNotApplied> notApplied(List<OperationalEvent> events) {
        return events.stream()
                     .filter(OperationalEvent.StreamConfigChangeNotApplied.class::isInstance)
                     .map(OperationalEvent.StreamConfigChangeNotApplied.class::cast)
                     .toList();
    }

    /// An announcer whose node enforces `enforced` for the stream.
    private static StreamIsrAnnouncer enforcing(StreamConfig enforced, List<OperationalEvent> sink) {
        return StreamIsrAnnouncer.streamIsrAnnouncer(_ -> enforced.confirmationFactor(),
                                                     _ -> enforced.confirmationFactor(),
                                                     _ -> Option.some(enforced),
                                                     (_, _) -> Option.none(),
                                                     sink::add);
    }

    private static String idOf(OperationalEvent event) {
        return switch (event) {
            case OperationalEvent.StreamIsrBelowMinimum below -> below.eventId();
            case OperationalEvent.StreamIsrRestored restored -> restored.eventId();
            case OperationalEvent.StreamConfigChangeNotApplied notApplied -> notApplied.eventId();
            case OperationalEvent.StreamRetentionChanged retention -> retention.eventId();
            default -> throw new AssertionError("unexpected " + event);
        };
    }

    /// The partition 3 holds ISR {A, B}; the factor moves from `before` to `after` with this Put.
    private static StreamIsrAnnouncer configAnnouncer(int before, int after, List<OperationalEvent> sink) {
        return StreamIsrAnnouncer.streamIsrAnnouncer(_ -> before,
                                                     _ -> after,
                                                     _ -> Option.none(),
                                                     (stream, partition) -> partition == 3
                                                                            ? Option.some(record(List.of(A, B), 4L))
                                                                            : Option.none(),
                                                     sink::add);
    }

    private static StreamConfig config(int replicationFactor, int confirmationFactor) {
        return config(4, replicationFactor, confirmationFactor);
    }

    private static StreamConfig config(int partitions, int replicationFactor, int confirmationFactor) {
        return StreamConfig.streamConfig("orders", partitions, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest")
                           .withReplication(new ReplicationFactors(replicationFactor, confirmationFactor));
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config, long createdAt) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey("orders"),
                                                  StreamConfigValue.streamConfigValue(config, createdAt)),
                              Option.none());
    }

    private static StreamPartitionOwnershipValue record(List<NodeId> isr, long isrVersion) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(A,
                                                                           Epoch.epoch(1L, 2L, 0L).withCounter(3L),
                                                                           3L,
                                                                           HlcTimestamp.ZERO,
                                                                           isr,
                                                                           isrVersion);
    }
}
