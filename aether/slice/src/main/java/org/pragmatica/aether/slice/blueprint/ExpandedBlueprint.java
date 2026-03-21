package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import java.util.List;

@Codec
public record ExpandedBlueprint(BlueprintId id,
                                List<ResolvedSlice> loadOrder,
                                Option<String> resourcesConfig) {
    public static ExpandedBlueprint expandedBlueprint(BlueprintId id, List<ResolvedSlice> loadOrder) {
        return new ExpandedBlueprint(id, List.copyOf(loadOrder), Option.none());
    }

    public static ExpandedBlueprint expandedBlueprint(BlueprintId id,
                                                      List<ResolvedSlice> loadOrder,
                                                      Option<String> resourcesConfig) {
        return new ExpandedBlueprint(id, List.copyOf(loadOrder), resourcesConfig);
    }
}
