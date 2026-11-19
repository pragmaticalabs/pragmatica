// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.cluster.metrics.HeldReport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;


/// The leader's records of a departure hold (#3345, D45-6): the single writer of every record of a held drain episode.
///
/// - **Episodes.** State is per (node, episode): OPEN -> CLOSED(DELIVERED | LOSS_ACCEPTED | DEATH | SUPERSEDED); CLOSED is terminal
///   and remembered, so a later report of a closed (or older) episode is ignored (H1). A report of a NEWER episode of the node
///   closes the open one SUPERSEDED and opens the new one (H2). The rows are in [Drainer].
/// - **Latch (R2).** HELD is latched while an episode is OPEN, opened by its first held report, and is never read back from a live
///   answer: a report that is absent (the drainer missing from the view, its FAN entry swept, a restart under a new boot token)
///   changes nothing. The latch is cleared by a delivery report, a landed loss acceptance, the drainer's death ([#onDead]) or a
///   newer episode of the same drainer.
/// - **Declared former holder (R1(a)).** The latched episode keeps its last report past the FAN sweep ([#lastReport]).
/// - **Warning.** `dht-departure-sole-copy-at-risk`, subject the episode, raised once the report is reportable (at once when the count
///   is unknown or undeliverable chunks exist, else after `reportAfterMs`) and re-raised on every [#onPong] / [#tick] until it lands.
/// - **Closers.** A report that ended `DELIVERED` closes with `dht-departure-sole-copy-delivered`, one that ended `LOSS_ACCEPTED` and a
///   death while held with `dht-departure-hold-ended`, always through [WarningRaiser#raiseClosing] (a new leader may never have seen
///   the warning open) and re-raised until landed; once landed the drainer is told `EndRecorded` ([#decisionFor]).
/// - **Override.** [#acceptLoss] records `dht-departure-loss-accepted` first and relays `AcceptLoss` only once that record landed.
///
/// A new leader seeds its latches from the cluster event log ([#seedFromLog]). Inert until something drives it: the caller (the
/// leader's pong path) calls [#onPong], [#tick], [#onLeft] and [#onDead] only while this node is the leader.
public interface HoldRecords {
    /// Default delay before an enumerated, encodable hold becomes reportable.
    long REPORT_AFTER_MS = 15_000L;

    /// The FAN's view of the latest held report a node sent (`ClusterSyncPongSignalFan#latestHeldReport`).
    @FunctionalInterface
    interface HeldReports {
        Option<HeldReport> latestHeldReport(NodeId nodeId);
    }

    /// The event layer's awaited raises (`ClusterEventAggregator#raiseLanded`, `#raiseClosing`): `true` only when the event landed.
    interface WarningRaiser {
        Promise<Boolean> raiseLanded(OperatorWarning warning);
        Promise<Boolean> raiseClosing(OperatorWarning warning, OperatorWarning closer);
    }

    /// The retained cluster event log (`ClusterEventAggregator#events`).
    @FunctionalInterface
    interface LandedEvents {
        Promise<List<ClusterEvent>> events();
    }

    /// What the operator confirmed: the owed digest of an enumerated inventory, or an inventory not enumerated.
    sealed interface LossConfirmation {
        record Digest(String value) implements LossConfirmation {
            public static Digest digest(String value) {
                return new Digest(value);
            }
        }

        enum InventoryUnknown implements LossConfirmation {
            INVENTORY_UNKNOWN
        }

        /// The form as the CLI spells it.
        default String render() {
            return switch (this) {
                case Digest digest -> "--digest " + digest.value();
                case InventoryUnknown _ -> "--inventory-unknown";
            };
        }
    }

    /// A decision the leader relays to the drainer on the ping (`holdDecisions[id]`).
    sealed interface HoldDecision {
        String episode();

        record AcceptLoss(String episode, LossConfirmation confirmation) implements HoldDecision {
            public static AcceptLoss acceptLoss(String episode, LossConfirmation confirmation) {
                return new AcceptLoss(episode, confirmation);
            }
        }

        record EndRecorded(String episode) implements HoldDecision {
            public static EndRecorded endRecorded(String episode) {
                return new EndRecorded(episode);
            }
        }
    }

    /// The landed loss-acceptance record: the episode, the confirmation and the report it was checked against.
    record LossAccepted(NodeId nodeId, String episode, LossConfirmation confirmation, HeldReport report) {
        public static LossAccepted lossAccepted(NodeId nodeId,
                                                String episode,
                                                LossConfirmation confirmation,
                                                HeldReport report) {
            return new LossAccepted(nodeId, episode, confirmation, report);
        }
    }

    /// Why [#acceptLoss] refused: every refusal but [RecordNotLanded] is a conflict with the current hold (409);
    /// [RecordNotLanded] means the record did not land and nothing was relayed (503).
    sealed interface HoldRefusal extends Cause, HttpStatusAware {
        @Override
        default HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }

