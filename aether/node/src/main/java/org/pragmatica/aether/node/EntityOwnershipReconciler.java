// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.entity.EntityKeyspaceRegistrar;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityKeyspaceRegistrationValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter.HrwOwner;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #345 I1 narrow C — the durable-entity ownership reconcile: one level-triggered tick with two
/// idempotent halves, plus the [EntityKeyspaceRegistrar] seam slices declare through. Extracted from
/// the `AetherNode` wiring so the decision logic is unit-testable off a seeded [KVStore] (the
/// `ClusterTopologyRoutes.assembleOwnershipResponse` precedent) — an interface's static helpers can
/// only be public or private, and neither is a testing seam.
///
/// **Half 1, every node:** make this node's committed per-node registrations equal its
/// locally-declared keyspaces — in BOTH directions. A registration is what tells the LEADER a keyspace
/// exists AND that this node hosts it, and the leader may not host the slice, so the declaration has
/// to be cluster-visible. Asserting rather than writing once is the `SystemStreamRegistrar` lesson: a
/// single fire-and-forget apply that failed in a transient unquorate window would strand the keyspace
/// unowned forever. Pruning committed-but-undeclared records is what makes a retraction durable and
/// what heals the state retract can never see — a node that died and restarted without the slice; a
/// stale record on a LIVE node would otherwise keep it a placement candidate for a keyspace it can no
/// longer serve.
///
/// **Half 2, leader only:** mint the ownership records. Every COMMITTED registration (not just this
/// node's, so any leader can drive any keyspace) expands to its `entity:<keyspace>` arcs, and the
/// entity-specific [StreamPartitionOwnershipWriter] decides each one over the keyspace's hosting set
/// (see [#arcOwner]). The writer self-gates on leadership before reading any committed state and
/// returns [Option#none] for an unchanged owner, so a follower and a steady-state leader both emit
/// nothing and the batch is skipped.
///
/// **Exclusive authority.** Entity arcs are REAL streams since #345 I3 (`StreamEntityLogSubstrate`
/// creates `entity:<keyspace>` through `createStream`), so the stream-side replica reconcile also
/// walks them — for replica placement, which is correct and wanted. Its OWNERSHIP driver, however,
/// places over the whole member view and would fight this reconcile over the identical records,
/// re-placing entity arcs onto non-hosting nodes after every catalog or membership edge until the next
/// tick corrected them. [#withoutEntityArcs] is the boundary: the stream ownership driver filters
/// entity arcs out, making this class the ONLY writer of `entity:*` ownership.
public final class EntityOwnershipReconciler implements EntityKeyspaceRegistrar {
    private static final Logger LOG = LoggerFactory.getLogger(EntityOwnershipReconciler.class);

    private final Map<String, Integer> entityKeyspaces = new ConcurrentHashMap<>();
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final NodeId self;
    private final Supplier<List<NodeId>> membersSupplier;
    private final StreamPartitionOwnershipWriter writer;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier;
    /// The registration view the writer's [HrwOwner] reads, refreshed ONCE per tick — the writer asks
    /// per arc, and answering each ask with a fresh full scan would make a tick O(arcs × records).
    private volatile Map<String, HostedKeyspace> committedKeyspaces = Map.of();

    private EntityOwnershipReconciler(KVStore<AetherKey, AetherValue> kvStore,
                                      NodeId self,
                                      Supplier<List<NodeId>> membersSupplier,
                                      Function<HrwOwner, StreamPartitionOwnershipWriter> writerFactory,
                                      Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        this.kvStore = kvStore;
        this.self = self;
        this.membersSupplier = membersSupplier;
        this.writer = writerFactory.apply(this::snapshotArcOwner);
        this.applier = applier;
    }

    /// `writerFactory` receives the hosting-set [HrwOwner] this reconciler computes and returns the
    /// writer to drive with it — the node passes the real `StreamPartitionOwnershipWriter` factory
    /// (keeping its leader/term/clock suppliers in the wiring), tests pass a recording or throwing one.
    static EntityOwnershipReconciler entityOwnershipReconciler(KVStore<AetherKey, AetherValue> kvStore,
                                                               NodeId self,
                                                               Supplier<List<NodeId>> membersSupplier,
                                                               Function<HrwOwner, StreamPartitionOwnershipWriter> writerFactory,
                                                               Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return new EntityOwnershipReconciler(kvStore, self, membersSupplier, writerFactory, applier);
    }

