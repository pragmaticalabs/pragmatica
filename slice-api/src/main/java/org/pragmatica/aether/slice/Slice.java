package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

public interface Slice {
    default Promise<Unit> start() {
        return Result.unitResult().async();
    }

    default Promise<Unit> stop() {
        return Result.unitResult().async();
    }

    List<SliceMethod<?, ?>> methods();

    /**
     * HTTP routes this slice handles.
     * Routes are registered automatically during slice activation.
     * Default implementation returns empty list (no routes).
     *
     * @return list of route definitions
     */
    default List<SliceRoute> routes() {
        return List.of();
    }
}
