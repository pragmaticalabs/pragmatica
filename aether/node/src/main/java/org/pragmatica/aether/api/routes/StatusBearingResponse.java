// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.http.HttpStatus;


/// A successful route value that declares the status [ManagementRouter] writes with its body (#1354). The body is
/// the value, serialized as usual; only the status differs from the default `200`. The status is DERIVED by the
/// value itself from what it carries, so no route call site picks one and the two cannot disagree.
///
/// This is the success-side twin of `HttpStatusAware` (a failure's status): `HttpStatusAware` types a refusal, this
/// types an answer that ran but was not wholly successful, such as a partial `publish-batch` (`207`).
public interface StatusBearingResponse {
    HttpStatus httpStatus();
}