    /// One keyspace's committed registration view: the hosting set, the arc span, and whether the
    /// hosts' declared partition counts disagreed (a rolling-redeploy window — surfaced as data so the
    /// tick can WARN and a test can assert the surfacing, instead of a log line nobody can see).
    /// `partitionCount` is the MAX across hosts: extra arcs are harmless no-ops, while minting fewer
    /// than a host fences against would strand that host's writes forever.
    public record HostedKeyspace(int partitionCount, Set<NodeId> hosts, boolean countsDisagree) {
        static HostedKeyspace hostedKeyspace(int partitionCount, NodeId host) {
            return new HostedKeyspace(partitionCount, Set.of(host), false);
        }

        HostedKeyspace merged(HostedKeyspace other) {
            var union = new HashSet<>(hosts);

            union.addAll(other.hosts());

            return new HostedKeyspace(Math.max(partitionCount, other.partitionCount()),
                                      Set.copyOf(union),
                                      countsDisagree || other.countsDisagree() || partitionCount != other.partitionCount());
        }
    }

    /// Record a locally-provisioned entity keyspace. Synchronous and IO-free, per the
    /// [EntityKeyspaceRegistrar] contract: provisioning must return a resolved promise, and a slice load
    /// that blocked on a consensus round would fail whenever the cluster was briefly unquorate.
    /// [#tick] does the committing, and keeps doing it until it sticks.
    @Override
    public Unit declare(String keyspace, int partitionCount) {
        entityKeyspaces.put(keyspace, partitionCount);

        return Unit.unit();
    }

    /// Forget a locally-unloaded entity keyspace — the retract mirror of [#declare], same contract.
    /// The next [#tick] sees "committed for this node but not declared" and prunes the record, which
    /// is also how a node that restarted WITHOUT the slice sheds its stale registration.
    @Override
    public Unit retract(String keyspace) {
        entityKeyspaces.remove(keyspace);

        return Unit.unit();
    }

    /// One reconcile pass — both halves, in order, so a tick that registers a keyspace can mint its
    /// arcs on the next pass over committed state.
    @Contract
    void tick() {
        // ScheduledExecutorService CANCELS a periodic task whose run throws, silently and permanently.
        // This tick is the only thing that ever mints entity ownership records, so a single throw would
        // leave every entity write refused forever with a cause that says "transient" — undiagnosable
        // from the outside, because the symptom is indistinguishable from a slow reconcile. Catching here
        // keeps the driver alive and names the failure. This is an adapter-boundary lift, not business
        // logic swallowing an error.
        try {
            var registrationChanges = registrationDelta(kvStore, entityKeyspaces, self);

            if (!registrationChanges.isEmpty()) {
                LOG.info("Entity keyspace: committing {} registration change(s)", registrationChanges.size());
            }

            applyBatch(registrationChanges, "registration change(s)");
            var scanned = scanRegistrations(kvStore);

            committedKeyspaces = scanned;
            var arcs = entityArcs(scanned);
            var commands = writer.writeOwnershipChanges(arcs);

            if (!commands.isEmpty()) {
                LOG.info("Entity ownership: minting {} record(s) across {} declared arc(s)",
                         commands.size(),
                         arcs.size());
            }

            applyBatch(commands, "ownership record(s)");
        } catch (RuntimeException e) {
            LOG.warn("Entity ownership reconcile tick failed: {} — retried next tick", e.toString(), e);
        }
    }

    /// The [HrwOwner] seam bound into the writer at construction: answers each per-arc ask from the
    /// snapshot the current tick published, plus a fresh member read.
    private Option<NodeId> snapshotArcOwner(String arcName, int partition) {
        return arcOwner(committedKeyspaces, membersSupplier.get(), arcName, partition);
    }

