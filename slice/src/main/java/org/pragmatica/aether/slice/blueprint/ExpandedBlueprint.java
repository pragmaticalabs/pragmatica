package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.routing.RoutingSection;

import java.util.List;

public record ExpandedBlueprint(BlueprintId id, List<ResolvedSlice> loadOrder, List<RoutingSection> routing) {
    public static ExpandedBlueprint expandedBlueprint(BlueprintId id,
                                                      List<ResolvedSlice> loadOrder,
                                                      List<RoutingSection> routing) {
        return new ExpandedBlueprint(id, loadOrder, routing);
    }
}
