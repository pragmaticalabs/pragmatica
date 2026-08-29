// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.durabletopicstep;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Host slice for the transitive context-carrying subscriber fixture: it declares no subscription
/// itself, and picks one up through its [OrderAuditListener] step.
///
/// Its own method carries an interceptor ([WithAuditRetry]) on purpose. That makes this the
/// falsifying case for the D5 interceptor rule: the refusal of interceptor-plus-MessageContext is
/// scoped to the slice's OWN methods, because only those are walked by the generated wrapper. If
/// that scope were widened bluntly to "the slice has interceptors anywhere", this legal combination
/// would be refused and this module would fail to compile.
@Slice
public interface OrderAuditSlice {
    @WithAuditRetry
    Promise<AuditReport> report(AuditQuery query);

    static OrderAuditSlice orderAuditSlice(OrderAuditListener listener) {
        return _ -> Promise.success(new AuditReport(0L));
    }
}
