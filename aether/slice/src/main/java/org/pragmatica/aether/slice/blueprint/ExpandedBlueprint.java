package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.serialization.Codec;

import java.util.List;

@Codec
public record ExpandedBlueprint(BlueprintId id, List<ResolvedSlice> loadOrder) {
    public static ExpandedBlueprint expandedBlueprint(BlueprintId id, List<ResolvedSlice> loadOrder) {
        return new ExpandedBlueprint(id, List.copyOf(loadOrder));
    }
}
