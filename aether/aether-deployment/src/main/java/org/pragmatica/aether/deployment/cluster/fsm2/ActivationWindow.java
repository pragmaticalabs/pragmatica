// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.CommunityState;


/// #3815, #3852: the in-flight window of ONE community state-change write. It belongs to that write alone: `write` identifies it (the draft
/// carries the same number, `TxDraft#window`), `from` and `to` are the committed state it leaves and the state it writes. A redraft of the same
/// write keeps the window; a write for another edge opens its own. `deadlineNs` is the monotonic time (ns) the write stops counting as in flight.
public record ActivationWindow(long write, CommunityState from, CommunityState to, long deadlineNs) {
    public static ActivationWindow activationWindow(long write,
                                                    CommunityState from,
                                                    CommunityState to,
                                                    long deadlineNs) {
        return new ActivationWindow(write, from, to, deadlineNs);
    }

    /// The window of a write that leaves `state` for `next`.
    public boolean isFor(CommunityState state, CommunityState next) {
        return from == state && to == next;
    }

    public boolean openAt(long nowNs) {
        return nowNs < deadlineNs;
    }

    public ActivationWindow closedAt(long nowNs) {
        return activationWindow(write, from, to, nowNs);
    }
}
