package org.pragmatica.cluster.consensus.weakmvc;

import org.pragmatica.cluster.state.Command;

import java.util.List;

/// Represents a proposal value in the Weak MVC protocol.
public record Batch<C extends Command>(BatchId id, long timestamp, List<C> commands) implements Comparable<Batch<C>> {
    @Override
    public int compareTo(Batch<C> o) {
        return Long.compare(timestamp(), o.timestamp());
    }

    public static <C extends Command> Batch<C> create(List<C> commands) {
        return new Batch<C>(BatchId.createRandom(), System.nanoTime(), commands);
    }

    public static <C extends Command> Batch<C> empty() {
        return new Batch<>(BatchId.createEmpty(), System.nanoTime(), List.of());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
