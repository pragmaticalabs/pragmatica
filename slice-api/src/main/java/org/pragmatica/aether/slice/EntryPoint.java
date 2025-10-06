package org.pragmatica.aether.slice;

import org.pragmatica.lang.type.TypeToken;

import java.util.List;

@Deprecated
public record EntryPoint(EntryPointId id, List<TypeToken<?>> parameterTypes) {
    public static EntryPoint entry(EntryPointId id, List<TypeToken<?>> parameterTypes) {
        return new EntryPoint(id, parameterTypes);
    }
}
