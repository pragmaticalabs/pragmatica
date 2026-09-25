package org.pragmatica.consensus.rabia;

import java.util.List;

import org.pragmatica.consensus.Command;
import org.pragmatica.serialization.Codec;


/// Application prefix and the still-open slot's durable promises are one atomic checkpoint.
@Codec
public record VotingJournalCheckpoint<C extends Command>(long sequence,
                                                         RabiaPersistence.SavedState<C> state,
                                                         List<RabiaProtocolMessage> retained) {
    public VotingJournalCheckpoint {
        retained = List.copyOf(retained);
    }
}
