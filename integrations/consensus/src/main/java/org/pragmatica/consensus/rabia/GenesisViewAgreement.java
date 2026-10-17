package org.pragmatica.consensus.rabia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;


/// Genesis view agreement (#1526, owner design). Executor-confined; the engine drives it.
///
/// A node's VIEW is every core it has seen: its own id, the cores its discovery reports, and every
/// view another core announced to it. Views only grow (union), except when `cluster.genesis_voters`
/// fixes the view, in which case nothing is merged. Nodes announce `(round, view)` every round. Epoch
/// 0 may start with view V only when:
///
/// 1. `|V|` equals the configured core count (the size anchor);
/// 2. this node announced V in its last two rounds; and
/// 3. every other member of V reported exactly V in two consecutive rounds.
///
/// **Safety.** Every view a node ever reports is a superset of every earlier one. If two nodes started
/// with rosters V and V' sharing a member X, X reported both, so one contains the other; both have the
/// configured size, so V = V'. Two started rosters can therefore only differ by being DISJOINT, which
/// takes at least twice the configured count of authenticated cores, split by a partition into two
/// groups of exactly that count. That case is outside what this rule can close — `cluster.genesis_voters`
/// closes it. Safety rests on monotone views plus the size anchor alone. The two-round stability of
/// rules 2 and 3 is a robustness margin, kept as specified, that no safety test pins: removing it
/// leaves `GenesisViewAgreementSimulationTest` green, as the argument above predicts.
///
/// [limit: amnesiac-same-id-excluded-by-boot-token] "Views only grow" is a property of ONE process. A
/// process relaunched under a NodeId that already announced a view has forgotten it and can announce a
/// different one, so the argument also rests on a same-NodeId relaunch being terminal: peers hold the
/// original boot token and refuse the new process (#1528/#1545). With same-id relaunches allowed, the
/// verifier formed two overlapping epoch-0 configurations in 11 of 20,000 chaos seeds.
///
/// **Liveness.** Because views only grow, each node's view changes at most as many times as there are
/// cores, so flapping visibility cannot keep views churning forever: once every member of the final
/// view stays reachable for two consecutive rounds, genesis completes. A member that vanishes for good
/// holds genesis (it is in the view and never reports); so does a view larger than the configured count.
/// Both are reported through [#status]. A view never shrinks while its process lives, so an action that
/// leaves the pending processes running cannot clear it — stopping the extra candidates, stopping a retired
/// core, or adding a fresh-identity replacement all leave the survivors waiting (`GenesisRecoveryActionsTest`,
/// view layer). The operator procedure lives with the transport's boot tokens (#1545): STOP every pending
/// core and every worker or governor connected to them, THEN start the cores; or start them under fresh
/// identities; or set `cluster.genesis_voters` and apply the same stop-all-then-start
/// (`EmberGenesisRecoveryTest`, real transport). A view that cannot shrink in a live process is a liveness
/// trap, tracked as a follow-up.
final class GenesisViewAgreement {
    enum Stage {
        WAITING,
        EXCEEDS_COUNT,
        AGREED
    }

    record Report(long round, Set<NodeId> view) {}

    record Status(Stage stage, Set<NodeId> view, Set<NodeId> missing) {}

    private final NodeId self;
    private final int configuredCount;
    private final Option<Set<NodeId>> fixedView;
    private final Map<NodeId, List<Report>> reports = new HashMap<>();
    private final List<Set<NodeId>> announced = new ArrayList<>();
    private Set<NodeId> view;
    private long round;
    private long roundsSinceChange;
    private Option<Set<NodeId>> agreed = Option.none();

    private GenesisViewAgreement(NodeId self, int configuredCount, Option<Set<NodeId>> fixedView) {
        this.self = self;
        this.configuredCount = configuredCount;
        this.fixedView = fixedView;
        this.view = fixedView.or(() -> Set.of(self));
    }

    /// With a fixed view (`cluster.genesis_voters`) the anchor is that roster's own size: the operator
    /// named the electorate explicitly and discovery plays no part, so there is no partition-driven
    /// choice for the configured count to guard. Two nodes with different fixed views never agree,
    /// because a shared member reports only its own.
    static GenesisViewAgreement genesisViewAgreement(NodeId self, int configuredCount, Option<Set<NodeId>> fixedView) {
        return new GenesisViewAgreement(self,
                                        fixedView.map(Set::size).or(configuredCount),
                                        fixedView.map(Set::copyOf));
    }

    /// Starts a round: merges what discovery currently sees and returns the `(round, view)` to announce.
    Report tick(Set<NodeId> discovered) {
        merge(discovered);
        round++;
        roundsSinceChange++;
        announced.add(view);
        if (announced.size() > 2) {
            announced.removeFirst();
        }

        evaluate();

        return new Report(round, view);
    }

    /// Records one announcement from `sender` and merges its view.
    Unit receive(NodeId sender, long senderRound, Set<NodeId> senderView) {
        merge(senderView);
        merge(Set.of(sender));
        var history = reports.computeIfAbsent(sender, _ -> new ArrayList<>());

        if (history.stream().anyMatch(report -> report.round() >= senderRound)) {
            return Unit.unit();
        }

        history.add(new Report(senderRound, Set.copyOf(senderView)));
        if (history.size() > 2) {
            history.removeFirst();
        }

        evaluate();

        return Unit.unit();
    }

    Option<Set<NodeId>> agreed() {
        return agreed;
    }

    long round() {
        return round;
    }

    /// Rounds since this node's view last changed — the engine backs off its round interval on it.
    long roundsSinceChange() {
        return roundsSinceChange;
    }

    Status status() {
        if (agreed.isPresent()) {
            return new Status(Stage.AGREED, view, Set.of());
        }

        var stage = view.size() > configuredCount
                    ? Stage.EXCEEDS_COUNT
                    : Stage.WAITING;

        return new Status(stage, view, missing());
    }

    private Set<NodeId> missing() {
        var missing = new TreeSet<NodeId>((left, right) -> left.id()
                                                               .compareTo(right.id()));

        view.stream().filter(member -> !member.equals(self) && !reportedStably(member)).forEach(missing::add);

        return Set.copyOf(missing);
    }

    private void merge(Set<NodeId> seen) {
        if (fixedView.isPresent() || view.containsAll(seen)) {
            return;
        }

        var merged = new HashSet<>(view);

        merged.addAll(seen);
        view = Set.copyOf(merged);
        roundsSinceChange = 0;
    }

    private void evaluate() {
        if (agreed.isEmpty() && canStart()) {
            agreed = Option.some(view);
        }
    }

    private boolean canStart() {
        return view.size() == configuredCount
               && view.contains(self)
               && announcedStably()
               && view.stream()
                      .filter(member -> !member.equals(self))
                      .allMatch(this::reportedStably);
    }

    private boolean announcedStably() {
        return announced.size() == 2 && announced.stream()
                                                 .allMatch(view::equals);
    }

    private boolean reportedStably(NodeId member) {
        var history = reports.getOrDefault(member, List.of());

        return history.size() == 2
               && history.get(1)
                         .round() == history.get(0)
                                            .round() + 1
               && history.stream()
                         .allMatch(report -> report.view()
                                                   .equals(view));
    }
}
