// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The narrowest sink of a DRAIN request: every writer of a DRAIN passes through one implementation.
public interface DrainSink {
    /// Requests a drain of `target`; `gate` runs once, in the continuation, before the write.
    Promise<Unit> request(NodeId target, DrainGate gate);
    /// The request already in flight for `target`, if any.
    Option<Promise<Unit>> inFlight(NodeId target);
}
