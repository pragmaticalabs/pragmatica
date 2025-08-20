package org.pragmatica.aether.slice.deployment;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;

/// Range of instances to be deployed
/// Initial number of instances must be less than or equal to the maximum number of instances.
///
/// @param initial Initial number of instances
/// @param max     Maximum number of instances
public record Range(int initial, int max) {
    public static Result<Range> range(int initial, int max) {
        return Result.all(ensure(initial, Verify.Is::positive), ensure(max, Verify.Is::positive))
                     .map(Range::new)
                     .flatMap(range -> ensure(range, r -> r.initial <= r.max));
    }
}
