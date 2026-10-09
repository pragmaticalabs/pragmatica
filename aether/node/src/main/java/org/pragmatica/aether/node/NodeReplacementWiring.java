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
import java.util.stream.Stream;
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
import org.pragmatica.aether.environment.SourceName;
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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
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
                         Supplier<Set<NodeId>> readyAll,
                         Function<NodeId, String> version,
                         ClusterTopologyManager ctm,
                         Function<NodeId, Promise<DrainOutcome>> drain,
                         Predicate<NodeId> draining,
                         Supplier<String> idPrefix,
                         Predicate<NodeId> dhtHolds,
                         Supplier<Set<NodeId>> genesisVoters,
                         java.util.function.IntSupplier fleetLimit,
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

    /// The binding of an EXTERNAL reservation written while the fleet ledger could not count it (no ledger, or its inventory is not
    /// complete). The marker travels with the reservation, so whatever later settles it knows whether a slot was taken: it returns a
    /// slot only for a counted reservation and never for this one.
    static final String UNCOUNTED = org.pragmatica.aether.deployment.cluster.CapacityControlledLifecycle.UNCOUNTED_BINDING;

    /// The ledger, when it is in a state that can count a slot (it exists and its inventory is complete, as CTM requires).
    static Option<AetherValue.CapacityLedgerValue> countable(Option<AetherValue.CapacityLedgerValue> ledger) {
        return ledger.filter(AetherValue.CapacityLedgerValue::inventoryComplete);
    }

    /// What is committed in the same transaction as an EXTERNAL replacement's record: the admission intent the operator-started node
    /// is admitted by, a capacity reservation for its role (core admission and `AetherNode.workerAdmissionAllowed` both admit on
    /// it; the same reservation the leader writes when it provisions a node itself), so there is no window with a pairing and no
    /// intent, or the reverse. A CTM replacement is admitted by its own provisioning reservation, so it commits the record alone.
    ///
    /// The fleet counter is counted up in the same transaction when the ledger can count, exactly as CTM's own reservation does, so the
    /// settlement that later returns the slot ([#settleReservation]) is symmetric with this acquire and returns only a slot that was
    /// taken; a reservation the ledger could not count carries [#UNCOUNTED] so it is dropped without touching the counter.
    static List<KVCommand.Mutation<AetherKey, AetherValue>> admissionMutations(boolean external,
                                                                               String role,
                                                                               NodeId replacement,
                                                                               String source,
                                                                               Option<AetherValue.CapacityLedgerValue> ledger) {
        if (!external) {
            return List.of();
        }

        var countable = countable(ledger);
        var reservation = new KVCommand.Mutation<AetherKey, AetherValue>(new AetherKey.CapacityReservationKey(replacement),
                                                                         Option.<AetherValue> none(),
                                                                         Option.<AetherValue> some(new AetherValue.CapacityReservationValue(source,
                                                                                                                                            countable.isPresent()
                                                                                                                                            ? ""
                                                                                                                                            : UNCOUNTED,
                                                                                                                                            role.toLowerCase(java.util.Locale.ROOT),
                                                                                                                                            AetherValue.CapacityReservationPhase.DISPATCHED)));
        var counted = countable.<KVCommand.Mutation<AetherKey, AetherValue>> map(current -> new KVCommand.Mutation<>(AetherKey.CapacityLedgerKey.INSTANCE,
                                                                                                                     Option.<AetherValue> some(current),
                                                                                                                     Option.<AetherValue> some(new AetherValue.CapacityLedgerValue(current.allocated() + 1,
                                                                                                                                                                                   current.version() + 1,
                                                                                                                                                                                   current.inventoryComplete()))));

        return counted.fold(() -> List.of(reservation), ledgerMutation -> List.of(reservation, ledgerMutation));
    }

    /// The reservation of an EXTERNAL replacement is settled in the commit that ends the replacement, in the same transaction:
    /// - ROLLED_BACK with the node never observed (still `DISPATCHED`): a counted reservation becomes `RELEASED`, which hands it to the
    ///   lifecycle's refusal reconciliation (it returns the counted slot and drops the reservation); an uncounted one is deleted.
    ///   Either way the id stops being admissible.
    /// - DONE (the replacement is a member now, admitted by the voter set, not by the reservation): a counted `DISPATCHED` reservation
    ///   becomes `OBSERVED`, exactly what the lifecycle writes when it sees the instance, so the slot is returned when the node is
    ///   later retired and the id stops being admissible then; an uncounted one is deleted.
    /// A reservation already `OBSERVED` is the lifecycle's to release.
    static List<KVCommand.Mutation<AetherKey, AetherValue>> settleReservation(NodeReplacementValue next,
                                                                              Option<AetherValue.CapacityReservationValue> reservation) {
        if (!NodeReplacementValue.MODE_EXTERNAL.equals(next.mode()) || (next.phase() != NodeReplacementPhase.ROLLED_BACK && next.phase() != NodeReplacementPhase.DONE)) {
            return List.of();
        }

        var key = new AetherKey.CapacityReservationKey(next.replacement());

        return reservation.filter(value -> value.phase() == AetherValue.CapacityReservationPhase.DISPATCHED)
                          // An uncounted reservation holds no slot, but it is the only admission intent a node of a ledger-less cluster has: a worker that
                          // loses its community assignment is admitted again on it. A node that did arrive keeps it; only a rollback (never arrived) drops it.
                          .filter(value -> !(UNCOUNTED.equals(value.sourceBinding()) && next.phase() == NodeReplacementPhase.DONE))
                          .<KVCommand.Mutation<AetherKey, AetherValue>> map(value -> new KVCommand.Mutation<>(key,
                                                                                                              Option.<AetherValue> some(value),
                                                                                                              settled(next.phase(),
                                                                                                                      value)))
                          .fold(() -> List.<KVCommand.Mutation<AetherKey, AetherValue>> of(),
                                List::of);
    }

    private static Option<AetherValue> settled(NodeReplacementPhase phase, AetherValue.CapacityReservationValue value) {
        if (UNCOUNTED.equals(value.sourceBinding())) {
            return Option.none();
        }

        return Option.<AetherValue> some(new AetherValue.CapacityReservationValue(value.sourceName(),
                                                                                  value.sourceBinding(),
                                                                                  value.intendedRole(),
                                                                                  phase == NodeReplacementPhase.DONE
                                                                                  ? AetherValue.CapacityReservationPhase.OBSERVED
                                                                                  : AetherValue.CapacityReservationPhase.RELEASED));
    }

    /// Hands the pairings to the two reconcilers that must treat them as capacity on purpose. One call, so that what each receives is
    /// pinned in one place: the leader reconciler counts only CORE replacements as core capacity, the placement reconciler is told
    /// which nodes a reduction must not remove (the live pairings') and which are surge (not an excess).
    public static Unit connectReconcilers(NodeReplacementIndex index,
                                          java.util.function.Consumer<Supplier<Set<NodeId>>> leaderCoreSurge,
                                          java.util.function.BiConsumer<Supplier<Set<NodeId>>, Supplier<Set<NodeId>>> placementShield) {
        leaderCoreSurge.accept(index::coreSurgeReplacements);
        placementShield.accept(index::retirementProtected, index::surgeReplacements);

        return Unit.unit();
    }

    private static Promise<Boolean> cas(Inputs in,
                                        NodeId original,
                                        Option<NodeReplacementValue> expected,
                                        NodeReplacementValue next) {
        return cas(in, original, expected, next, List.of());
    }

    private static Promise<Boolean> cas(Inputs in,
                                        NodeId original,
                                        Option<NodeReplacementValue> expected,
                                        NodeReplacementValue next,
                                        List<KVCommand.Mutation<AetherKey, AetherValue>> alongside) {
        var key = new NodeReplacementKey(original);
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      expected.map(value -> (AetherValue) value),
                                                                      Option.some(next));

        return submit(in,
                      key,
                      Stream.concat(Stream.of(mutation), alongside.stream()).toList());
    }

    /// One leader transaction over `mutations`, accepted only if the transaction was.
    private static Promise<Boolean> submit(Inputs in,
                                           AetherKey key,
                                           List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        return leaderValue(in).fold(() -> Promise.success(false),
                                    leader -> {
                                        var id = UUID.randomUUID().toString();
                                        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                              id,
                                                                                                              leader,
                                                                                                              List.of(),
                                                                                                              mutations);

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
        // Nodes whose provider instance a listing after the terminate showed gone, why the last attempt on a node did not, and
        // when the attempts on a node began. Held per leader: a new leader repeats the (idempotent) confirmation.
        private final Set<NodeId> reaped = ConcurrentHashMap.newKeySet();
        private final Map<NodeId, String> reapFailure = new ConcurrentHashMap<>();
        private final Map<NodeId, Long> reapSince = new ConcurrentHashMap<>();
        // Nodes whose instance a provider listing showed while they were still up: only for these does a later EMPTY listing mean gone.
        private final Set<NodeId> seen = ConcurrentHashMap.newKeySet();
        private final Set<NodeId> observing = ConcurrentHashMap.newKeySet();

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
            var reservation = in.kvStore()
                                .getTyped(new AetherKey.CapacityReservationKey(next.replacement()),
                                          AetherValue.CapacityReservationValue.class);

            return cas(in, original, Option.some(expected), next, settleReservation(next, reservation));
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
            var ready = "core".equalsIgnoreCase(record.role())
                        ? in.readyAdmitted().get()
                        : in.readyAll().get();
            var oldAlive = alive(oldState);

            noteLiveInstance(original, record, oldAlive);
            noteLiveInstance(record.replacement(), record, alive(newState));

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
                                   drainRefused.getOrDefault(original, ""),
                                   reaped.contains(original),
                                   reapFailure.getOrDefault(original,
                                                            reapFailure.getOrDefault(record.replacement(), "")));
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
                case TERMINATE_REPLACEMENT -> terminate(record.replacement(), record, true);
                case DRAIN_OLD -> drain(original, record);
                case RETIRE_OLD -> terminate(original, record, false);
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
                                           NodeRole.nodeRole(record.role()).or(NodeRole.CORE),
                                           SourceName.sourceNameOrDefault(record.source()))
                     .<EffectResult> map(disposition -> switch (disposition) {
                case ProvisionDisposition.Dispatched _ -> new EffectResult.Done();
                case ProvisionDisposition.Deferred deferred -> new EffectResult.Deferred("provisioning deferred: " + deferred.reason());
            })
                     .recover(cause -> new EffectResult.Failed(cause.message()));
        }

        /// Done only when the provider's own listing, taken after the terminate, shows the instance gone. A refusal, a listing that
        /// fails or an instance still listed is Deferred with its cause (never read as gone) and tried again on the next tick; a
        /// rollback gives up after the retirement budget (Failed, which keeps the pair for the operator), while the retirement's
        /// own deadline is the planner's.
        private Promise<EffectResult> terminate(NodeId node, NodeReplacementValue record, boolean boundedByRetiring) {
            if (reaped.contains(node)) {
                return Promise.success(new EffectResult.Done());
            }

            reapSince.putIfAbsent(node,
                                  in.clock().getAsLong());
            var source = SourceName.sourceNameOrDefault(record.source());

            return in.ctm()
                     .drainNode(node, DrainReason.REPLACED)
                     .flatMap(_ -> in.ctm()
                                     .reapRetired(node,
                                                  source,
                                                  seen.contains(node)))
                     .<EffectResult> map(_ -> confirmedGone(node))
                     .recover(cause -> notConfirmed(node, cause, boundedByRetiring));
        }

        private EffectResult confirmedGone(NodeId node) {
            reaped.add(node);
            reapFailure.remove(node);
            reapSince.remove(node);

            return new EffectResult.Done();
        }

        private EffectResult notConfirmed(NodeId node, Cause cause, boolean boundedByRetiring) {
            reapFailure.put(node, cause.message());
            if (boundedByRetiring && in.clock().getAsLong() - reapSince.getOrDefault(node,
                                                                                     in.clock().getAsLong()) > in.timings()
                                                                                                                 .retiringMs()) {
                reapSince.remove(node);

                return new EffectResult.Failed(cause.message());
            }

            return new EffectResult.Deferred("termination not confirmed: " + cause.message());
        }

        private Promise<EffectResult> drain(NodeId original, NodeReplacementValue record) {
            return observeInstances(original, record).flatMap(_ -> in.drain()
                                                                     .apply(original)
                                                                     .<EffectResult> map(outcome -> drained(original,
                                                                                                            outcome))
                                                                     .recover(cause -> new EffectResult.Deferred("drain admission failed: " + cause.message())));
        }

        /// While both nodes are still up, ask the provider whether it lists them: an instance seen here is one a later empty listing can
        /// safely call gone (the old node self-halts after its drain). A listing that fails or lists nothing observes nothing and
        /// never blocks the drain; external-kind reservations have no provider instance to list.
        private Promise<Unit> observeInstances(NodeId original, NodeReplacementValue record) {
            var source = SourceName.sourceNameOrDefault(record.source());

            return Stream.of(original,
                             record.replacement())
                         .map(node -> in.ctm()
                                        .instanceListed(node, source)
                                        .onSuccess(listed -> {
                                  if (listed) {
                                  seen.add(node);
                              }
                              })
                                        .<Unit> map(_ -> Unit.unit())
                                        .recover(_ -> Unit.unit()))
                         .reduce(Promise.unitPromise(),
                                 (all, one) -> all.flatMap(_ -> one));
        }

        /// A node the membership reads as up is asked about at the provider once, in the background: an instance listed while the node was
        /// up is one a later empty listing can call gone. This is the observation point for a replacement that crashes before any drain
        /// (a canary death) and for an old node on a leader that took over after the drain. Never while PROVISIONING: a listing through the capacity
        /// lifecycle also records what it sees and, racing the provisioning's own fleet-inventory initialisation, made it refuse ("provisioning refused",
        /// 8 Ember tests on bigboy). A failed or empty listing observes nothing.
        private void noteLiveInstance(NodeId node, NodeReplacementValue record, boolean up) {
            if (!up || record.phase() == NodeReplacementPhase.PROVISIONING || seen.contains(node) || !observing.add(node)) {
                return;
            }

            in.ctm()
              .instanceListed(node,
                              SourceName.sourceNameOrDefault(record.source()))
              .onSuccess(listed -> {
                  if (listed) {
                  seen.add(node);
              }
              })
              .onResultRun(() -> observing.remove(node));
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
        Set<NodeId> genesisVoters();
        /// The ready view a WORKER replacement's readiness is read from.
        Set<NodeId> readyAll();
        /// The index of committed pairings the node's reconcilers and event announcer read.
        NodeReplacementIndex pairings();
        /// Whether this node's membership view reads `id` as alive: the predicate the reconciler's observation uses for the old node.
        boolean memberAlive(NodeId id);
        /// Whether a drain of `id` was ever accepted by this node's reconciler.
        boolean drainRequested(NodeId id);
        /// The fleet node limit an EXTERNAL admission is checked against.
        int fleetLimit();
    }

    private static final class Service implements NodeReplacementService, Wired {
        private final Inputs in;
        private final Env environment;

        @Override
        public Set<NodeId> genesisVoters() {
            return in.genesisVoters()
                     .get();
        }

        @Override
        public Set<NodeId> readyAll() {
            return in.readyAll()
                     .get();
        }

        @Override
        public boolean memberAlive(NodeId id) {
            return alive(Option.option(in.membership().get()).flatMap(fsm -> Option.option(fsm.memberStates().get(id))));
        }

        @Override
        public int fleetLimit() {
            return in.fleetLimit()
                     .getAsInt();
        }

        @Override
        public boolean drainRequested(NodeId id) {
            return environment.drainRequested.contains(id);
        }

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
            return start(original, Option.none(), targetVersion);
        }

        @Override
        public Promise<NodeReplacementValue> beginExternal(NodeId original, NodeId replacement, String targetVersion) {
            return start(original, Option.some(replacement), targetVersion);
        }

        private Promise<NodeReplacementValue> start(NodeId original, Option<NodeId> chosen, String targetVersion) {
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

            if (!"core".equalsIgnoreCase(role) && !"worker".equalsIgnoreCase(role)) {
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

            var taken = chosen.filter(id -> isTaken(fsm, id));

            if (taken.isPresent()) {
                return new Refusal.ReplacementIdInUse(taken.unwrap()).promise();
            }

            var former = chosen.filter(id -> "core".equalsIgnoreCase(role) && in.genesisVoters()
                                                                                .get()
                                                                                .contains(id));

            if (former.isPresent()) {
                return new Refusal.FormerVoterIdentity(former.unwrap()).promise();
            }

            var ledger = countable(in.kvStore()
                                     .getTyped(AetherKey.CapacityLedgerKey.INSTANCE,
                                               AetherValue.CapacityLedgerValue.class));
            var sourceName = descriptor.map(d -> d.source()).or("");

            if (chosen.isPresent() && ledger.isPresent() && sourceName.isBlank()) {
                return new Refusal.SourceRequired(original).promise();
            }

            if (chosen.isPresent() && ledger.filter(counted -> counted.allocated() >= in.fleetLimit()
                                                                                        .getAsInt())
                                            .isPresent()) {
                return new Refusal.FleetFull(ledger.unwrap().allocated()).promise();
            }

            var replacement = chosen.or(() -> NodeId.randomNodeId(in.idPrefix().get()));
            var external = chosen.isPresent();
            var now = in.clock().getAsLong();
            var source = descriptor.map(d -> d.source()).or("");
            var record = new NodeReplacementValue(replacement,
                                                  role.toLowerCase(),
                                                  NodeReplacementPhase.PROVISIONING,
                                                  now + in.timings().provisioningMs(),
                                                  source,
                                                  targetVersion,
                                                  external
                                                  ? NodeReplacementValue.MODE_EXTERNAL
                                                  : NodeReplacementValue.MODE_CTM,
                                                  0,
                                                  "",
                                                  0L);
            var expected = in.index().recordFor(original);
            var alongside = NodeReplacementWiring.admissionMutations(external,
                                                                     role,
                                                                     replacement,
                                                                     source,
                                                                     in.kvStore()
                                                                       .getTyped(AetherKey.CapacityLedgerKey.INSTANCE,
                                                                                 AetherValue.CapacityLedgerValue.class));

            return cas(in, original, expected, record, alongside).flatMap(accepted -> {
                if (!accepted) {
                    return new Refusal.Conflict(original).<NodeReplacementValue> promise();
                }

                return Promise.success(record);
            });
        }

        private boolean isTaken(MembershipFsm fsm, NodeId id) {
            var member = Option.option(fsm).map(f -> f.memberStates()
                                                      .containsKey(id)).or(false);
            var paired = in.index().all().values().stream().anyMatch(value -> id.equals(value.replacement()));
            var reserved = in.kvStore().get(new AetherKey.CapacityReservationKey(id)).isPresent();

            return member || paired || reserved;
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
