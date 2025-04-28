package org.pragmatica.cluster.net;

import org.pragmatica.utility.ULID;

public record NodeId(String id) {
    @Override
    public String toString() {
        return id;
    }

    static NodeId create(String id) {
        return new NodeId(id);
    }

    static NodeId createRandom() {
        return create(ULID.randomULID().encoded());
    }
}