        record NotHeld(NodeId nodeId, String message) implements HoldRefusal {
            static final Fn1<NotHeld, NodeId> FACTORY = Causes.forOneValue("Node %s is not held", NotHeld::new);
        }

        record Ended(String episode, String message) implements HoldRefusal {
            static final Fn1<Ended, String> FACTORY = Causes.forOneValue("Hold of episode %s has ended", Ended::new);
        }

        record OtherEpisode(String requested, HeldReport current, String message) implements HoldRefusal {
            static final Fn2<OtherEpisode, String, HeldReport> FACTORY = Causes.forTwoValues("Episode %s is not the held episode; current report: %s",
                                                                                             OtherEpisode::new);
        }

        record StaleDigest(String requested, HeldReport current, String message) implements HoldRefusal {
            static final Fn2<StaleDigest, String, HeldReport> FACTORY = Causes.forTwoValues("Confirmation %s does not match the current report: %s",
                                                                                            StaleDigest::new);
        }

        record AlreadyAccepted(String episode, String message) implements HoldRefusal {
            static final Fn1<AlreadyAccepted, String> FACTORY = Causes.forOneValue("Loss of episode %s is already accepted or being recorded",
                                                                                   AlreadyAccepted::new);
        }

        record RecordNotLanded(String episode, String message) implements HoldRefusal {
            static final Fn1<RecordNotLanded, String> FACTORY = Causes.forOneValue("The loss-accepted record of episode %s did not land; nothing was relayed, retry",
                                                                                   RecordNotLanded::new);

            @Override
            public HttpStatus httpStatus() {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }
    }

    /// A pong from `nodeId` reached the leader: take its latest held report (absence changes nothing), then raise what is due.
    @Contract
    void onPong(NodeId nodeId);

    /// Periodic pass over every recorded hold: as [#onPong] for each, so raises that did not land are retried while the drainer is
    /// silent.
    @Contract
    void tick();

    /// The drainer left the view cleanly (LEAVE edge): nothing is relayed or reported once every closer landed; the latch is not
    /// cleared.
    @Contract
    void onLeft(NodeId nodeId);

    /// Failure detection declared the drainer dead (DEAD edge): the open episode closes from its last report with
    /// `dht-departure-hold-ended`; an episode already closed keeps its closer.
    @Contract
    void onDead(NodeId nodeId);

    /// New leader: latch every episode whose `dht-departure-sole-copy-at-risk` landed in the retained log with no closer of the same
    /// subject. Completes with the number of latches seeded; fails when the log cannot be read (then latches come from reports only).
    Promise<Integer> seedFromLog();
    /// The operator override: take the FAN's latest report into the record, check `episode` and `confirmation` against the
    /// record's last report of the current episode (kept past the FAN sweep, H3), record `dht-departure-loss-accepted`, and only
    /// once it landed relay `AcceptLoss`. Fails with a [HoldRefusal]: a record raise that failed or did not land is
    /// [HoldRefusal.RecordNotLanded] and releases the acceptance (H4); a delivery that arrived first is [HoldRefusal.Ended] (H5).
    Promise<LossAccepted> acceptLoss(NodeId nodeId, String episode, LossConfirmation confirmation);
    /// The decision to relay to `nodeId` on the ping, for its current episode: `EndRecorded` once the closer landed, else
    /// `AcceptLoss` once its record landed and until the drainer reports its end; none otherwise (H5).
    Option<HoldDecision> decisionFor(NodeId nodeId);
    /// Whether an episode of `nodeId` is OPEN (latched HELD). Never read from a live answer.
    ///
    /// Seed gaps: a new leader learns open episodes from the retained event log ([#seedFromLog]). When the at-risk event was
    /// evicted from the log's retention, or the log partition has a new owner whose log does not carry it, nothing is seeded and
    /// this answers `false` until the drainer's first held pong reaches this leader.
    boolean heldLatched(NodeId nodeId);
    /// The last held report of the current episode, kept past the FAN sweep; none once the drainer departed and every closer
    /// landed.
    Option<HeldReport> lastReport(NodeId nodeId);

