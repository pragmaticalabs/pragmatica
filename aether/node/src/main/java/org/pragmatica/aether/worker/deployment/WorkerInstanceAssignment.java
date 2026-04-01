package org.pragmatica.aether.worker.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;

import java.util.Comparator;
import java.util.List;

/// Deterministic instance assignment via consistent hashing.
/// All workers evaluate the same function and arrive at the same result.
@SuppressWarnings("JBCT-UTIL-02") // Utility interface -- static methods only
public sealed interface WorkerInstanceAssignment {
    record unused() implements WorkerInstanceAssignment{}

    /// Compute how many instances this worker should run for the given artifact.
    ///
    /// Algorithm: sort members by hash(memberId + artifact), round-robin assign targetInstances.
    /// Must be deterministic: same inputs -> same output on every worker.
    ///
    /// @param artifact         the slice artifact
    /// @param targetInstances  total desired instances across worker pool
    /// @param aliveMembers     list of alive SWIM members (by nodeId)
    /// @param self             this worker's NodeId
    /// @return number of instances this worker should run (0 if none assigned)
    static int assignedInstances(Artifact artifact,
                                 int targetInstances,
                                 List<NodeId> aliveMembers,
                                 NodeId self) {
        if ( aliveMembers.isEmpty() || targetInstances <= 0) {
        return 0;}
        var sortedMembers = aliveMembers.stream().sorted(Comparator.comparingInt(member -> hashFor(member, artifact)))
                                               .toList();
        var selfIndex = sortedMembers.indexOf(self);
        if ( selfIndex < 0) {
        return 0;}
        var memberCount = sortedMembers.size();
        var baseCount = targetInstances / memberCount;
        var remainder = targetInstances % memberCount;
        return baseCount + (selfIndex < remainder
                            ? 1
                            : 0);
    }

    private static int hashFor(NodeId nodeId, Artifact artifact) {
        return (nodeId.id() + ":" + artifact.asString()).hashCode();
    }
}
