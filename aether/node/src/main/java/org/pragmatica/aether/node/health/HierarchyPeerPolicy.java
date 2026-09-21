// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node.health;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.ConnectionDirection;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Unit.unit;


/// Control connections and direct probes have a hierarchy-sized audience. Application dependency
/// connections are supplied independently and never enlarge the health observation scope.
public record HierarchyPeerPolicy(NodeId self, boolean worker, AtomicReference<View> current) {
    public record View(Set<NodeId> directPeers, Set<NodeId> swimPeers, boolean forwardsPeerMetrics) {
        public View {
            directPeers = Set.copyOf(directPeers);
            swimPeers = Set.copyOf(swimPeers);
        }
    }

    public static HierarchyPeerPolicy hierarchyPeerPolicy(NodeId self, boolean worker) {
        return new HierarchyPeerPolicy(self,
                                       worker,
                                       new AtomicReference<>(new View(Set.of(), Set.of(self), !worker)));
    }

    /// Workers initiate their core uplinks without waiting for discovery in the opposite direction.
    /// Application dependencies are directed edges: the destination need not request a reverse link.
    /// Role facts are independent of liveness; symmetric peers retain the default tie-break.
    public boolean isConnectionInitiator(NodeId peer,
                                         boolean peerCore,
                                         boolean peerWorker,
                                         boolean applicationDependency) {
        if (applicationDependency) {
            return true;
        }

        if (worker && peerCore) {
            return true;
        }

        if (!worker && !peerCore && peerWorker) {
            return false;
        }

        return ConnectionDirection.shouldInitiate(self, peer);
    }

    /// A staged core must contact its configured state-transfer seeds before those peers know it.
    /// This grants transport initiation only; admission and voter authority remain independent.
    public boolean initiatesCoreBootstrap(boolean installedVoter, boolean configuredTransferPeer) {
        return ! worker
               && !installedVoter
               && configuredTransferPeer;
    }

    public Unit refresh(Set<NodeId> cores,
                        Set<NodeId> governors,
                        Set<NodeId> ownCommunity,
                        Option<NodeId> ownGovernor,
                        Option<NodeId> leader,
                        Set<NodeId> reachableCores) {
        current.set(worker
                    ? workerView(cores, ownCommunity, ownGovernor, leader, reachableCores)
                    : coreView(cores, governors));

        return unit();
    }

    private View coreView(Set<NodeId> cores, Set<NodeId> governors) {
        var peers = new HashSet<>(cores);

        peers.addAll(governors);
        peers.add(self);

        return new View(peers, peers, true);
    }

    private View workerView(Set<NodeId> cores,
                            Set<NodeId> members,
                            Option<NodeId> governor,
                            Option<NodeId> leader,
                            Set<NodeId> reachableCores) {
        var available = new HashSet<>(reachableCores);

        available.retainAll(cores);
        var peers = new HashSet<>(coreUplinks(self,
                                              available.isEmpty()
                                              ? cores
                                              : available));

        leader.filter(cores::contains).onPresent(peers::add);
        governor.onPresent(peers::add);
        boolean ownsCommunity = governor.filter(self::equals).isPresent();

        if (ownsCommunity) {
            peers.addAll(members);
        }

        var observationPeers = new HashSet<>(members);

        observationPeers.addAll(cores);
        observationPeers.add(self);

        return new View(peers, observationPeers, ownsCommunity);
    }

    public boolean shouldPing(NodeId peer) {
        return current.get()
                      .directPeers()
                      .contains(peer) && !self.equals(peer);
    }

    public boolean shouldConnect(NodeId peer) {
        return shouldPing(peer);
    }

    public boolean shouldObserve(NodeId peer) {
        return current.get()
                      .swimPeers()
                      .contains(peer);
    }

    public boolean forwardsPeerMetrics() {
        return current.get()
                      .forwardsPeerMetrics();
    }

    /// Two deterministic uplinks distribute community traffic and retain a second path on failure.
    static Set<NodeId> coreUplinks(NodeId self, Set<NodeId> cores) {
        return cores.stream()
                    .sorted(Comparator.<NodeId> comparingLong(peer -> score(self, peer))
                                      .reversed()
                                      .thenComparing(NodeId::id))
                    .limit(2)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static long score(NodeId self, NodeId peer) {
        long hash = 0xcbf29ce484222325L;

        for (byte value : (self.id() + "/" + peer.id()).getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
        }
        // Mix the suffix-sensitive FNV result before ranking nearby generated node identities.
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;

        return hash ^ (hash >>> 33);
    }
}
