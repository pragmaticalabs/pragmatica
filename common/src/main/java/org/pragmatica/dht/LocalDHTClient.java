package org.pragmatica.dht;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/**
 * Local DHT client implementation that operates on a single node.
 * Suitable for testing and single-node deployments.
 *
 * @param <N> Node identifier type
 */
public final class LocalDHTClient<N extends Comparable<N>> implements DHTClient {
    private final DHTNode<N> node;

    private LocalDHTClient(DHTNode<N> node) {
        this.node = node;
    }

    /**
     * Create a local DHT client backed by the given node.
     */
    public static <N extends Comparable<N>> LocalDHTClient<N> localDHTClient(DHTNode<N> node) {
        return new LocalDHTClient<>(node);
    }

    @Override
    public Promise<Option<byte[] >> get(byte[] key) {
        return node.getLocal(key);
    }

    @Override
    public Promise<Unit> put(byte[] key, byte[] value) {
        return node.putLocal(key, value);
    }

    @Override
    public Promise<Boolean> remove(byte[] key) {
        return node.removeLocal(key);
    }

    @Override
    public Promise<Boolean> exists(byte[] key) {
        return node.existsLocal(key);
    }

    @Override
    public Partition partitionFor(byte[] key) {
        return node.partitionFor(key);
    }

    /**
     * Get the underlying node.
     */
    public DHTNode<N> node() {
        return node;
    }
}
