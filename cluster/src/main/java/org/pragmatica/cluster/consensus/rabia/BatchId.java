package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.utility.ULID;

public interface BatchId {
    String id();

    static BatchId create(String id) {
        record batchId(String id) implements BatchId {}

        return new batchId(id);
    }

    static BatchId createRandom() {
        return create(ULID.randomULID().encoded());
    }
}