    /// `clock` is a monotonic millisecond source (H6), for example `System.nanoTime()` in milliseconds; a read earlier than an
    /// episode's first sighting is taken as the `reportAfterMs` delay having passed.
    static HoldRecords holdRecords(HeldReports reports,
                                   WarningRaiser raiser,
                                   LandedEvents log,
                                   LongSupplier clock,
                                   long reportAfterMs) {
        record holdRecords(HeldReports reports,
                           WarningRaiser raiser,
                           LandedEvents log,
                           LongSupplier clock,
                           long reportAfterMs,
                           Map<NodeId, Drainer> drainers) implements HoldRecords {
            @Override
            @Contract
            public void onPong(NodeId nodeId) {
                refresh(nodeId);
                raiseDue(nodeId);
            }

            @Override
            @Contract
            public void tick() {
                recordedIds().forEach(this::onPong);
            }

            @Override
            @Contract
            public void onLeft(NodeId nodeId) {
                update(nodeId, Drainer::left);
                raiseDue(nodeId);
            }

            @Override
            @Contract
            public void onDead(NodeId nodeId) {
                update(nodeId, Drainer::died);
                raiseDue(nodeId);
            }

            @Override
            public Promise<Integer> seedFromLog() {
                return log.events()
                          .map(this::seed);
            }

            @Override
            public Promise<LossAccepted> acceptLoss(NodeId nodeId, String episode, LossConfirmation confirmation) {
                return claimAcceptance(nodeId, episode, confirmation).async()
                                      .flatMap(this::recordAcceptance);
            }

            @Override
            public Option<HoldDecision> decisionFor(NodeId nodeId) {
                return drainer(nodeId).flatMap(Drainer::decision);
            }

            @Override
            public boolean heldLatched(NodeId nodeId) {
                return drainer(nodeId).filter(Drainer::latched)
                              .isPresent();
            }

            @Override
            public Option<HeldReport> lastReport(NodeId nodeId) {
                return drainer(nodeId).flatMap(Drainer::lastReport);
            }

            private Option<Drainer> drainer(NodeId nodeId) {
                synchronized (drainers) {
                    return Option.option(drainers.get(nodeId));
                }
            }

            private List<NodeId> recordedIds() {
                synchronized (drainers) {
                    return List.copyOf(drainers.keySet());
                }
            }

            /// Take the FAN's latest held report of `nodeId` into its record (absence changes nothing). The port is read outside
            /// the lock; the order of two reports is settled under it by their own asOf (H6).
            @Contract
            private void refresh(NodeId nodeId) {
                reports.latestHeldReport(nodeId).onPresent(report -> observe(nodeId, report));
            }

            @Contract
            private void observe(NodeId nodeId, HeldReport report) {
                var now = clock.getAsLong();

                synchronized (drainers) {
                    drainers.put(nodeId, known(nodeId).reported(report, now));
                }
            }

            /// The record of `nodeId`, or an empty one; called under the lock.
            private Drainer known(NodeId nodeId) {
                return Option.option(drainers.get(nodeId)).or(() -> Drainer.unseen(nodeId));
            }

            /// Apply `change` to the record of `nodeId`, if any. A finished record stays as the tombstone of its closed episode.
            @Contract
            private void update(NodeId nodeId, Fn1<Drainer, Drainer> change) {
                synchronized (drainers) {
                    Option.option(drainers.get(nodeId)).onPresent(current -> drainers.put(nodeId, change.apply(current)));
                }
            }

            /// Claim the raises due for `nodeId` (marking them in flight) and perform them outside the lock: the event layer's
            /// completion may call back into this unit on another thread.
            @Contract
            private void raiseDue(NodeId nodeId) {
                claimRaises(nodeId).forEach(this::perform);
            }

            private List<Raise> claimRaises(NodeId nodeId) {
                var now = clock.getAsLong();

                synchronized (drainers) {
                    var raises = Option.option(drainers.get(nodeId))
                                       .map(current -> current.dueRaises(now, reportAfterMs))
                                       .or(List.of());

                    raises.forEach(raise -> update(nodeId,
                                                   current -> current.changed(raise.episode(),
                                                                              episode -> episode.raising(true))));

                    return raises;
                }
            }

            @Contract
            private void perform(Raise raise) {
                raiseOf(raise).onResult(result -> settleRaise(raise, result.or(false)));
            }

            private Promise<Boolean> raiseOf(Raise raise) {
                return raise.closer()
                            .fold(() -> raiser.raiseLanded(raise.warning()),
                                  closer -> raiser.raiseClosing(raise.warning(),
                                                                closer));
            }

            /// A raise that did not land only clears the in-flight mark: the next pong or tick raises it again (re-raised until landed).
            @Contract
            private void settleRaise(Raise raise, boolean landed) {
                update(raise.nodeId(),
                       current -> current.changed(raise.episode(), episode -> episode.settled(raise, landed)));
            }

            private int seed(List<ClusterEvent> events) {
                var now = clock.getAsLong();
                var open = Drainer.openEpisodes(events);

                synchronized (drainers) {
                    return (int) open.stream()
                                     .filter(episode -> learn(episode, now))
                                     .count();
                }
            }

            /// Learn one open episode from the log; true when it latched an episode this leader did not know. Called under the lock.
            private boolean learn(String episode, long now) {
                return Drainer.episodeNode(episode)
                              .map(nodeId -> learnFor(nodeId, episode, now))
                              .or(false);
            }

            private boolean learnFor(NodeId nodeId, String episode, long now) {
                var before = known(nodeId);
                var after = before.learned(episode, now);

                drainers.put(nodeId, after);

                return before.find(episode)
                             .isEmpty() && after.find(episode)
                                                .filter(Episode::open)
                                                .isPresent();
            }

            /// Check the override against the record (refreshed from the FAN first, kept past its sweep: H3) and mark it in flight.
            private Result<LossAccepted> claimAcceptance(NodeId nodeId, String episode, LossConfirmation confirmation) {
                refresh(nodeId);
                synchronized (drainers) {
                    return Option.option(drainers.get(nodeId))
                                 .flatMap(Drainer::current)
                                 .toResult(HoldRefusal.NotHeld.FACTORY.apply(nodeId))
                                 .flatMap(current -> current.acceptable(nodeId, episode, confirmation))
                                 .onSuccess(_ -> update(nodeId,
                                                        current -> current.changed(episode,
                                                                                   held -> held.accepting(true))));
                }
            }

            /// Record first: the decision becomes visible only once `dht-departure-loss-accepted` landed. FER: a raise that FAILED
            /// is read as not landed (H4), so the caller gets "record not landed" and the acceptance is released for a retry.
            private Promise<LossAccepted> recordAcceptance(LossAccepted accepted) {
                return raiser.raiseLanded(HoldTexts.lossAccepted(accepted))
                             .recover(_ -> false)
                             .flatMap(landed -> settleAcceptance(accepted, landed).async());
            }

            private Result<LossAccepted> settleAcceptance(LossAccepted accepted, boolean landed) {
                synchronized (drainers) {
                    var outcome = Episode.settlement(drainer(accepted.nodeId()).flatMap(current -> current.find(accepted.episode())),
                                                     accepted,
                                                     landed);

                    update(accepted.nodeId(),
                           current -> current.changed(accepted.episode(),
                                                      episode -> episode.acceptanceSettled(accepted, landed)));

                    return outcome;
                }
            }
        }

        return new holdRecords(reports, raiser, log, clock, reportAfterMs, new HashMap<>());
    }

