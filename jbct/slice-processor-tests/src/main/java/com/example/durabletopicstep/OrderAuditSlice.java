// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.durabletopicstep;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Host slice for the transitive context-carrying subscriber fixture: it declares no subscription
/// itself, and picks one up through its [OrderAuditListener] step.
@Slice
public interface OrderAuditSlice {
    Promise<AuditReport> report(AuditQuery query);

    static OrderAuditSlice orderAuditSlice(OrderAuditListener listener) {
        return _ -> Promise.success(new AuditReport(0L));
    }
}
