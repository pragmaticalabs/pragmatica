// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;


/// The submitted blueprint is well-formed but conflicts with the CURRENT state of the cluster: today, an HTTP route one of its
/// slices declares is already declared by a slice of an already-stored blueprint (#1206). That is 409, not the 400 of a
/// malformed request ([BlueprintRejected]); the cause stays reachable through [#origin()] and its message names the stored
/// blueprint and slice.
public record BlueprintConflict(Cause origin, String message) implements Cause.Wrapped, HttpStatusAware {
    public static final Fn1<BlueprintConflict, Cause> FACTORY = origin -> new BlueprintConflict(origin,
                                                                                                "Blueprint conflicts with the cluster: " + origin.message());

    @Override
    public HttpStatus httpStatus() {
        return HttpStatus.CONFLICT;
    }
}