    /// One raise to perform: the at-risk warning alone, or the warning and its closer.
    record Raise(NodeId nodeId, String episode, OperatorWarning warning, Option<OperatorWarning> closer) {
        public static Raise raise(NodeId nodeId,
                                  String episode,
                                  OperatorWarning warning,
                                  Option<OperatorWarning> closer) {
            return new Raise(nodeId, episode, warning, closer);
        }
    }

    /// How an episode closed.
    enum Closer {
        DELIVERED,
        LOSS_ACCEPTED,
        DEATH,
        SUPERSEDED
    }

    /// The closing of an episode: its kind, the newer episode that superseded it, the decision still awaiting the drainer (an
    /// acceptance whose record landed before the drainer reported its end) and whether its closer landed.
    record Closing(Closer kind, Option<String> successor, Option<HoldDecision.AcceptLoss> relay, boolean landed) {
        public static Closing closing(Closer kind,
                                      Option<String> successor,
                                      Option<HoldDecision.AcceptLoss> relay,
                                      boolean landed) {
            return new Closing(kind, successor, relay, landed);
        }

        static Closing closedBy(Closer kind) {
            return closing(kind, Option.none(), Option.none(), false);
        }

        static Closing supersededBy(String successor) {
            return closing(Closer.SUPERSEDED, Option.some(successor), Option.none(), false);
        }

        static Closing accepted(LossAccepted record) {
            return closing(Closer.LOSS_ACCEPTED,
                           Option.none(),
                           Option.some(HoldDecision.AcceptLoss.acceptLoss(record.episode(), record.confirmation())),
                           false);
        }

        /// The closer is due: not landed, and no decision awaits the drainer.
        boolean due() {
            return ! landed && relay.isEmpty();
        }

        /// The drainer is past the episode: a decision still awaiting it is dropped, so the closer becomes due.
        Closing released() {
            return closing(kind, successor, Option.none(), landed);
        }

        /// The drainer reported the end of an episode whose decision awaited it: the reported end closes it.
        Closing acknowledged(Closer reported) {
            return closing(reported, successor, Option.none(), landed);
        }

        Closing landed(boolean landedNow) {
            return closing(kind, successor, relay, landed || landedNow);
        }

        /// What the drainer is told (H5): `EndRecorded` once the closer landed, else the decision awaiting it, else nothing.
        Option<HoldDecision> decision(String episode) {
            return landed
                   ? Option.some(HoldDecision.EndRecorded.endRecorded(episode))
                   : relay.map(HoldDecision.class::cast);
        }
    }

