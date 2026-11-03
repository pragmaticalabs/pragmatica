// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The deployment FSM's writer for one leadership term (#2584). Every consensus write of `cluster.fsm` goes through
/// [#write] or one of the hierarchy writes, named by its [WriteSite].
public final class TermWriter {
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> store;
    private final NodeId self;
    private final BooleanSupplier deactivated;
    private final HierarchyStateWriter hierarchy;

    private TermWriter(ClusterNode<KVCommand<AetherKey>> cluster,
                       KVStore<AetherKey, AetherValue> store,
                       NodeId self,
                       BooleanSupplier deactivated,
                       HierarchyStateWriter hierarchy) {
        this.cluster = cluster;
        this.store = store;
        this.self = self;
        this.deactivated = deactivated;
        this.hierarchy = hierarchy;
    }

    static TermWriter termWriter(ClusterNode<KVCommand<AetherKey>> cluster,
                                 KVStore<AetherKey, AetherValue> store,
                                 NodeId self,
                                 BooleanSupplier deactivated,
                                 HierarchyStateWriter hierarchy) {
        return new TermWriter(cluster, store, self, deactivated, hierarchy);
    }

    /// Builds the site's commands and applies them, only while the committed leader record names this node and the
    /// term is live (design §2.2 point 3a, generalised from #2592's rollback guard). Otherwise nothing is built or
    /// applied, and the write fails as the site's [NotLeaderAction] says. The returned promise is otherwise the apply's
    /// own; the site attaches its existing success and failure handling to it.
    public <R> Promise<List<R>> write(WriteSite site, Fn0<List<KVCommand<AetherKey>>> build) {
        return writeUnder(site, _ -> build.apply());
    }

    /// As [#write], for a site whose commands carry the leader record they are issued under (a [KVCommand.LeaderTransaction]).
    /// `build` receives the very record the precondition checked, never one read afterwards: a newer leader's record that
    /// commits between the check and the build would otherwise be embedded, and the applier would accept it.
    public <R> Promise<List<R>> writeUnder(WriteSite site, Fn1<List<KVCommand<AetherKey>>, LeaderValue> build) {
        return issuingLeader().fold(() -> notLeader(site),
                                    leader -> cluster.apply(build.apply(leader)));
    }

    /// A conditional hierarchy put (the [WriteSite#CORE_DIRECTIVE] and [WriteSite#ACTIVATE_REISSUE] sites). Refused with
    /// `HierarchyStateWriter.Refusal.INACTIVE` unless the committed leader record names this node and the term is live.
    public Promise<Unit> hierarchyPut(AetherKey key, Option<AetherValue> expected, AetherValue value) {
        return hierarchy.put(key, expected, value);
    }

    /// A conditional hierarchy commit (the [WriteSite#DEPARTURE_DIRECTIVE_REMOVE] site), refused as [#hierarchyPut] is.
    public Promise<Unit> hierarchyCommit(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations,
                                         List<KVCommand.ReadWitness<AetherKey>> guards) {
        return hierarchy.commit(mutations, guards);
    }

    private Option<LeaderValue> issuingLeader() {
        return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                    .filter(this::namesThisLiveTerm);
    }

    private boolean namesThisLiveTerm(LeaderValue value) {
        return value.leader()
                    .equals(self) && !deactivated.getAsBoolean();
    }

    /// The refusal of [#write]. Only sites that go through [#write] reach it, and none of them declares
    /// [NotLeaderAction#INACTIVE_REFUSAL]: the hierarchy sites are refused by the hierarchy writer inside
    /// [#hierarchyPut]/[#hierarchyCommit], so the action decides nothing here and the site is carried for its consumer.
    private <R> Promise<List<R>> notLeader(WriteSite site) {
        return FsmWriteError.NotLeader.FACTORY.apply(site, committedLeader()).promise();
    }

    private Option<NodeId> committedLeader() {
        return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                    .map(LeaderValue::leader);
    }
}
