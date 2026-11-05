// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.cluster.state.kvstore.NotificationDelivery.OperatorEvents;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.lang.Unit.unit;


/// Where the node store's KV dispatcher events become operator warnings (KVStore dispatcher design §3.6, operator-attention
/// rule): `kv-dispatch-slow-handler` and its recovery, `kv-dispatch-read-ahead` and `kv-dispatcher-failed`. The store is
/// built before the node's event layer, so the sink is bound later; until then the dispatcher's own log line is the whole
/// report. The dispatcher has already logged each event, so this hands it to the sink without a second log line.
public final class KvDispatchOperatorEvents implements OperatorEvents {
    private final AtomicReference<OperatorWarningSink> sink = new AtomicReference<>(OperatorWarningSink.logOnly());

    private KvDispatchOperatorEvents() {}

    public static KvDispatchOperatorEvents kvDispatchOperatorEvents() {
        return new KvDispatchOperatorEvents();
    }

    /// Where the events go from now on.
    @Contract
    public void operatorWarnings(OperatorWarningSink sink) {
        this.sink.set(sink);
    }

    /// Never blocks: every sink hands the warning off (`OperatorWarningSink` is sealed for that, #1617 R2).
    @Override
    public Unit emit(OperatorWarningCode code, String subject, String detail) {
        sink.get().accept(OperatorWarning.operatorWarning(code, subject, detail));

        return unit();
    }
}
