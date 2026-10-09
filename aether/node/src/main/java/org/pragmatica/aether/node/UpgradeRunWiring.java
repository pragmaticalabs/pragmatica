// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.NodeReplacementService;
import org.pragmatica.aether.deployment.cluster.UpgradeRunAnnouncements;
import org.pragmatica.aether.deployment.cluster.UpgradeRunIndex;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner;
import org.pragmatica.aether.deployment.cluster.UpgradeRunReconciler;
import org.pragmatica.aether.deployment.cluster.UpgradeRunReconciler.BeginResult;
import org.pragmatica.aether.deployment.cluster.UpgradeRunService;
import org.pragmatica.aether.deployment.cluster.UpgradeRunService.Refusal;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.UpgradeRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #1543 part F1 — binds the rolling-upgrade run to the node: reads membership and versions, commits the run by `LeaderTransaction`,
/// starts each replacement through the merged [NodeReplacementService] (the run never reaches around it), and turns each committed
/// transition into its operator events on the cluster-events owner. Runs on scheduler and request threads only.
public final class UpgradeRunWiring {
    private static final Logger LOG = LoggerFactory.getLogger(UpgradeRunWiring.class);

    private UpgradeRunWiring() {}

    public record Inputs(NodeId self,
                         BooleanSupplier isLeader,
                         KVStore<AetherKey, AetherValue> kvStore,
                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                         UpgradeRunIndex index,
                         Supplier<MembershipFsm> membership,
                         Function<NodeId, String> version,
                         NodeReplacementService replacements,
                         LongSupplier clock) {}

    public record Wiring(UpgradeRunReconciler reconciler, UpgradeRunService service) {}

    public static Wiring wire(Inputs in) {
        var environment = new Env(in);

        return new Wiring(UpgradeRunReconciler.upgradeRunReconciler(environment), new Service(in, environment));
    }

    /// The listener that turns each committed transition into its operator events, on the cluster-events owner ONLY: every node applies
    /// every commit, and the events of one transition must be raised exactly once.
    public static UpgradeRunIndex.TransitionListener announcer(BooleanSupplier ownsClusterEvents,
                                                               OperatorWarningSink warnings) {
        return (before, after) -> {
            if (ownsClusterEvents.getAsBoolean()) {
                UpgradeRunAnnouncements.of(before, after).forEach(a -> OperatorWarnings.raise(LOG,
                                                                                              warnings,
                                                                                              a.code(),
                                                                                              a.subject(),
                                                                                              "{}",
                                                                                              a.message()));
            }

            return Unit.unit();
        };
    }

    /// The run's order: cores first with the leader LAST (the replacement of the leader hands leadership over once, at the end), then
    /// workers; nodes already on the target are left out. Names sort within a group so the order is the same wherever it is computed.
    static List<NodeId> orderOf(Map<NodeId, UpgradeRunPlanner.Member> members, String target, NodeId leader) {
        var needing = members.entrySet()
                             .stream()
                             .filter(entry -> entry.getValue()
                                                   .replaceable() && !target.equals(entry.getValue().version()))
                             .map(Map.Entry::getKey)
                             .sorted(Comparator.comparing(NodeId::id))
                             .toList();
        var order = new ArrayList<NodeId>();

        needing.stream().filter(id -> isCore(members, id) && !id.equals(leader)).forEach(order::add);
        needing.stream().filter(id -> isCore(members, id) && id.equals(leader)).forEach(order::add);
        needing.stream().filter(id -> !isCore(members, id)).forEach(order::add);

        return List.copyOf(order);
    }

    private static boolean isCore(Map<NodeId, UpgradeRunPlanner.Member> members, NodeId id) {
        return "core".equalsIgnoreCase(members.get(id).role());
    }

    private static Map<NodeId, UpgradeRunPlanner.Member> members(Inputs in) {
        var fsm = in.membership().get();
        var members = new HashMap<NodeId, UpgradeRunPlanner.Member>();

        if (fsm == null) {
            return members;
        }

        fsm.memberStates()
           .forEach((id, state) -> {
                        if (NodeReplacementWiring.alive(Option.option(state))) {
                        members.put(id,
                                    new UpgradeRunPlanner.Member(fsm.memberDescriptor(id).map(d -> d.role()).or("core"),
                                                                 in.version().apply(id)));
                    }
                    });

        return members;
    }

