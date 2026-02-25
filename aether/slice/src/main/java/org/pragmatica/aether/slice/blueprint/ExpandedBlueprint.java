package org.pragmatica.aether.slice.blueprint;

import java.util.List;

public record ExpandedBlueprint(BlueprintId id, List<ResolvedSlice> loadOrder) {
    public static ExpandedBlueprint expandedBlueprint(BlueprintId id, List<ResolvedSlice> loadOrder) {
        return new ExpandedBlueprint(id, List.copyOf(loadOrder));
    }
}
