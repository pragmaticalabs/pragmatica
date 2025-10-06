package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

public interface Slice {
    Promise<Unit> start();

    Promise<Unit> stop();

    List<SliceMethod<?, ?>> methods();
}
