package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;

public record Blueprint(BlueprintId id, List<SliceSpec> slices) {
    private static final Cause NULL_ID = Causes.cause("Blueprint ID cannot be null");
    private static final Cause NULL_SLICES = Causes.cause("Slices list cannot be null");
    private static final Cause EMPTY_SLICES = Causes.cause("Slices list cannot be empty");

    public static Result<Blueprint> blueprint(BlueprintId id, List<SliceSpec> slices) {
        return Result.all(ensure(id, Is::notNull, NULL_ID),
                          ensure(slices, Is::notNull, NULL_SLICES))
                     .flatMap((i, s) -> s.isEmpty()
                                        ? EMPTY_SLICES.result()
                                        : Result.success(new Blueprint(i,
                                                                       List.copyOf(s))));
    }
}
