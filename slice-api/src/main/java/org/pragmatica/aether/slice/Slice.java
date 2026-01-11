package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

public interface Slice {
    default Promise<Unit> start() {
        return Result.unitResult()
                     .async();
    }

    default Promise<Unit> stop() {
        return Result.unitResult()
                     .async();
    }

    List<SliceMethod< ?, ?>> methods();
}
