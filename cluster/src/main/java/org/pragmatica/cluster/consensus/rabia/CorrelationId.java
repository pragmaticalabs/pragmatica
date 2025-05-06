package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.utility.ULID;

public interface CorrelationId {
    String id();

    static CorrelationId create(String id) {
        record correlationId(String id) implements CorrelationId {}

        return new correlationId(id);
    }

    static CorrelationId createRandom() {
        return create(ULID.randomULID().encoded());
    }

    static CorrelationId none() {
        return create("");
    }
}
