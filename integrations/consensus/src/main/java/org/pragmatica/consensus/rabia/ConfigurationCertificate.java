package org.pragmatica.consensus.rabia;

import java.util.List;
import java.util.Set;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;


/// Crash-fault handoff evidence. Identities are authenticated cluster peers, not Byzantine proofs.
@Codec
public record ConfigurationCertificate(VoterConfiguration previous,
                                       VoterConfiguration next,
                                       Phase nextSlot,
                                       List<NodeId> witnesses) {
    public ConfigurationCertificate {
        witnesses = List.copyOf(witnesses);
    }

    public boolean isValid() {
        return VoterConfiguration.voterConfiguration(previous.epoch(),
                                                     previous.members())
                                 .isSuccess()
               && VoterConfiguration.voterConfiguration(next.epoch(),
                                                        next.members())
                                    .isSuccess()
               && nextSlot.value() > 0
               && next.epoch() == previous.epoch() + 1
               && Set.copyOf(witnesses).size() == witnesses.size()
               && witnesses.size() >= previous.quorumSize()
               && witnesses.stream()
                           .allMatch(previous::contains);
    }
}
