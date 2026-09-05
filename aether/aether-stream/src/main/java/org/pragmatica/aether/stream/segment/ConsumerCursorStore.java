// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Where a consumer's committed offset lives.
///
/// Extracted from [CursorStore] (#488) so the consumer runtime can be handed something other than
/// the node-local disk store. [CursorStore] itself remains the node-local implementation; the node
/// composes it with a consensus-KV cursor so a declarative consumer resumes after its partition's
/// owner changes — the local store is unreadable from the node that takes over, since its ref index
/// is per-node and never replicated.
///
/// `fetch` returning [Option#empty] means "no cursor recorded", which callers read as
/// create-from-earliest (offset 0), NOT as "start at the head".
public interface ConsumerCursorStore {
    Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset);
    Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition);

    /// #654 round 2: a store composed of sub-stages (e.g. the node's cluster-aware store, which
    /// chains a consensus checkpoint publish after the local commit) may recover an inner stage's
    /// failure rather than fail `commit(...)` itself, per its own documented rule for what a
    /// sub-stage failure is allowed to do to the outer result. When it does, it reports the detail
    /// here so the runtime still counts and surfaces the failure even though `commit(...)`'s Promise
    /// settled successfully. The runtime reads this immediately after `commit(...)` resolves — it
    /// reflects only the most recent attempt for this key, not an accumulating log. A store with no
    /// such sub-stages (e.g. the plain local store) always returns [Option#empty].
    default Option<String> lastRecoveredFailure(String consumerGroup, String streamName, int partition) {
        return Option.empty();
    }
}