    /// One drain episode of a node: OPEN while `closing` is empty, CLOSED (terminal, remembered) once it is present.
    /// Immutable; [HoldRecords] replaces it under its lock.
    record Episode(String episode,
                   Option<HeldReport> lastReport,
                   long firstSeenMillis,
                   boolean seeded,
                   boolean warningLanded,
                   Option<Closing> closing,
                   boolean accepting,
                   boolean raising) {
        public static Episode episode(String episode,
                                      Option<HeldReport> lastReport,
                                      long firstSeenMillis,
                                      boolean seeded,
                                      boolean warningLanded,
                                      Option<Closing> closing,
                                      boolean accepting,
                                      boolean raising) {
            return new Episode(episode, lastReport, firstSeenMillis, seeded, warningLanded, closing, accepting, raising);
        }

        /// Opened by its first report (R2); a first report that already ended opens it closed.
        static Episode first(HeldReport report, long now) {
            return episode(report.episode(), Option.none(), now, false, false, Option.none(), false, false).reported(report);
        }

        /// Opened from the event log by a new leader: no report yet, raised at once on the first report (same subject).
        static Episode seededAt(String episode, long now) {
            return episode(episode, Option.none(), now, true, false, Option.none(), false, false);
        }

        boolean open() {
            return closing.isEmpty();
        }

        /// Closed with a landed closer: nothing left to raise or relay.
        boolean done() {
            return closing.filter(Closing::landed)
                          .isPresent();
        }

        /// A report of this episode: the open episode keeps it unless it is older by its own asOf (H6) and closes on an ended
        /// report; a closed episode ignores it (H1), except that the drainer's end settles a decision awaiting it.
        Episode reported(HeldReport report) {
            return closing.fold(() -> updated(report), closed -> acknowledgedBy(closed, report));
        }

        private Episode updated(HeldReport report) {
            return olderThanStored(report)
                   ? this
                   : withReport(report);
        }

        private boolean olderThanStored(HeldReport report) {
            return lastReport.filter(stored -> report.asOfMillis() < stored.asOfMillis())
                             .isPresent();
        }

        private Episode withReport(HeldReport report) {
            return episode(episode,
                           Option.some(report),
                           firstSeenMillis,
                           seeded,
                           warningLanded,
                           reportedCloser(report).map(Closing::closedBy),
                           accepting,
                           raising);
        }

        private Episode acknowledgedBy(Closing closed, HeldReport report) {
            return closed.relay()
                         .isPresent()
                   ? reportedCloser(report).map(reported -> withClosing(closed.acknowledged(reported)))
                                   .or(this)
                   : this;
        }

        /// The drainer is past this episode (death, or a newer episode): the open episode closes as `whenOpen`; a decision
        /// awaiting the drainer is released; any other closing stands.
        Episode ended(Closing whenOpen) {
            return withClosing(closing.map(Closing::released).or(whenOpen));
        }

        /// The acceptance's record landed while the episode is open: closed LOSS_ACCEPTED, `AcceptLoss` awaits the drainer.
        Episode acceptanceSettled(LossAccepted record, boolean landed) {
            var settled = landed && open()
                          ? Option.some(Closing.accepted(record))
                          : closing;

            return episode(episode, lastReport, firstSeenMillis, seeded, warningLanded, settled, false, raising);
        }

        Episode accepting(boolean inFlight) {
            return episode(episode, lastReport, firstSeenMillis, seeded, warningLanded, closing, inFlight, raising);
        }

        Episode raising(boolean inFlight) {
            return episode(episode, lastReport, firstSeenMillis, seeded, warningLanded, closing, accepting, inFlight);
        }

        /// The outcome of a raise of this episode: clears the in-flight mark; a landed closer marks the closing landed.
        Episode settled(Raise raise, boolean landed) {
            var closed = raise.closer().isPresent()
                         ? closing.map(current -> current.landed(landed))
                         : closing;

            return episode(episode,
                           lastReport,
                           firstSeenMillis,
                           seeded,
                           warningLanded || landed,
                           closed,
                           accepting,
                           false);
        }

        private Episode withClosing(Closing closed) {
            return episode(episode,
                           lastReport,
                           firstSeenMillis,
                           seeded,
                           warningLanded,
                           Option.some(closed),
                           accepting,
                           raising);
        }

        /// The raise due now, if any: the closer until it landed, else the at-risk warning while open, reportable and not landed.
        Option<Raise> dueRaise(NodeId nodeId, long now, long reportAfterMs) {
            if (raising) {
                return Option.none();
            }

            return closing.filter(Closing::due)
                          .map(closed -> closingRaise(nodeId, closed))
                          .orElse(() -> opening(nodeId, now, reportAfterMs));
        }

        private Raise closingRaise(NodeId nodeId, Closing closed) {
            return Raise.raise(nodeId,
                               episode,
                               HoldTexts.atRisk(nodeId, episode, lastReport),
                               Option.some(HoldTexts.closer(nodeId, episode, closed, lastReport)));
        }

        private Option<Raise> opening(NodeId nodeId, long now, long reportAfterMs) {
            return lastReport.filter(_ -> open() && !warningLanded)
                             .filter(report -> reportable(report, now, reportAfterMs))
                             .map(report -> Raise.raise(nodeId,
                                                        episode,
                                                        HoldTexts.atRisk(nodeId,
                                                                         episode,
                                                                         Option.some(report)),
                                                        Option.none()));
        }

        /// Reportable at once when seeded, unenumerated or undeliverable, else after `reportAfterMs`. A clock read earlier than
        /// the first sighting (a step back) is read as the delay having passed: a late warning is the harm, an early one is not.
        private boolean reportable(HeldReport report, long now, long reportAfterMs) {
            return seeded || report.owedCount() < 0 || report.undeliverable() > 0 || now < firstSeenMillis || now - firstSeenMillis >= reportAfterMs;
        }

        /// The override check against this episode's own last report (H3): the episode named, still open, no acceptance
        /// recorded or in flight, and the digest of an enumerated inventory (or the unknown form while not enumerated).
        Result<LossAccepted> acceptable(NodeId nodeId, String requested, LossConfirmation confirmation) {
            return lastReport.toResult(HoldRefusal.NotHeld.FACTORY.apply(nodeId))
                             .flatMap(report -> checked(nodeId, report, requested, confirmation));
        }

        private Result<LossAccepted> checked(NodeId nodeId,
                                             HeldReport report,
                                             String requested,
                                             LossConfirmation confirmation) {
            if (!episode.equals(requested)) {
                return HoldRefusal.OtherEpisode.FACTORY.apply(requested, report).result();
            }

            if (accepting || closing.flatMap(Closing::relay).isPresent()) {
                return HoldRefusal.AlreadyAccepted.FACTORY.apply(episode).result();
            }

            if (closing.isPresent()) {
                return HoldRefusal.Ended.FACTORY.apply(episode).result();
            }

            return matches(report, confirmation)
                   ? Result.success(LossAccepted.lossAccepted(nodeId, episode, confirmation, report))
                   : HoldRefusal.StaleDigest.FACTORY.apply(confirmation.render(),
                                                           report)
                                                    .result();
        }

        /// The answer to an acceptance whose record raise completed (H4, H5): not landed is "record not landed"; landed is
        /// accepted only while the episode is still open (a delivery that arrived first wins: refused as ended).
        static Result<LossAccepted> settlement(Option<Episode> episode, LossAccepted accepted, boolean landed) {
            if (!landed) {
                return HoldRefusal.RecordNotLanded.FACTORY.apply(accepted.episode()).result();
            }

            return episode.filter(Episode::open)
                          .map(_ -> Result.success(accepted))
                          .or(() -> HoldRefusal.Ended.FACTORY.apply(accepted.episode()).result());
        }

        private static boolean matches(HeldReport report, LossConfirmation confirmation) {
            return switch (confirmation) {
                case LossConfirmation.Digest digest -> report.owedCount() >= 0 && report.owedDigest().equals(digest.value());
                case LossConfirmation.InventoryUnknown _ -> report.owedCount() < 0;
            };
        }

        private static Option<Closer> reportedCloser(HeldReport report) {
            return switch (report.ended()) {
                case DELIVERED -> Option.some(Closer.DELIVERED);
                case LOSS_ACCEPTED -> Option.some(Closer.LOSS_ACCEPTED);
                case NONE, UNKNOWN -> Option.none();
            };
        }

        /// The drain start stamp of an episode `<nodeId>@<drainStartMillis>`; an unreadable stamp orders first.
        static long stamp(String episode) {
            return Option.some(episode.lastIndexOf('@'))
                         .filter(at -> at > 0)
                         .flatMap(at -> Number.parseLong(episode.substring(at + 1)).option())
                         .or(Long.MIN_VALUE);
        }
    }