    /// One leader transaction replacing `expected` by `next` (absent `expected` = create), accepted only if the transaction was.
    private static Promise<Boolean> commit(Inputs in, Option<UpgradeRunValue> expected, UpgradeRunValue next) {
        var leader = in.kvStore()
                       .getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                       .filter(value -> in.isLeader()
                                          .getAsBoolean() && value.leader()
                                                                  .equals(in.self()));

        return leader.fold(() -> Promise.success(false),
                           value -> {
                               var id = UUID.randomUUID().toString();
                               var key = UpgradeRunKey.INSTANCE;
                               var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                             expected.map(run -> (AetherValue) run),
                                                                                             Option.some(next));
                               var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                     id,
                                                                                                     value,
                                                                                                     List.of(),
                                                                                                     List.of(mutation));

                               return in.apply()
                                        .apply(List.of(command))
                                        .map(results -> results.stream()
                                                               .filter(KVCommand.TransactionResult.class::isInstance)
                                                               .map(KVCommand.TransactionResult.class::cast)
                                                               .anyMatch(result -> result.transactionId()
                                                                                         .equals(id) && result.accepted()));
                           });
    }

    private static final class Env implements UpgradeRunReconciler.Environment {
        private final Inputs in;

        Env(Inputs in) {
            this.in = in;
        }

        @Override
        public boolean isLeader() {
            return in.isLeader()
                     .getAsBoolean();
        }

        @Override
        public Option<UpgradeRunValue> run() {
            return in.index()
                     .run();
        }

        @Override
        public Promise<Boolean> commit(UpgradeRunValue expected, UpgradeRunValue next) {
            return UpgradeRunWiring.commit(in, Option.some(expected), next);
        }

        @Override
        public UpgradeRunPlanner.Observation observe() {
            return new UpgradeRunPlanner.Observation(members(in),
                                                     in.replacements().all());
        }

        @Override
        public Promise<BeginResult> begin(NodeId node, String targetVersion) {
            return in.replacements()
                     .begin(node, targetVersion)
                     .<BeginResult> map(_ -> new BeginResult.Started())
                     .recover(cause -> switch (cause) {
                case NodeReplacementService.Refusal.NotLeader _, NodeReplacementService.Refusal.AlreadyReplacing _ -> new BeginResult.Deferred(cause.message());
                case NodeReplacementService.Refusal _ -> new BeginResult.Refused(cause.message());
                default -> new BeginResult.Deferred(cause.message());
            });
        }

        @Override
        public long now() {
            return in.clock()
                     .getAsLong();
        }
    }

    private static final class Service implements UpgradeRunService {
        private final Inputs in;
        private final Env environment;

        Service(Inputs in, Env environment) {
            this.in = in;
            this.environment = environment;
        }

        @Override
        public Promise<UpgradeRunValue> start(String targetVersion) {
            if (!in.isLeader().getAsBoolean()) {
                return new Refusal.NotLeader().promise();
            }

            var existing = in.index().run();

            if (existing.filter(UpgradeRunValue::live).isPresent()) {
                return new Refusal.AlreadyRunning(existing.unwrap().targetVersion()).promise();
            }

            var members = members(in);
            var order = orderOf(members, targetVersion, in.self());

            if (order.isEmpty()) {
                return new Refusal.NothingToReplace(targetVersion).promise();
            }

            var now = in.clock().getAsLong();
            var epoch = existing.map(UpgradeRunValue::epoch).or(0L) + 1;
            var run = new UpgradeRunValue(targetVersion,
                                          order,
                                          0,
                                          "",
                                          UpgradeRunState.RUNNING,
                                          UpgradeStop.NONE,
                                          "",
                                          now,
                                          now,
                                          epoch);

            return commit(in, existing, run).flatMap(accepted -> accepted
                                                                 ? Promise.success(run)
                                                                 : new Refusal.Conflict().<UpgradeRunValue> promise());
        }

        @Override
        public Option<UpgradeRunValue> status() {
            return in.index()
                     .run();
        }

        @Override
        public Promise<UpgradeRunValue> pause() {
            return request("pause",
                           UpgradeRunState.RUNNING,
                           run -> run.with(run.index(),
                                           run.inFlight(),
                                           run.state(),
                                           UpgradeStop.PAUSE,
                                           run.reason(),
                                           now()));
        }

        @Override
        public Promise<UpgradeRunValue> abort() {
            return in.index()
                     .run()
                     .filter(UpgradeRunValue::live)
                     .fold(() -> notApplicable("abort"),
                           run -> run.state() == UpgradeRunState.PAUSED
                                  ? change(run,
                                           run.with(run.index(),
                                                    run.inFlight(),
                                                    UpgradeRunState.RUNNING,
                                                    UpgradeStop.ABORT,
                                                    run.reason(),
                                                    now()))
                                  : change(run,
                                           run.with(run.index(),
                                                    run.inFlight(),
                                                    run.state(),
                                                    UpgradeStop.ABORT,
                                                    run.reason(),
                                                    now())));
        }

        /// A paused run goes back to RUNNING. A node whose replacement was rolled back (or whose record is gone) is tried again, so its
        /// `inFlight` is cleared; a still-live or kept-both record is left, and the planner pauses the run again, saying why.
        @Override
        public Promise<UpgradeRunValue> resume() {
            return request("resume",
                           UpgradeRunState.PAUSED,
                           run -> run.with(run.index(),
                                           retried(run),
                                           UpgradeRunState.RUNNING,
                                           UpgradeStop.NONE,
                                           "",
                                           now()));
        }

        private String retried(UpgradeRunValue run) {
            if (run.inFlight().isEmpty()) {
                return "";
            }

            var record = Option.option(in.replacements().all().get(new NodeId(run.inFlight())));
            var rolledBack = record.filter(value -> value.phase() == NodeReplacementPhase.ROLLED_BACK);

            return record.isEmpty() || rolledBack.isPresent()
                   ? ""
                   : run.inFlight();
        }

        private long now() {
            return in.clock()
                     .getAsLong();
        }

        private Promise<UpgradeRunValue> request(String operation,
                                                 UpgradeRunState required,
                                                 Function<UpgradeRunValue, UpgradeRunValue> change) {
            return in.index()
                     .run()
                     .filter(run -> run.state() == required)
                     .fold(() -> notApplicable(operation),
                           run -> change(run,
                                         change.apply(run)));
        }

        private Promise<UpgradeRunValue> notApplicable(String operation) {
            return in.index()
                     .run()
                     .fold(() -> new Refusal.NoRun().<UpgradeRunValue> promise(),
                           run -> new Refusal.NotApplicable(run.state().name(),
                                                            operation).<UpgradeRunValue> promise());
        }

        private Promise<UpgradeRunValue> change(UpgradeRunValue before, UpgradeRunValue next) {
            if (!in.isLeader().getAsBoolean()) {
                return new Refusal.NotLeader().promise();
            }

            return environment.commit(before, next)
                              .flatMap(accepted -> accepted
                                                   ? Promise.success(next)
                                                   : new Refusal.Conflict().<UpgradeRunValue> promise());
        }
    }
}
