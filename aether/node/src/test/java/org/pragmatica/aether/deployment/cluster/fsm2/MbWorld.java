// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.lang.Option;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// A membership world for the tests of the membership half: the harness, the leader, the cores, and a **real**
/// `BootTokens` registry that gives a node its boot token (`0` while the registry has recorded none, which is the unknown
/// path of C8). Admissions handed to the FSM are built from the registry, never stubbed.
final class MbWorld {
    final FsmHarness h;
    final BootTokens tokens = BootTokens.bootTokens(0x7001L);
    final Set<NodeId> cores = new HashSet<>();

    MbWorld(Settings settings) {
        this.h = new FsmHarness(settings);
        h.commitLeader(FsmHarness.SELF, 1L);
    }

    static MbWorld world(NodeId... cores) {
        var w = new MbWorld(Settings.settings(FsmHarness.SELF, DeploymentAtomicity.ALL_OR_NOTHING, Set.of(), bounds()));

        for (var core : cores) {
            w.cores.add(core);
        }
        w.syncCores();
        return w;
    }

    static MbWorld worldWithSeeds(Set<NodeId> seeds, NodeId... cores) {
        var w = new MbWorld(Settings.settings(FsmHarness.SELF, DeploymentAtomicity.ALL_OR_NOTHING, seeds, bounds()));

        for (var core : cores) {
            w.cores.add(core);
        }
        w.syncCores();
        return w;
    }

    private void syncCores() {
        h.seams = h.seams.withCountedCores(cores).withReady(cores);
    }

    // -- the registry ----------------------------------------------------------------------------------------------

    /// Membership admits `node` at `epoch`: the registry records `token` (nothing when 0, the unknown path), and the
    /// admission is built from what the registry says.
    Admission admit(NodeId node, long epoch, long token) {
        tokens.admit(node, token);
        var admission = admissionOf(node, epoch);

        h.admissions.put(node, admission);
        return admission;
    }

    /// The admission as the registry sees the node now.
    Admission admissionOf(NodeId node, long epoch) {
        return new Admission(epoch, process(tokens.tokenOf(node)));
    }

    // -- fixtures written before activation ------------------------------------------------------------------------

    MbWorld target(Artifact artifact, int instances, String placement) {
        var target = new SliceTargetValue(artifact.version(), instances, 1, Option.none(), placement, FsmHarness.START_WALL, Option.none(), Option.none(), Option.none());

        h.seedQuietly(new SliceTargetKey(artifact.base()), target);
        return this;
    }

    MbWorld directive(NodeId node, ActivationDirectiveValue directive) {
        h.seedQuietly(new ActivationDirectiveKey(node), directive);
        return this;
    }

    MbWorld community(String id, int targetSize, CommunityState state) {
        h.seedQuietly(new CommunityKey(id), Fixtures.community("default", targetSize, state));
        return this;
    }

    /// An instance the way a healthy node leaves it: the goal at `gen`, and the node's row for that generation.
    MbWorld instance(NodeId node, Artifact artifact, SliceGoal goal, SliceObservedState state, Generation gen, long token) {
        h.seedQuietly(new SliceDesiredKey(node, artifact), desired(goal, gen));
        h.seedQuietly(new SliceObservedKey(node, artifact), row(state, 1L, gen, process(token)));
        return this;
    }

    MbWorld routes(NodeId node, Artifact artifact) {
        h.seedQuietly(new NodeRoutesKey(node, artifact), new NodeRoutesValue(List.of(), Epoch.ZERO));
        return this;
    }

    MbWorld record(NodeId node, DepartureState state, long epoch, long token, DepartureCause cause) {
        h.seedQuietly(new NodeDepartureKey(node),
                      new NodeDepartureValue(state, epoch, process(token), cause == null
                                                                           ? Option.none()
                                                                           : Option.some(cause), FsmHarness.START_WALL));
        return this;
    }

    MbWorld seams(UnaryOperator<Seams> change) {
        h.seams = change.apply(h.seams);
        return this;
    }

    // -- while active ----------------------------------------------------------------------------------------------

    MbWorld refreshSeams(UnaryOperator<Seams> change) {
        h.seams = change.apply(h.seams);
        h.deliver(new ClusterInput.SeamsRefreshed(h.seams));
        return this;
    }

    MbWorld activate() {
        h.activate();
        return this;
    }

    /// A committed governor announcement (a LeaderTransaction through the real applier).
    MbWorld announce(String community, NodeId governor, NodeId... members) {
        h.seed(new GovernorAnnouncementKey(community), roster(governor, false, members));
        h.drain();
        return this;
    }

    MbWorld announceDissolved(String community, NodeId governor, NodeId... members) {
        h.seed(new GovernorAnnouncementKey(community), roster(governor, true, members));
        h.drain();
        return this;
    }

    long reannounceMs() {
        return GovernorAnnouncer.DEFAULT_REANNOUNCE_INTERVAL.millis();
    }

    /// The governor re-announces `members` at the real fixed rate for `seconds`, with the local alive view following `alive`.
    MbWorld reannounceFor(String community, NodeId governor, long seconds, Set<NodeId> alive, NodeId... members) {
        var rounds = Math.max(1, seconds * 1000L / reannounceMs());

        for (var i = 0L; i < rounds; i++) {
            announce(community, governor, members);
            refreshSeams(s -> s.withLocalAlive(Option.some(alive)));
            h.advance(reannounceMs());
        }
        return this;
    }

    Option<AetherValue> stored(AetherKey key) {
        return h.store.get(key);
    }

    boolean present(AetherKey key) {
        return stored(key).isPresent();
    }

    Option<NodeDepartureValue> recordOf(NodeId node) {
        return stored(new NodeDepartureKey(node)).filter(NodeDepartureValue.class::isInstance).map(NodeDepartureValue.class::cast);
    }

    boolean counts(NodeId node) {
        return Rows.counts(h.model(), node);
    }

    static SlicePair pair(NodeId node, Artifact artifact) {
        return SlicePair.slicePair(node, artifact);
    }
}
