// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Failures of `GET /cluster/communities/{id}` (#1652).
public sealed interface CommunityRouteError extends Cause, HttpStatusAware {
    /// 404 — neither a committed `CommunityValue` nor a committed roster exists for the id.
    record CommunityNotFound(String communityId, String message) implements CommunityRouteError {
        static final Fn1<CommunityNotFound, String> FACTORY = Causes.forOneValue("Community '%s' not found",
                                                                                 CommunityNotFound::new);

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_FOUND;
        }
    }
}
