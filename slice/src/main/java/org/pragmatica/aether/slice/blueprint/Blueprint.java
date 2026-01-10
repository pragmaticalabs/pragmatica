package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.routing.SliceSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

public record Blueprint(BlueprintId id, List<SliceSpec> slices) {
    private static final Cause NULL_ID = Causes.cause("Blueprint ID cannot be null");
    private static final Cause NULL_SLICES = Causes.cause("Slices list cannot be null");
    private static final Cause EMPTY_SLICES = Causes.cause("Slices list cannot be empty");

    public static Result<Blueprint> blueprint(BlueprintId id, List<SliceSpec> slices) {
        if (id == null) {
            return NULL_ID.result();
        }
        if (slices == null) {
            return NULL_SLICES.result();
        }
        if (slices.isEmpty()) {
            return EMPTY_SLICES.result();
        }
        return Result.success(new Blueprint(id, List.copyOf(slices)));
    }
}
