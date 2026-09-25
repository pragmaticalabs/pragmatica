package org.pragmatica.consensus.rabia;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.serialization.Codec;


/// Checkpoint at an agreed electorate barrier. Pending requests are not committed evidence.
@Codec
public record ConfigurationHandoff<C extends Command>(VoterConfiguration previous,
                                                      VoterConfiguration next,
                                                      Phase nextSlot,
                                                      byte[] snapshot,
                                                      List<Batch<C>> pendingBatches,
                                                      List<ConfigurationCertificate> history) {
    public ConfigurationHandoff {
        snapshot = snapshot.clone();
        pendingBatches = List.copyOf(pendingBatches);
        history = List.copyOf(history);
    }

    public ConfigurationHandoff(VoterConfiguration previous,
                                VoterConfiguration next,
                                Phase nextSlot,
                                byte[] snapshot,
                                List<Batch<C>> pendingBatches) {
        this(previous, next, nextSlot, snapshot, pendingBatches, List.of());
    }

    public boolean sameCheckpoint(ConfigurationHandoff<?> other) {
        return previous.equals(other.previous())
               && next.equals(other.next())
               && nextSlot.equals(other.nextSlot())
               && Arrays.equals(snapshot, other.snapshot());
    }
}
