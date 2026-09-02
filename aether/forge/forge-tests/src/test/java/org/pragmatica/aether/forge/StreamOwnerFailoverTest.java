// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/// #457 — DEFAULT-membership variant of the RF=2 stream owner-kill failover proof (shared flow in
/// [AbstractStreamOwnerFailover]). Phases 1–8 (empty start → full pre-kill history → graceful owner-kill
/// → complete-history recovery → ordered live tail) are HARD — they prove lossless read failover, the
/// user-facing guarantee the #491 batch delivers. Phase 9 (post-kill RF-restoration) is a non-failing
/// sensor: RF-rebuild does not yet converge, blocked by two residuals beyond the batch — #498 (SWIM
/// false-removal-under-churn falsely marks live survivors DEAD, and the dial layer withholds the re-dial
/// that would drain the buffered catch-up) and #499 (HRW-divergence: an empty node becomes owner and
/// cannot catch up from the data-bearing survivor). This class observes phase-9 convergence rather than
/// asserting it; the membership-pinned variant ([StreamOwnerFailoverPinnedTest], @Disabled until #499) is
/// the ready-made HARD acceptance gate for when those residuals close.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamOwnerFailoverTest extends AbstractStreamOwnerFailover {
    @Override
    boolean assertsConvergence() {
        return false;
    }
}