    /// The episodes of one drainer, sorted by drain start (newest last), and whether it left the view. Every exported operation
    /// applies ONE row of the episode table:
    /// - report of a known episode -> [Episode#reported] (update the open one; a closed one ignores it, H1);
    /// - report of an unknown NEWER episode -> the open one closes SUPERSEDED, the new one opens (H2); of an unknown older one
    ///   -> ignored (H1);
    /// - DEAD edge -> the open episode closes DEATH; LEAVE edge -> departed, the latch stands.
    /// Kept: the open episode, every closed one whose closer has not landed or whose decision awaits the drainer, and the
    /// newest closed one (the tombstone that makes a late report of it ignorable).
    record Drainer(NodeId nodeId, List<Episode> episodes, boolean departed) {
        public static Drainer drainer(NodeId nodeId, List<Episode> episodes, boolean departed) {
            return new Drainer(nodeId, List.copyOf(episodes), departed);
        }

        static Drainer unseen(NodeId nodeId) {
            return drainer(nodeId, List.of(), false);
        }

        /// The newest episode: the one the drainer is in.
        Option<Episode> current() {
            return episodes.isEmpty()
                   ? Option.none()
                   : Option.some(episodes.getLast());
        }

        Option<Episode> find(String episode) {
            return Option.from(episodes.stream().filter(known -> known.episode()
                                                                      .equals(episode)).findFirst());
        }

        boolean latched() {
            return episodes.stream()
                           .anyMatch(Episode::open);
        }

        /// Nothing left to raise, relay or report: the drainer departed and every closer landed.
        boolean finished() {
            return departed && episodes.stream()
                                       .allMatch(Episode::done);
        }

