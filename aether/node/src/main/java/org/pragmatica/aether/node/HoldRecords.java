// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Instant;
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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;


/// The leader's records of a departure hold (#3345, D45-6): the single writer of every record of a held drain episode.
///
/// - **Latch (R2).** HELD is latched per drain episode by the first held report and is never read back from a live answer: a report
///   that is absent (the drainer missing from the view, its FAN entry swept, a restart under a new boot token) changes nothing. The
///   latch is cleared only by a delivery report, by a landed loss acceptance, or by the drainer's death ([#onDead]).
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

    /// The drainer left the view cleanly (LEAVE edge): the record goes once its closer landed; the latch is not cleared.
    @Contract
    void onLeft(NodeId nodeId);

    /// Failure detection declared the drainer dead (DEAD edge): clears the latch and closes the hold from the last report with
    /// `dht-departure-hold-ended` (or the reported end, when the report ended before the closer landed).
    @Contract
    void onDead(NodeId nodeId);

    /// New leader: latch every episode whose `dht-departure-sole-copy-at-risk` landed in the retained log with no closer of the same
    /// subject. Completes with the number of latches seeded; fails when the log cannot be read (then latches come from reports only).
    Promise<Integer> seedFromLog();

    /// The operator override: check the FAN's latest report for `nodeId` against `episode` and `confirmation`, record
    /// `dht-departure-loss-accepted`, and only once it landed relay `AcceptLoss`. Fails with a [HoldRefusal].
    Promise<LossAccepted> acceptLoss(NodeId nodeId, String episode, LossConfirmation confirmation);

    /// The decision to relay to `nodeId` on the ping: `EndRecorded` once the closer landed, else `AcceptLoss` once its record landed.
    Option<HoldDecision> decisionFor(NodeId nodeId);

    /// Whether an episode of `nodeId` is latched HELD. Never read from a live answer.
    boolean heldLatched(NodeId nodeId);

    /// The last held report the latched episode carried, kept past the FAN sweep.
    Option<HeldReport> lastReport(NodeId nodeId);

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
                           Map<NodeId, Hold> holds) implements HoldRecords {
            @Override
            @Contract
            public void onPong(NodeId nodeId) {
                reports.latestHeldReport(nodeId)
                       .onPresent(report -> observe(nodeId, report));
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
                update(nodeId, Hold::left);
                raiseDue(nodeId);
            }

            @Override
            @Contract
            public void onDead(NodeId nodeId) {
                update(nodeId, Hold::died);
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
                return hold(nodeId).flatMap(Hold::decision);
            }

            @Override
            public boolean heldLatched(NodeId nodeId) {
                return hold(nodeId).filter(Hold::latched)
                                   .isPresent();
            }

            @Override
            public Option<HeldReport> lastReport(NodeId nodeId) {
                return hold(nodeId).flatMap(Hold::lastReport);
            }

            private Option<Hold> hold(NodeId nodeId) {
                synchronized (holds) {
                    return Option.option(holds.get(nodeId));
                }
            }

            private List<NodeId> recordedIds() {
                synchronized (holds) {
                    return List.copyOf(holds.keySet());
                }
            }

            @Contract
            private void observe(NodeId nodeId, HeldReport report) {
                var now = clock.getAsLong();

                synchronized (holds) {
                    holds.put(nodeId, Hold.observed(Option.option(holds.get(nodeId)), nodeId, report, now));
                }
            }

            /// Apply `change` to the recorded hold, if any; a hold whose closer landed after the drainer departed is dropped.
            @Contract
            private void update(NodeId nodeId, Fn1<Hold, Hold> change) {
                synchronized (holds) {
                    Option.option(holds.get(nodeId))
                          .map(change::apply)
                          .onPresent(this::store);
                }
            }

            @Contract
            private void store(Hold hold) {
                if (hold.finished()) {
                    holds.remove(hold.nodeId());
                } else {
                    holds.put(hold.nodeId(), hold);
                }
            }

            /// Claim the raise due for `nodeId` (marking it in flight) and perform it outside the lock: the event layer's completion
            /// may call back into this unit on another thread.
            @Contract
            private void raiseDue(NodeId nodeId) {
                claimRaise(nodeId).onPresent(this::perform);
            }

            private Option<Raise> claimRaise(NodeId nodeId) {
                var now = clock.getAsLong();

                synchronized (holds) {
                    var raise = Option.option(holds.get(nodeId))
                                      .flatMap(hold -> hold.dueRaise(now, reportAfterMs));

                    raise.onPresent(_ -> holds.computeIfPresent(nodeId, (_, hold) -> hold.raising(true)));
                    return raise;
                }
            }

            @Contract
            private void perform(Raise raise) {
                raiseOf(raise).onResult(result -> settleRaise(raise, result.or(false)));
            }

            private Promise<Boolean> raiseOf(Raise raise) {
                return raise.closer()
                            .fold(() -> raiser.raiseLanded(raise.warning()),
                                  closer -> raiser.raiseClosing(raise.warning(), closer));
            }

            /// A raise that did not land only clears the in-flight mark: the next pong or tick raises it again (re-raised until landed).
            @Contract
            private void settleRaise(Raise raise, boolean landed) {
                update(raise.nodeId(), hold -> hold.settled(raise, landed));
            }

            private int seed(List<ClusterEvent> events) {
                var now = clock.getAsLong();
                var open = Hold.openEpisodes(events);

                synchronized (holds) {
                    var before = holds.size();

                    open.stream()
                        .flatMap(episode -> Hold.seeded(episode, now)
                                                .stream())
                        .forEach(hold -> holds.putIfAbsent(hold.nodeId(), hold));
                    return holds.size() - before;
                }
            }

            private Result<LossAccepted> claimAcceptance(NodeId nodeId, String episode, LossConfirmation confirmation) {
                synchronized (holds) {
                    return Option.option(holds.get(nodeId))
                                 .toResult(HoldRefusal.NotHeld.FACTORY.apply(nodeId))
                                 .flatMap(Hold::acceptable)
                                 .flatMap(_ -> liveReport(nodeId))
                                 .flatMap(report -> Hold.confirmed(nodeId, report, episode, confirmation))
                                 .onSuccess(_ -> holds.computeIfPresent(nodeId, (_, hold) -> hold.accepting(true)));
                }
            }

            private Result<HeldReport> liveReport(NodeId nodeId) {
                return reports.latestHeldReport(nodeId)
                              .toResult(HoldRefusal.NotHeld.FACTORY.apply(nodeId));
            }

            /// Record first: the decision becomes visible only once `dht-departure-loss-accepted` landed.
            private Promise<LossAccepted> recordAcceptance(LossAccepted accepted) {
                return raiser.raiseLanded(HoldTexts.lossAccepted(accepted))
                             .flatMap(landed -> settleAcceptance(accepted, landed).async());
            }

            private Result<LossAccepted> settleAcceptance(LossAccepted accepted, boolean landed) {
                update(accepted.nodeId(), hold -> hold.acceptanceSettled(accepted, landed));
                return landed
                       ? Result.success(accepted)
                       : HoldRefusal.RecordNotLanded.FACTORY.apply(accepted.episode())
                                                            .result();
            }
        }

        return new holdRecords(reports, raiser, log, clock, reportAfterMs, new HashMap<>());
    }

    /// One raise to perform: the at-risk warning alone, or the warning and its closer.
    record Raise(NodeId nodeId, String episode, OperatorWarning warning, Option<OperatorWarning> closer) {
        public static Raise raise(NodeId nodeId, String episode, OperatorWarning warning, Option<OperatorWarning> closer) {
            return new Raise(nodeId, episode, warning, closer);
        }
    }

    /// How a hold closes.
    enum Closer {
        DELIVERED,
        LOSS_ACCEPTED,
        DEATH
    }

    /// The record of one drain episode. Immutable; [HoldRecords] replaces it under its lock.
    record Hold(NodeId nodeId,
                String episode,
                Option<HeldReport> lastReport,
                long firstSeenMillis,
                boolean seeded,
                boolean latched,
                boolean warningLanded,
                Option<Closer> closer,
                boolean closerLanded,
                Option<HoldDecision.AcceptLoss> accepted,
                boolean accepting,
                boolean raising,
                boolean departed) {
        public static Hold hold(NodeId nodeId,
                                String episode,
                                Option<HeldReport> lastReport,
                                long firstSeenMillis,
                                boolean seeded,
                                boolean latched,
                                boolean warningLanded,
                                Option<Closer> closer,
                                boolean closerLanded,
                                Option<HoldDecision.AcceptLoss> accepted,
                                boolean accepting,
                                boolean raising,
                                boolean departed) {
            return new Hold(nodeId,
                            episode,
                            lastReport,
                            firstSeenMillis,
                            seeded,
                            latched,
                            warningLanded,
                            closer,
                            closerLanded,
                            accepted,
                            accepting,
                            raising,
                            departed);
        }

        /// The latch set by the first held report of an episode (R2); a first report that already ended latches nothing and closes.
        static Hold first(NodeId nodeId, HeldReport report, long now) {
            return hold(nodeId,
                        report.episode(),
                        Option.none(),
                        now,
                        false,
                        !ended(report),
                        false,
                        Option.none(),
                        false,
                        Option.none(),
                        false,
                        false,
                        false).withReport(report);
        }

        /// A latch a new leader learned from the event log: no report yet, raised at once on the first report (same subject).
        static Option<Hold> seeded(String episode, long now) {
            return episodeNode(episode).map(nodeId -> hold(nodeId,
                                                           episode,
                                                           Option.none(),
                                                           now,
                                                           true,
                                                           true,
                                                           false,
                                                           Option.none(),
                                                           false,
                                                           Option.none(),
                                                           false,
                                                           false,
                                                           false));
        }

        /// A report of another episode replaces a record only once that record has nothing left to say (its closer landed) or
        /// never carried a report (a seed); otherwise the open episode keeps its latch.
        static Hold observed(Option<Hold> current, NodeId nodeId, HeldReport report, long now) {
            return current.map(hold -> hold.observedBy(nodeId, report, now))
                          .or(() -> first(nodeId, report, now));
        }

        private Hold observedBy(NodeId reporter, HeldReport report, long now) {
            if (episode.equals(report.episode())) {
                return withReport(report);
            }
            return closerLanded || lastReport.isEmpty()
                   ? first(reporter, report, now)
                   : this;
        }

        /// Keep `report` as the last report; an ended report clears the latch and names the closer (an earlier closer stands).
        Hold withReport(HeldReport report) {
            var end = reportedCloser(report);

            return hold(nodeId,
                        episode,
                        Option.some(report),
                        firstSeenMillis,
                        seeded,
                        latched && end.isEmpty(),
                        warningLanded,
                        closer.orElse(end),
                        closerLanded,
                        accepted,
                        accepting,
                        raising,
                        departed);
        }

        Hold left() {
            return hold(nodeId,
                        episode,
                        lastReport,
                        firstSeenMillis,
                        seeded,
                        latched,
                        warningLanded,
                        closer,
                        closerLanded,
                        accepted,
                        accepting,
                        raising,
                        true);
        }

        Hold died() {
            return hold(nodeId,
                        episode,
                        lastReport,
                        firstSeenMillis,
                        seeded,
                        false,
                        warningLanded,
                        closer.orElse(Option.some(Closer.DEATH)),
                        closerLanded,
                        accepted,
                        accepting,
                        raising,
                        true);
        }

        Hold raising(boolean inFlight) {
            return hold(nodeId,
                        episode,
                        lastReport,
                        firstSeenMillis,
                        seeded,
                        latched,
                        warningLanded,
                        closer,
                        closerLanded,
                        accepted,
                        accepting,
                        inFlight,
                        departed);
        }

        Hold accepting(boolean inFlight) {
            return hold(nodeId,
                        episode,
                        lastReport,
                        firstSeenMillis,
                        seeded,
                        latched,
                        warningLanded,
                        closer,
                        closerLanded,
                        accepted,
                        inFlight,
                        raising,
                        departed);
        }

        /// The outcome of a raise of this episode; a raise of another episode only releases nothing (the record was replaced).
        Hold settled(Raise raise, boolean landed) {
            if (!episode.equals(raise.episode())) {
                return this;
            }
            var closing = raise.closer()
                               .isPresent();

            return hold(nodeId,
                        episode,
                        lastReport,
                        firstSeenMillis,
                        seeded,
                        latched,
                        warningLanded || landed,
                        closer,
                        closerLanded || (closing && landed),
                        accepted,
                        accepting,
                        false,
                        departed);
        }

        /// The override landed (clears the latch, relays `AcceptLoss`) or did not (nothing changes but the in-flight mark).
        Hold acceptanceSettled(LossAccepted record, boolean landed) {
            var decision = landed && episode.equals(record.episode())
                           ? Option.some(HoldDecision.AcceptLoss.acceptLoss(record.episode(), record.confirmation()))
                           : Option.<HoldDecision.AcceptLoss>none();

            return hold(nodeId,
                        episode,
                        lastReport,
                        firstSeenMillis,
                        seeded,
                        latched && decision.isEmpty(),
                        warningLanded,
                        closer,
                        closerLanded,
                        accepted.orElse(decision),
                        false,
                        raising,
                        departed);
        }

        /// Nothing left to record or relay: the closer landed and the drainer is gone.
        boolean finished() {
            return closerLanded && departed;
        }

        Option<HoldDecision> decision() {
            return closerLanded
                   ? Option.some(HoldDecision.EndRecorded.endRecorded(episode))
                   : accepted.map(HoldDecision.class::cast);
        }

        /// The raise due now, if any: the closer until it landed, else the at-risk warning while latched, reportable and not landed.
        Option<Raise> dueRaise(long now, long reportAfterMs) {
            if (raising) {
                return Option.none();
            }
            return closer.filter(_ -> !closerLanded)
                         .map(this::closing)
                         .orElse(() -> opening(now, reportAfterMs));
        }

        private Raise closing(Closer kind) {
            return Raise.raise(nodeId,
                               episode,
                               HoldTexts.atRisk(nodeId, episode, lastReport),
                               Option.some(HoldTexts.closer(nodeId, episode, kind, lastReport)));
        }

        private Option<Raise> opening(long now, long reportAfterMs) {
            return lastReport.filter(_ -> latched && !warningLanded)
                             .filter(report -> reportable(report, now, reportAfterMs))
                             .map(report -> Raise.raise(nodeId,
                                                        episode,
                                                        HoldTexts.atRisk(nodeId, episode, Option.some(report)),
                                                        Option.none()));
        }

        private boolean reportable(HeldReport report, long now, long reportAfterMs) {
            return seeded || report.owedCount() < 0 || report.undeliverable() > 0 || now - firstSeenMillis >= reportAfterMs;
        }

        /// The override applies to a latched hold with no end in sight and no acceptance recorded or in flight.
        Result<Hold> acceptable() {
            if (closer.isPresent() || closerLanded) {
                return HoldRefusal.Ended.FACTORY.apply(episode)
                                                .result();
            }
            if (accepted.isPresent() || accepting) {
                return HoldRefusal.AlreadyAccepted.FACTORY.apply(episode)
                                                          .result();
            }
            return latched
                   ? Result.success(this)
                   : HoldRefusal.NotHeld.FACTORY.apply(nodeId)
                                                .result();
        }

        /// The check against the FAN's latest report: same episode, still open, and the digest of an enumerated inventory
        /// (or the unknown form while the count is not enumerated).
        static Result<LossAccepted> confirmed(NodeId nodeId,
                                              HeldReport report,
                                              String episode,
                                              LossConfirmation confirmation) {
            if (!report.episode()
                       .equals(episode)) {
                return HoldRefusal.OtherEpisode.FACTORY.apply(episode, report)
                                                       .result();
            }
            if (ended(report)) {
                return HoldRefusal.Ended.FACTORY.apply(episode)
                                                .result();
            }
            return matches(report, confirmation)
                   ? Result.success(LossAccepted.lossAccepted(nodeId, episode, confirmation, report))
                   : HoldRefusal.StaleDigest.FACTORY.apply(confirmation.render(), report)
                                                    .result();
        }

        private static boolean matches(HeldReport report, LossConfirmation confirmation) {
            return switch (confirmation) {
                case LossConfirmation.Digest digest -> report.owedCount() >= 0 && report.owedDigest()
                                                                                        .equals(digest.value());
                case LossConfirmation.InventoryUnknown _ -> report.owedCount() < 0;
            };
        }

        private static boolean ended(HeldReport report) {
            return reportedCloser(report).isPresent();
        }

        private static Option<Closer> reportedCloser(HeldReport report) {
            return switch (report.ended()) {
                case DELIVERED -> Option.some(Closer.DELIVERED);
                case LOSS_ACCEPTED -> Option.some(Closer.LOSS_ACCEPTED);
                case NONE, UNKNOWN -> Option.none();
            };
        }

        /// Subjects whose at-risk warning landed in `events` with no closer of the same subject, in log order.
        static List<String> openEpisodes(List<ClusterEvent> events) {
            var opened = new HashSet<String>();
            var closed = new HashSet<String>();

            events.stream()
                  .flatMap(event -> warningOf(event).stream())
                  .forEach(warning -> classify(warning, opened, closed));
            opened.removeAll(closed);
            return opened.stream()
                         .sorted()
                         .toList();
        }

        @Contract
        private static void classify(ClusterEvent.OperatorWarning warning, Set<String> opened, Set<String> closed) {
            var code = detail(warning, "code").or("");
            var target = OPENER.equals(code)
                         ? Option.some(opened)
                         : CLOSERS.contains(code)
                           ? Option.some(closed)
                           : Option.<Set<String>>none();

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
            return Option.option(warning.details()
                                        .get(key));
        }

        /// The node of an episode `<nodeId>@<drainStartMillis>`.
        static Option<NodeId> episodeNode(String episode) {
            return Option.some(episode.lastIndexOf('@'))
                         .filter(at -> at > 0)
                         .flatMap(at -> NodeId.nodeId(episode.substring(0, at))
                                              .option());
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

        static OperatorWarning closer(NodeId nodeId, String episode, Closer kind, Option<HeldReport> report) {
            return switch (kind) {
                case DELIVERED -> OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED,
                                                                  episode,
                                                                  "Draining node " + nodeId.id() + " (episode " + episode
                                                                  + "): every sole-copy DHT chunk acknowledged by a current holder"
                                                                  + report.map(held -> " as of " + at(held))
                                                                          .or(""));
                case LOSS_ACCEPTED -> OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED,
                                                                      episode,
                                                                      "Draining node " + nodeId.id() + " (episode " + episode
                                                                      + "): hold ended, loss accepted by an operator; "
                                                                      + owed(report)
                                                                      + " may be lost; restore them from the source of truth");
                case DEATH -> OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED,
                                                              episode,
                                                              "Draining node " + nodeId.id() + " (episode " + episode
                                                              + "): declared dead by failure detection while held; " + owed(report)
                                                              + " may be lost; restore them from the source of truth");
            };
        }

        static OperatorWarning lossAccepted(LossAccepted accepted) {
            var report = accepted.report();

            return OperatorWarning.operatorWarning(OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED,
                                                   accepted.episode(),
                                                   "Operator accepted the loss of draining node " + accepted.nodeId()
                                                                                                           .id()
                                                   + " (episode " + accepted.episode() + "): " + count(report)
                                                   + " as of " + at(report) + " (" + accepted.confirmation()
                                                                                           .render()
                                                   + "); first keys: " + String.join(",", report.sampleKeys()));
        }

        private static String atRiskText(NodeId nodeId, String episode, HeldReport report) {
            return "Draining node " + nodeId.id() + " (episode " + episode + ") is held: " + heldClause(report)
                   + undeliverableClause(report) + ". First keys: " + String.join(",", report.sampleKeys())
                   + "; full list in the node's WARN log; live count: `aether node lifecycle " + nodeId.id() + "`. "
                   + EXITS + exitCommand(nodeId, episode, report) + "`.";
        }

        private static String unreportedText(NodeId nodeId, String episode) {
            return "Draining node " + nodeId.id() + " (episode " + episode
                   + ") is held: its last report did not reach this leader; live count: `aether node lifecycle " + nodeId.id()
                   + "`. " + EXITS + "aether node drain accept-loss " + nodeId.id() + " --episode " + episode + "` with the digest it reports.";
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
            return "aether node drain accept-loss " + nodeId.id() + " --episode " + episode + " " + (report.owedCount() < 0
                                                                                                    ? LossConfirmation.InventoryUnknown.INVENTORY_UNKNOWN.render()
                                                                                                    : LossConfirmation.Digest.digest(report.owedDigest())
                                                                                                                            .render());
        }

        private static String owed(Option<HeldReport> report) {
            return report.map(held -> count(held) + " owed as of " + at(held) + " (digest " + held.owedDigest() + ")")
                         .or("the key(s) it owed (count unknown to this leader: its last report did not reach it)");
        }

        private static String count(HeldReport report) {
            return report.owedCount() < 0
                   ? "an unenumerated number of key(s)"
                   : report.owedCount() + " key(s)";
        }

        private static String at(HeldReport report) {
            return Instant.ofEpochMilli(report.asOfMillis())
                          .toString();
        }
    }
}
