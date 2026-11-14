// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Fires a claimed job. Firing is AT-LEAST-ONCE: the same job can be fired again after a crash or a pause that
/// outlives the lease, including after the side effect already happened, so a handler MUST be idempotent. A handler
/// whose own writes must not be applied by a superseded firing fences them with [FiredJob#fenceToken]: the token of
/// a later firing of the same job is always greater.
///
/// A successful promise completes the job; a failed one (or a thrown exception) reschedules it after the configured
/// retry delay.
@FunctionalInterface
public interface JobHandler {
    Promise<Unit> fire(FiredJob job);
}