        Option<HeldReport> lastReport() {
            return finished()
                   ? Option.none()
                   : current().flatMap(Episode::lastReport);
        }

        Option<HoldDecision> decision() {
            return finished()
                   ? Option.none()
                   : current().flatMap(Drainer::decisionOf);
        }

        private static Option<HoldDecision> decisionOf(Episode episode) {
            return episode.closing()
                          .flatMap(closed -> closed.decision(episode.episode()));
        }

        Drainer reported(HeldReport report, long now) {
            return find(report.episode()).map(_ -> changed(report.episode(),
                                                           episode -> episode.reported(report)))
                       .or(() -> unknownReported(report, now));
        }

        private Drainer unknownReported(HeldReport report, long now) {
            return newer(report.episode())
                   ? opened(Episode.first(report, now))
                   : this;
        }

        /// A new leader learned an open episode from the log: a newer one opens as a report would; an older unknown one is
        /// remembered closed SUPERSEDED by the current episode, so its at-risk gets a closer.
        Drainer learned(String episode, long now) {
            if (find(episode).isPresent()) {
                return this;
            }

            return newer(episode)
                   ? opened(Episode.seededAt(episode, now))
                   : withOlder(Episode.seededAt(episode, now).ended(Closing.supersededBy(current().map(Episode::episode)
                                                                                                .or(episode))));
        }

        private boolean newer(String episode) {
            return current().map(known -> Episode.stamp(episode) > Episode.stamp(known.episode()))
                          .or(true);
        }

        private Drainer opened(Episode next) {
            var superseded = Closing.supersededBy(next.episode());
            var all = new ArrayList<Episode>();

            episodes.forEach(known -> all.add(known.ended(superseded)));
            all.add(next);

            return drainer(nodeId, pruned(all), false);
        }

        private Drainer withOlder(Episode older) {
            var all = new ArrayList<Episode>(episodes);

            all.add(older);
            all.sort(Comparator.comparingLong(known -> Episode.stamp(known.episode())));

            return drainer(nodeId, pruned(all), departed);
        }

        Drainer died() {
            return drainer(nodeId,
                           episodes.stream().map(known -> known.ended(Closing.closedBy(Closer.DEATH))).toList(),
                           true);
        }

        Drainer left() {
            return drainer(nodeId, episodes, true);
        }

        /// Apply `change` to the episode named `episode`, if it is still kept.
        Drainer changed(String episode, Fn1<Episode, Episode> change) {
            return drainer(nodeId,
                           pruned(episodes.stream()
                                          .map(known -> known.episode()
                                                             .equals(episode)
                                                        ? change.apply(known)
                                                        : known)
                                          .toList()),
                           departed);
        }

        List<Raise> dueRaises(long now, long reportAfterMs) {
            return episodes.stream()
                           .flatMap(episode -> episode.dueRaise(nodeId, now, reportAfterMs)
                                                      .stream())
                           .toList();
        }

        private static List<Episode> pruned(List<Episode> all) {
            var newestDone = Option.from(all.stream().filter(Episode::done).reduce((_, later) -> later));

            return all.stream()
                      .filter(episode -> !episode.done() || newestDone.filter(episode::equals)
                                                                      .isPresent())
                      .toList();
        }

        /// Subjects whose at-risk warning landed in `events` with no closer of the same subject, oldest drain first.
        static List<String> openEpisodes(List<ClusterEvent> events) {
            var opened = new HashSet<String>();
            var closed = new HashSet<String>();

            events.stream()
                  .flatMap(event -> warningOf(event).stream())
                  .forEach(warning -> classify(warning, opened, closed));
            opened.removeAll(closed);

            return opened.stream()
                         .sorted(Comparator.comparingLong(Episode::stamp))
                         .toList();
        }

        @Contract
        private static void classify(ClusterEvent.OperatorWarning warning, Set<String> opened, Set<String> closed) {
            var code = detail(warning, "code").or("");
            var target = OPENER.equals(code)
                         ? Option.some(opened)
                         : CLOSERS.contains(code)
                           ? Option.some(closed)
                           : Option.<Set<String>> none();

            target.onPresent(subjects -> detail(warning, "subject").onPresent(subjects::add));
        }

        private static final String OPENER = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK.code();

        private static final Set<String> CLOSERS = Set.of(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED.code(),
                                                          OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED.code());

        private static Option<ClusterEvent.OperatorWarning> warningOf(ClusterEvent event) {
            return event instanceof ClusterEvent.OperatorWarning warning
                   ? Option.some(warning)
                   : Option.none();
        }

        private static Option<String> detail(ClusterEvent.OperatorWarning warning, String key) {
            return Option.option(warning.details().get(key));
        }

        /// The node of an episode `<nodeId>@<drainStartMillis>`.
        static Option<NodeId> episodeNode(String episode) {
            return Option.some(episode.lastIndexOf('@'))
                         .filter(at -> at > 0)
                         .flatMap(at -> NodeId.nodeId(episode.substring(0, at)).option());
        }
    }

