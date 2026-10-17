// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator.CoordinatorError;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The consumer-group coordinator refuses a join or leave with an untyped `CoordinatorError.NOT_LEADER` while it is dormant on this
/// node, which `ProblemResponses` resolved to 500 (#1921). That is the not-leader refusal every other leader-bound route answers
/// as 409 ([ManagementServerError.NotLeader]); it is not a server fault.
public final class CoordinatorRefusal {
    private CoordinatorRefusal() {}

    public static Result<Unit> typed(Result<Unit> outcome) {
        return outcome.mapError(cause -> cause == CoordinatorError.NOT_LEADER
                                         ? new ManagementServerError.NotLeader("")
                                         : cause);
    }
}
