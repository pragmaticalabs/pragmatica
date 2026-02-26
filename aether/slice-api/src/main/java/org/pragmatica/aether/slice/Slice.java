package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.SliceCodec;

import java.util.List;

public interface Slice {
    default Promise<Unit> start() {
        return Promise.unitPromise();
    }

    default Promise<Unit> stop() {
        return Promise.unitPromise();
    }

    List<SliceMethod<?, ?>> methods();

    default SliceCodec codec(SliceCodec parent) {
        return parent;
    }
}
