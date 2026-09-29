// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.slice.blueprint.SliceSpec;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// A `POST /api/v1/scale` request refused for the count it asks for. Both are malformed requests, so both
/// answer 400: an untyped cause would reach `ProblemResponses.resolveStatus` and default to 500.
public sealed interface ScaleRouteError extends Cause, HttpStatusAware {
    @Override
    default HttpStatus httpStatus() {
        return HttpStatus.BAD_REQUEST;
    }

    /// Fewer than [SliceSpec#MIN_INSTANCES] instances (#1495, owner ruling: the floor holds at runtime, not
    /// only when a blueprint is declared).
    record InstancesBelowFloor(int requested, String message) implements ScaleRouteError {
        static final Fn1<InstancesBelowFloor, Integer> FACTORY = Causes.forOneValue("Requested %s instances; a slice must run at least " + SliceSpec.MIN_INSTANCES
                                                                                   + " instances",
                                                                                    InstancesBelowFloor::new);
    }

    /// Fewer instances than the slice's own `minAvailable`.
    record InstancesBelowMinAvailable(int requested, int minAvailable, String message) implements ScaleRouteError {
        static final Fn2<InstancesBelowMinAvailable, Integer, Integer> FACTORY = Causes.forTwoValues("Requested %s instances but the slice's minAvailable is %s",
                                                                                                     InstancesBelowMinAvailable::new);
    }
}
