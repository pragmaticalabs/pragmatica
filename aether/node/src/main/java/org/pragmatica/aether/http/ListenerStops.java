// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;

import static org.pragmatica.lang.Promise.resolved;
import static org.pragmatica.lang.Unit.unit;


/// How the node's HTTP/1.1 + HTTP/3 listener pairs are stopped (#1612).
///
/// A listener's `stop()` can FAIL, for example when a channel close or an event-loop termination times out.
/// Both the management server and the app HTTP server stop a pair and then either finish (shutdown) or restart
/// (certificate rotation). These two helpers are shared so that both servers handle the failure the same way.
public sealed interface ListenerStops {
    /// Resolves once BOTH stops have settled, with the first failure among them. Both stops are already under
    /// way when this is called. `h1Stop.flatMap(_ -> h3Stop)` would instead return h1's failure while h3 was
    /// still stopping, and would drop h3's failure whenever h1 failed.
    static Promise<Unit> bothStopped(Promise<Unit> h1Stop, Promise<Unit> h3Stop) {
        return h1Stop.fold(h1Outcome -> h3Stop.fold(h3Outcome -> resolved(h1Outcome.flatMap(_ -> h3Outcome))));
    }

    /// For certificate rotation: a failed stop is logged and the restart goes ahead. This is forward recovery (FER).
    /// Aborting would leave the node with no listener at all, while a restart succeeds whenever the channel did
    /// close, and a port still bound makes the restart fail visibly.
    static Promise<Unit> stoppedForRestart(Promise<Unit> stop, Logger log, String listeners) {
        return stop.onFailure(cause -> log.warn("{} did not stop cleanly before certificate rotation; restarting anyway: {}",
                                                listeners,
                                                cause.message()))
                   .recover(_ -> unit());
    }

    record unused() implements ListenerStops {}
}
