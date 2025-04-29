package org.pragmatica.cluster.net;

import org.pragmatica.utility.ULID;

public interface NodeId {
    String id();

    static NodeId create(String id) {
        record nodeId(String id) implements NodeId {}
        return new nodeId(id);
    }

    static NodeId createRandom() {
        return create(ULID.randomULID().encoded());
    }
}
