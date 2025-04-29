package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.cluster.state.Command;

import java.util.List;

public interface Batch<C extends Command> {
    BatchId id();
    List<C> commands();

    static <C extends Command> Batch<C> create(List<C> commands) {
        record batch<C extends Command>(BatchId id, List<C> commands) implements Batch<C> {}

        return new batch<>(BatchId.createRandom(), commands);
    }
} 