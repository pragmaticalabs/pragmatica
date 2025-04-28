package org.pragmatica.cluster.net;

public interface NodeConnection<T> {
    NodeInfo info();

    T connection();

    static <T> NodeConnection<T> create(NodeInfo info, T connection) {
        record nodeConnection<T>(NodeInfo info, T connection) implements NodeConnection<T> {}

        return new nodeConnection<>(info, connection);
    }
}
