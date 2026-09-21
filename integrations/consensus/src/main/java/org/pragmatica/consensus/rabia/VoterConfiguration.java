package org.pragmatica.consensus.rabia;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;


/// An immutable electorate. Provisioning targets and local health are not voting authority.
@Codec
public record VoterConfiguration(long epoch, ClusterConfig roster) {
    public VoterConfiguration {
        roster = new ClusterConfig(roster.members().stream().sorted(java.util.Comparator.comparing(NodeId::id)).toList());
    }

    public static Result<VoterConfiguration> voterConfiguration(long epoch, List<NodeId> members) {
        if (epoch < 0) {
            return Error.NEGATIVE_EPOCH.result();
        }

        return ClusterConfig.clusterConfig(members).map(roster -> new VoterConfiguration(epoch, roster));
    }

    public List<NodeId> members() {
        return roster.members();
    }

    public boolean contains(NodeId node) {
        return members().contains(node);
    }

    public int quorumSize() {
        return members().size() / 2 + 1;
    }

    public int fPlusOne() {
        return members().size() - quorumSize() + 1;
    }

    public VoterConfiguration successor(ClusterConfig next) {
        return new VoterConfiguration(epoch + 1, next);
    }

    public enum Error implements Cause {
        NEGATIVE_EPOCH;
        @Override
        public String message() {
            return "Voter epoch cannot be negative";
        }
    }
}
