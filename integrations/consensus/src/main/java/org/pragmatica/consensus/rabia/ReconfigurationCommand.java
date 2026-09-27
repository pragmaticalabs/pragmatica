package org.pragmatica.consensus.rabia;

import java.util.Comparator;
import java.util.Set;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// Rabia §4 special command: Weak-MVC agrees the slot R that carries it, and from slot R+1 the
/// `target` roster governs at epoch `baseEpoch + 1`.
///
/// `baseEpoch` names the configuration the target was computed from. Applied at any other epoch the
/// command is a deterministic no-op that still consumes its slot, so a stale or competing command can
/// never apply on top of a roster it did not see. Every replica evaluates [#successorOf] against the
/// same log prefix, so every replica reaches the same verdict.
///
/// The target is a complete roster, so a single add, a single remove and a one-slot replacement are all
/// expressible. A change is applicable only while the voters it retains from the current roster are a
/// majority of the target: the retained members then carry the applied prefix and keep the new
/// configuration deciding before any added member has caught up.
@Codec
public record ReconfigurationCommand(long baseEpoch, ClusterConfig target) {
    public ReconfigurationCommand {
        target = new ClusterConfig(target.members()
                                         .stream()
                                         .sorted(Comparator.comparing(NodeId::id))
                                         .toList());
    }

    public static ReconfigurationCommand reconfigurationCommand(long baseEpoch, ClusterConfig target) {
        return new ReconfigurationCommand(baseEpoch, target);
    }

    /// The configuration this command installs on top of `current`, or none when it is a no-op there.
    public Option<VoterConfiguration> successorOf(VoterConfiguration current) {
        return appliesTo(current)
               ? Option.some(current.successor(target))
               : Option.none();
    }

    public boolean appliesTo(VoterConfiguration current) {
        return baseEpoch == current.epoch() && isWellFormed() && !current.roster()
                                                                         .sameMembership(target)
               && retainsTargetMajority(current);
    }

    public boolean retainsTargetMajority(VoterConfiguration current) {
        var retained = target.members()
                             .stream()
                             .filter(current::contains)
                             .count();

        return retained >= target.clusterSize() / 2 + 1;
    }

    /// Deterministic tie-break between competing commands of one base epoch: every replica adopts the
    /// same one, so competing requests converge on an identical proposal instead of splitting the slot.
    public boolean preferredOver(ReconfigurationCommand other) {
        if (baseEpoch != other.baseEpoch()) {
            return baseEpoch > other.baseEpoch();
        }

        return key().compareTo(other.key()) < 0;
    }

    private String key() {
        return String.join(",",
                           target.members()
                                 .stream()
                                 .map(NodeId::id)
                                 .toList());
    }

    private boolean isWellFormed() {
        return !target.members()
                      .isEmpty() && Set.copyOf(target.members())
                                       .size() == target.clusterSize();
    }
}