    /// The HRW owner of `(entity:<keyspace>, partition)` over the keyspace's HOSTING set — the nodes
    /// with a committed registration — intersected with the live member view. HRW over the subset keeps
    /// the placement deterministic and minimal-movement, exactly as over the full view; only the
    /// candidates differ. [Option#none] when the name is not an entity arc, when the keyspace has no
    /// committed registration, or when no registered host is currently a live member: the committed
    /// owner record is then left as it stands rather than re-placed onto a node that cannot serve —
    /// writes stay refused with the transient cause until a host returns, which is the honest state.
    static Option<NodeId> arcOwner(Map<String, HostedKeyspace> committed,
                                   List<NodeId> members,
                                   String arcName,
                                   int partition) {
        return EntityPartitionArc.keyspaceOf(arcName)
                                 .flatMap(keyspace -> Option.option(committed.get(keyspace)))
                                 .flatMap(hosted -> placeWithin(hosted.hosts(),
                                                                members,
                                                                arcName,
                                                                partition));
    }

    private static Option<NodeId> placeWithin(Set<NodeId> hosts, List<NodeId> members, String arcName, int partition) {
        var candidates = members.stream().filter(hosts::contains).toList();

        return ReplicaPlacement.place(arcName, partition, candidates, 1).map(Placement::owner);
    }

    /// The stream ownership driver's exclusion boundary (see the type comment's "Exclusive authority"):
    /// everything in `reconciled` EXCEPT entity arcs. Replica placement for entity logs is untouched —
    /// only the ownership write has exactly one authority.
    static List<PartitionKey> withoutEntityArcs(List<PartitionKey> reconciled) {
        return reconciled.stream()
                         .filter(partition -> EntityPartitionArc.keyspaceOf(partition.streamName()).isEmpty())
                         .toList();
    }

    /// Every committed registration record collapsed into a per-keyspace [HostedKeyspace] — ONE scan
    /// per tick. PUBLIC because it is the single authority on the merge semantics: the operator surface
    /// (`EntityCheckpointRoutes.assembleKeyspaces`) projects THIS, so the view can never drift from what
    /// the leader acts on (review catch: a route-local re-implementation had no equivalence test).
    /// Original per-tick rationale: ONE scan
    /// per tick, feeding both the arc expansion and the writer's per-arc owner asks. A keyspace whose
    /// hosts disagree on the partition count is WARNED here, once per tick, from the same flag the
    /// record carries for tests.
    public static Map<String, HostedKeyspace> scanRegistrations(KVStore<AetherKey, AetherValue> kvStore) {
        var scanned = new HashMap<String, HostedKeyspace>();

        kvStore.forEach(EntityKeyspaceRegistrationKey.class,
                        EntityKeyspaceRegistrationValue.class,
                        (key, value) -> mergeRegistration(scanned, key, value));
        scanned.forEach(EntityOwnershipReconciler::warnOnCountDisagreement);

        return Map.copyOf(scanned);
    }

    @Contract
    private static void mergeRegistration(Map<String, HostedKeyspace> scanned,
                                          EntityKeyspaceRegistrationKey key,
                                          EntityKeyspaceRegistrationValue value) {
        scanned.merge(key.keyspace(),
                      HostedKeyspace.hostedKeyspace(value.partitionCount(), key.node()),
                      HostedKeyspace::merged);
    }

    @Contract
    private static void warnOnCountDisagreement(String keyspace, HostedKeyspace hosted) {
        if (hosted.countsDisagree()) {
            LOG.warn("Entity keyspace '{}' declared with differing partition counts across its hosts"
                    + " — arcs span the max ({}) until the hosts' configs re-converge",
                     keyspace,
                     hosted.partitionCount());
        }
    }

    /// The scanned keyspaces expanded to their ownership arcs, named through
    /// [EntityPartitionArc#arcName] so the writer registers the IDENTICAL `entity:<keyspace>` coordinate
    /// the write fence and the read pipeline resolve — and so an entity keyspace can never collide with
    /// a stream of the same bare name.
    static List<PartitionKey> entityArcs(Map<String, HostedKeyspace> scanned) {
        return scanned.entrySet()
                      .stream()
                      .flatMap(entry -> IntStream.range(0,
                                                        entry.getValue().partitionCount())
                                                 .mapToObj(partition -> new PartitionKey(EntityPartitionArc.arcName(entry.getKey()),
                                                                                         partition)))
                      .toList();
    }