    /// The texts of a hold's events (memo §4, rv3345b 7). No prediction: no text says when or whether the hold will end.
    final class HoldTexts {
        private HoldTexts() {}

        static OperatorWarning atRisk(NodeId nodeId, String episode, Option<HeldReport> report) {
            return OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK,
                                                   episode,
                                                   report.map(held -> atRiskText(nodeId, episode, held))
                                                         .or(() -> unreportedText(nodeId, episode)));
        }

        static OperatorWarning closer(NodeId nodeId, String episode, Closing closing, Option<HeldReport> report) {
            return switch (closing.kind()) {
                case DELIVERED -> OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED,
                                                                  episode,
                                                                  "Draining node " + nodeId.id() + " (episode " + episode + "): every sole-copy DHT chunk acknowledged by a current holder" + report.map(held -> " as of " + at(held)).or(""));
                case LOSS_ACCEPTED -> OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED,
                                                                      episode,
                                                                      "Draining node " + nodeId.id() + " (episode " + episode + "): hold ended, loss accepted by an operator; " + owed(report) + " may be lost; restore them from the source of truth");
                case DEATH -> OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED,
                                                              episode,
                                                              "Draining node " + nodeId.id() + " (episode " + episode + "): declared dead by failure detection while held; " + owed(report) + " may be lost; restore them from the source of truth");
                case SUPERSEDED -> OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED,
                                                                   episode,
                                                                   "Draining node " + nodeId.id() + " (episode " + episode + "): hold ended, superseded by episode " + closing.successor().or("unknown") + " (the drainer started a new drain); last report of this episode: " + owed(report));
            };
        }

        static OperatorWarning lossAccepted(LossAccepted accepted) {
            var report = accepted.report();

            return OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED,
                                                   accepted.episode(),
                                                   "Operator accepted the loss of draining node " + accepted.nodeId()
                                                                                                            .id()
                                                  + " (episode " + accepted.episode()
                                                  + "): " + count(report)
                                                  + " as of " + at(report)
                                                  + " (" + accepted.confirmation()
                                                                   .render()
                                                  + "); first keys: " + String.join(",", report.sampleKeys()));
        }

        private static String atRiskText(NodeId nodeId, String episode, HeldReport report) {
            return "Draining node " + nodeId.id()
                 + " (episode " + episode
                 + ") is held: " + heldClause(report) + undeliverableClause(report)
                 + ". First keys: " + String.join(",", report.sampleKeys())
                 + "; full list in the node's WARN log; live count: `aether node lifecycle " + nodeId.id()
                 + "`. " + EXITS + exitCommand(nodeId, episode, report)
                 + "`.";
        }

        private static String unreportedText(NodeId nodeId, String episode) {
            return "Draining node " + nodeId.id()
                 + " (episode " + episode
                 + ") is held: its last report did not reach this leader; live count: `aether node lifecycle " + nodeId.id()
                 + "`. " + EXITS
                 + "aether node drain accept-loss " + nodeId.id()
                 + " --episode " + episode
                 + "` with the digest it reports, or `" + LossConfirmation.InventoryUnknown.INVENTORY_UNKNOWN.render()
                 + "` if it reports an inventory not enumerated.";
        }

        private static final String EXITS = "Exits: a holder acknowledges them (a target returns or stops refusing), or, while a leader is reachable, `";

        private static String heldClause(HeldReport report) {
            return report.owedCount() < 0
                   ? "DHT chunk(s) not acknowledged by a current holder; inventory not enumerated as of " + at(report)
                   : report.owedCount() + " DHT chunk(s) not acknowledged by a current holder as of " + at(report);
        }

        private static String undeliverableClause(HeldReport report) {
            return report.undeliverable() > 0
                   ? "; " + report.undeliverable() + " chunk(s) cannot be encoded; no retry delivers them"
                   : "";
        }

        private static String exitCommand(NodeId nodeId, String episode, HeldReport report) {
            return "aether node drain accept-loss " + nodeId.id()
                 + " --episode " + episode
                 + " " + (report.owedCount() < 0
                          ? LossConfirmation.InventoryUnknown.INVENTORY_UNKNOWN.render()
                          : LossConfirmation.Digest.digest(report.owedDigest()).render());
        }

        private static String owed(Option<HeldReport> report) {
            return report.map(held -> count(held) + " owed as of " + at(held) + digestClause(held))
                         .or("the key(s) it owed (count unknown to this leader: its last report did not reach it)");
        }

        private static String digestClause(HeldReport report) {
            return report.owedCount() < 0
                   ? ""
                   : " (digest " + report.owedDigest() + ")";
        }

        private static String count(HeldReport report) {
            return report.owedCount() < 0
                   ? "an unenumerated number of key(s)"
                   : report.owedCount() + " key(s)";
        }

        private static String at(HeldReport report) {
            return Instant.ofEpochMilli(report.asOfMillis()).toString();
        }
    }
}
