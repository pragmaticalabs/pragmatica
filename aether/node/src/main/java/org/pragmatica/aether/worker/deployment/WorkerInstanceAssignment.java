package org.pragmatica.aether.worker.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;

import java.util.Comparator;
import java.util.List;


/// Deterministic instance assignment via consistent hashing.
/// All workers evaluate the same function and arrive at the same result.
@SuppressWarnings("JBCT-UTIL-02")
// Utility interface -- static methods only
public sealed interface WorkerInstanceAssignment {
    record unused() implements WorkerInstanceAssignment{}

    static int assignedInstances(Artifact artifact, int targetInstances, List<NodeId> aliveMembers, NodeId self) {
        if (aliveMembers.isEmpty() || targetInstances <= 0) {return 0;}
        var sortedMembers = aliveMembers.stream().sorted(Comparator.comparingInt(member -> hashFor(member, artifact)))
                                               .toList();
        var selfIndex = sortedMembers.indexOf(self);
        if (selfIndex <0) {return 0;}
        var memberCount = sortedMembers.size();
        var baseCount = targetInstances / memberCount;
        var remainder = targetInstances % memberCount;
        return baseCount + (selfIndex <remainder
                            ? 1
                            : 0);
    }

    private static int hashFor(NodeId nodeId, Artifact artifact) {
        return (nodeId.id() + ":" + artifact.asString()).hashCode();
    }
}
