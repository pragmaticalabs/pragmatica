// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.cluster.NodeReplacementAnnouncements;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.DrainState;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Effect;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Observation;
import org.pragmatica.aether.deployment.cluster.NodeReplacementReconciler;
import org.pragmatica.aether.deployment.cluster.NodeReplacementReconciler.EffectResult;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService.Refusal;
import org.pragmatica.aether.deployment.cluster.ProvisionDisposition;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #1543 part E1 — binds the replacement reconciler and service to the node: reads membership, voters and readiness,
/// commits records by `LeaderTransaction`, provisions and retires through CTM, drains the old node through the SAME
/// operator admission as `POST /nodes/drain` (#1946's slice floor, never forced), and turns each committed transition into
/// its operator events. Everything here runs on scheduler or request threads; nothing is called from an FSM listener.
public final class NodeReplacementWiring {
    private static final Logger LOG = LoggerFactory.getLogger(NodeReplacementWiring.class);

    private NodeReplacementWiring() {}

    /// What the operator drain admission answered for the old node.
    public record DrainOutcome(boolean accepted, String blockedBy, String refusedFor) {
        public static DrainOutcome admitted() {
            return new DrainOutcome(true, "", "");
        }

        /// Held back by the slice floor: an operator-visible block.
        public static DrainOutcome blocked(String why) {
            return new DrainOutcome(false, why, "");
        }

        /// Not admitted now, and not by the slice floor: nothing to report as blocked, ask again next tick. `why` is what the
        /// admission said, kept so that a replacement that ends kept-both can say what stopped the drain.
        public static DrainOutcome pending(String why) {
            return new DrainOutcome(false, "", why);
        }
    }

    /// The drain admission's answer as the replacement reads it: admitted; blocked ONLY by the slice floor; anything else is a
    /// refusal that is retried silently and remembered.
    public static Promise<DrainOutcome> drainOutcomeOf(Promise<Unit> admission) {
        return admission.<DrainOutcome> map(_ -> DrainOutcome.admitted())
                        .recover(cause -> NodeLifecycleRoutes.isSliceFloorRefusal(cause)
                                          ? DrainOutcome.blocked(cause.message())
                                          : DrainOutcome.pending(cause.message()));
    }

    /// The listener that turns each committed transition into its operator events, on the node that owns the cluster-events
    /// partition ONLY: every node applies every record, and the events of one transition must be raised exactly once.
    public static NodeReplacementIndex.TransitionListener announcer(BooleanSupplier ownsClusterEvents,
                                                                    OperatorWarningSink warnings) {
        return (original, before, after) -> ownsClusterEvents.getAsBoolean()
                                            ? announce(warnings, original, before, after)
                                            : Unit.unit();
    }

    public record Inputs(NodeId self,
                         BooleanSupplier isLeader,
                         KVStore<AetherKey, AetherValue> kvStore,
                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply,
                         NodeReplacementIndex index,
                         Supplier<MembershipFsm> membership,
                         Supplier<Option<VoterConfiguration>> installed,
                         Supplier<Option<VoterConfiguration>> settled,
                         Supplier<Set<NodeId>> readyAdmitted,
                         Function<NodeId, String> version,
                         ClusterTopologyManager ctm,
                         Function<NodeId, Promise<DrainOutcome>> drain,
                         Predicate<NodeId> draining,
                         Supplier<String> idPrefix,
                         Predicate<NodeId> dhtHolds,
                         OperatorWarningSink warnings,
                         LongSupplier clock,
                         NodeReplacementPlanner.Timings timings) {}

    public record Wiring(NodeReplacementReconciler reconciler, NodeReplacementService service) {}

    public static Wiring wire(Inputs in) {
        var environment = new Env(in);

        return new Wiring(NodeReplacementReconciler.nodeReplacementReconciler(environment, in.timings()),
                          new Service(in, environment));
    }

    private static boolean alive(Option<String> state) {
        return state.filter(name -> !"Departing".equals(name) && !"Dead".equals(name))
                    .isPresent();
    }

    private static Option<LeaderValue> leaderValue(Inputs in) {
        return in.kvStore()
                 .getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                 .filter(leader -> in.isLeader()
                                     .getAsBoolean() && leader.leader()
                                                              .equals(in.self()));
    }