    /// The delta between `self`'s declared keyspaces and its committed per-node records: a `Put` for
    /// each declared keyspace whose record is missing or carries a different partition count, a
    /// `Remove` for each committed self-record whose keyspace is no longer declared. A converged node
    /// yields an empty delta, so a steady-state cluster does no consensus work per tick.
    static List<KVCommand<AetherKey>> registrationDelta(KVStore<AetherKey, AetherValue> kvStore,
                                                        Map<String, Integer> entityKeyspaces,
                                                        NodeId self) {
        var puts = entityKeyspaces.entrySet()
                                  .stream()
                                  .filter(entry -> !isRegistrationCommitted(kvStore,
                                                                            entry.getKey(),
                                                                            self,
                                                                            entry.getValue()))
                                  .<KVCommand<AetherKey>> map(entry -> registrationPut(entry.getKey(),
                                                                                       self,
                                                                                       entry.getValue()))
                                  .toList();

        return Stream.concat(puts.stream(),
                             staleSelfRemovals(kvStore, entityKeyspaces, self).stream())
                     .toList();
    }

    private static List<KVCommand<AetherKey>> staleSelfRemovals(KVStore<AetherKey, AetherValue> kvStore,
                                                                Map<String, Integer> entityKeyspaces,
                                                                NodeId self) {
        var removes = new ArrayList<KVCommand<AetherKey>>();

        kvStore.forEach(EntityKeyspaceRegistrationKey.class,
                        EntityKeyspaceRegistrationValue.class,
                        (key, _) -> collectStaleSelfRegistration(removes, entityKeyspaces, self, key));

        return List.copyOf(removes);
    }

    /// Only THIS node's records are ever pruned: another host's record is its own statement, and judging
    /// it from here would re-open the very gap the per-node shape closed.
    @Contract
    private static void collectStaleSelfRegistration(List<KVCommand<AetherKey>> removes,
                                                     Map<String, Integer> entityKeyspaces,
                                                     NodeId self,
                                                     EntityKeyspaceRegistrationKey key) {
        if (key.node().equals(self) && !entityKeyspaces.containsKey(key.keyspace())) {
            removes.add(new KVCommand.Remove<>(key));
        }
    }

    private static boolean isRegistrationCommitted(KVStore<AetherKey, AetherValue> kvStore,
                                                   String keyspace,
                                                   NodeId self,
                                                   int partitionCount) {
        return kvStore.getTyped(EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey(keyspace, self),
                                EntityKeyspaceRegistrationValue.class)
                      .map(value -> value.partitionCount() == partitionCount)
                      .or(false);
    }

    private static KVCommand<AetherKey> registrationPut(String keyspace, NodeId self, int partitionCount) {
        return new KVCommand.Put<AetherKey, AetherValue>(EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey(keyspace,
                                                                                                                     self),
                                                         EntityKeyspaceRegistrationValue.entityKeyspaceRegistrationValue(partitionCount));
    }

    /// Apply a non-empty batch as ONE consensus write. An empty batch (a follower, or a converged
    /// pass) applies nothing and logs nothing — an empty command batch would be rejected by consensus.
    ///
    /// Deliberately fire-and-forget (the `@Contract` exemption is FOR this): the tick is level-triggered,
    /// so a failed apply needs no per-call recovery — the identical delta is recomputed and re-applied
    /// next tick — and blocking a scheduler thread on a consensus round would stall every other periodic
    /// task. The failure is not silent: it is logged with the batch it lost.
    @Contract
    private void applyBatch(List<KVCommand<AetherKey>> commands, String what) {
        if (commands.isEmpty()) {
            return;
        }

        applier.apply(commands)
               .onFailure(cause -> LOG.warn("Entity {} batch of {} failed: {} — retried next tick",
                                            what,
                                            commands.size(),
                                            cause.message()));
    }
}