    private static Promise<Boolean> cas(Inputs in,
                                        NodeId original,
                                        Option<NodeReplacementValue> expected,
                                        NodeReplacementValue next) {
        return leaderValue(in).fold(() -> Promise.success(false),
                                    leader -> {
                                        var key = new NodeReplacementKey(original);
                                        var id = UUID.randomUUID().toString();
                                        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                                      expected.map(value -> (AetherValue) value),
                                                                                                      Option.some(next));
                                        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                              id,
                                                                                                              leader,
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

    /// The operator events of one committed transition. Called from the index on EVERY node as the record is applied, and
    /// acted on only by the node that owns the cluster-events partition (see `AetherNode`): the owner is the same node for
    /// the opening event and its recovery, which the leader is not once the leader is the node being replaced.
    public static Unit announce(OperatorWarningSink warnings,
                                NodeId original,
                                Option<NodeReplacementValue> before,
                                NodeReplacementValue after) {
        NodeReplacementAnnouncements.of(original, before, after).forEach(a -> OperatorWarnings.raise(LOG,
                                                                                                     warnings,
                                                                                                     a.code(),
                                                                                                     a.subject(),
                                                                                                     "{}",
                                                                                                     a.message()));

        return Unit.unit();
    }

    private static final class Env implements NodeReplacementReconciler.Environment {
        private final Inputs in;
        private final Set<NodeId> drainRequested = ConcurrentHashMap.newKeySet();
        private final Map<NodeId, String> drainBlocked = new ConcurrentHashMap<>();
        private final Map<NodeId, String> drainRefused = new ConcurrentHashMap<>();

        Env(Inputs in) {
            this.in = in;
        }

        @Override
        public boolean isLeader() {
            return in.isLeader()
                     .getAsBoolean();
        }

        @Override
        public Map<NodeId, NodeReplacementValue> records() {
            return in.index()
                     .all();
        }

        @Override
        public Promise<Boolean> commit(NodeId original, NodeReplacementValue expected, NodeReplacementValue next) {
            return cas(in, original, Option.some(expected), next);
        }

        @Override
        public Observation observe(NodeId original, NodeReplacementValue record) {
            var states = Option.option(in.membership().get()).map(MembershipFsm::memberStates).or(Map.of());
            var oldState = Option.option(states.get(original));
            var newState = Option.option(states.get(record.replacement()));
            var voters = in.installed().get().map(config -> Set.copyOf(config.members())).or(Set.of());
            var settled = in.installed()
                            .get()
                            .flatMap(current -> in.settled()
                                                  .get()
                                                  .filter(current::equals))
                            .isPresent();
            var ready = in.readyAdmitted().get();
            var oldAlive = alive(oldState);

            return new Observation(in.clock().getAsLong(),
                                   oldAlive,
                                   newState.isPresent(),
                                   alive(newState),
                                   ready.contains(record.replacement()),
                                   voters.contains(original),
                                   voters.contains(record.replacement()),
                                   settled,
                                   ready.contains(record.replacement()),
                                   in.version().apply(record.replacement()),
                                   drainState(original, oldAlive),
                                   drainBlocked.getOrDefault(original, ""),
                                   oldState.filter(name -> !"Dead".equals(name)).isEmpty(),
                                   !in.dhtHolds().test(original),
                                   drainRefused.getOrDefault(original, ""));
        }

        private DrainState drainState(NodeId original, boolean oldAlive) {
            if (!oldAlive) {
                return DrainState.COMPLETE;
            }

            return drainRequested.contains(original) || in.draining()
                                                          .test(original)
                   ? DrainState.IN_PROGRESS
                   : DrainState.NOT_REQUESTED;
        }

        @Override
        public Promise<EffectResult> execute(Effect effect, NodeId original, NodeReplacementValue record) {
            return switch (effect) {
                case PROVISION -> provision(original, record);
                case TERMINATE_REPLACEMENT -> terminate(record.replacement());
                case DRAIN_OLD -> drain(original);
                case RETIRE_OLD -> terminate(original);
                case NONE -> Promise.success(new EffectResult.Done());
            };
        }

        /// Idempotent across leaders: provisioning commits a capacity reservation for the replacement's id BEFORE the provider is
        /// called, so a new leader that finds the record still PROVISIONING (the old leader died between dispatch and its
        /// commit) finds the reservation too and must not dispatch the same id again; the join deadline bounds the wait.
        private Promise<EffectResult> provision(NodeId original, NodeReplacementValue record) {
            var reservation = in.kvStore()
                                .getTyped(new AetherKey.CapacityReservationKey(record.replacement()),
                                          AetherValue.CapacityReservationValue.class);

            if (reservation.isPresent()) {
                return Promise.success(reservation.filter(value -> value.phase() == AetherValue.CapacityReservationPhase.RELEASED)
                                                  .isPresent()
                                       ? new EffectResult.Failed("the capacity reservation for the replacement was refused")
                                       : new EffectResult.Done());
            }

            var members = Option.option(in.membership().get()).map(fsm -> fsm.memberStates()
                                                                             .keySet()).or(Set.of());

            return in.ctm()
                     .provisionReplacement(record.replacement(),
                                           Option.none(),
                                           Set.copyOf(members),
                                           NodeRole.CORE)
                     .<EffectResult> map(disposition -> switch (disposition) {
                case ProvisionDisposition.Dispatched _ -> new EffectResult.Done();
                case ProvisionDisposition.Deferred deferred -> new EffectResult.Deferred("provisioning deferred: " + deferred.reason());
            })
                     .recover(cause -> new EffectResult.Failed(cause.message()));
        }

        private Promise<EffectResult> terminate(NodeId node) {
            return in.ctm()
                     .drainNode(node, DrainReason.REPLACED)
                     .<EffectResult> map(_ -> new EffectResult.Done())
                     .recover(cause -> new EffectResult.Deferred("terminate refused: " + cause.message()));
        }

        private Promise<EffectResult> drain(NodeId original) {
            return in.drain()
                     .apply(original)
                     .<EffectResult> map(outcome -> drained(original, outcome))
                     .recover(cause -> new EffectResult.Deferred("drain admission failed: " + cause.message()));
        }

        private EffectResult drained(NodeId original, DrainOutcome outcome) {
            if (outcome.accepted()) {
                drainBlocked.remove(original);
                drainRefused.remove(original);
                drainRequested.add(original);

                return new EffectResult.Done();
            }

            if (outcome.blockedBy().isEmpty()) {
                // Refused for a reason that is not the slice floor: nothing an operator can act on. Ask again, and remember why.
                drainBlocked.remove(original);
                drainRefused.put(original, outcome.refusedFor());

                return new EffectResult.Deferred("drain not admitted yet");
            }

            drainBlocked.put(original, outcome.blockedBy());
            drainRefused.remove(original);

            return new EffectResult.Deferred("drain blocked");
        }
    }

    /// A replacement holds the cluster's one slot while it runs AND while it waits, kept-both, for an operator to settle it: the
    /// kept pair still holds an extra node, and beginning over it would orphan the kept replacement and leave its event open.
    static boolean holdsCapacity(NodeReplacementPhase phase) {
        return phase == NodeReplacementPhase.FAILED_KEPT_BOTH || !NodeReplacementReconciler.isTerminal(phase);
    }

    /// What the wired replacement service was given, readable for the boot test that pins the node's wiring.
    public interface Wired {
        /// The index of committed pairings the node's reconcilers and event announcer read.
        NodeReplacementIndex pairings();
    }

    private static final class Service implements NodeReplacementService, Wired {
        private final Inputs in;
        private final Env environment;

        @Override
        public NodeReplacementIndex pairings() {
            return in.index();
        }

        Service(Inputs in, Env environment) {
            this.in = in;
            this.environment = environment;
        }

        @Override
        public Promise<NodeReplacementValue> begin(NodeId original, String targetVersion) {
            if (!in.isLeader().getAsBoolean()) {
                return new Refusal.NotLeader().promise();
            }

            var fsm = in.membership().get();
            var descriptor = Option.option(fsm).flatMap(f -> f.memberDescriptor(original));
            var known = Option.option(fsm).map(f -> f.memberStates()
                                                     .containsKey(original)).or(false);

            if (!known) {
                return new Refusal.UnknownNode(original).promise();
            }

            var role = descriptor.map(d -> d.role()).or("core");

            if (!"core".equalsIgnoreCase(role)) {
                return new Refusal.RoleNotSupported(original, role).promise();
            }

            var live = in.index()
                         .all()
                         .entrySet()
                         .stream()
                         .filter(entry -> holdsCapacity(entry.getValue().phase()))
                         .findAny();

            if (live.isPresent()) {
                return new Refusal.AlreadyReplacing(live.get().getKey()).promise();
            }

            var replacement = NodeId.randomNodeId(in.idPrefix().get());
            var now = in.clock().getAsLong();
            var record = new NodeReplacementValue(replacement,
                                                  "core",
                                                  NodeReplacementPhase.PROVISIONING,
                                                  now + in.timings().provisioningMs(),
                                                  descriptor.map(d -> d.source()).or(""),
                                                  targetVersion,
                                                  NodeReplacementValue.MODE_CTM,
                                                  0,
                                                  "",
                                                  0L);
            var expected = in.index().recordFor(original);

            return cas(in, original, expected, record).flatMap(accepted -> {
                if (!accepted) {
                    return new Refusal.Conflict(original).<NodeReplacementValue> promise();
                }

                return Promise.success(record);
            });
        }

        @Override
        public Option<NodeReplacementValue> status(NodeId original) {
            return in.index()
                     .recordFor(original);
        }

        @Override
        public Map<NodeId, NodeReplacementValue> all() {
            return in.index()
                     .all();
        }

        @Override
        public Promise<Unit> settle(NodeId original, Settlement settlement) {
            var current = in.index().recordFor(original);

            if (current.filter(found -> found.phase() == NodeReplacementPhase.FAILED_KEPT_BOTH).isEmpty()) {
                return new Refusal.NothingToSettle(original).promise();
            }

            var record = current.unwrap();
            var now = in.clock().getAsLong();
            var next = settlement == Settlement.KEEP_NEW
                       ? record.advanced(NodeReplacementPhase.DRAINING_OLD,
                                         now + in.timings().drainingMs(),
                                         "")
                       : record.advanced(NodeReplacementPhase.REVERTING,
                                         now + in.timings().swappingMs(),
                                         "settled: rolled back");

            return cas(in, original, Option.some(record), next).flatMap(accepted -> {
                if (!accepted) {
                    return new Refusal.Conflict(original).<Unit> promise();
                }

                return Promise.unitPromise();
            });
        }
    }
}
