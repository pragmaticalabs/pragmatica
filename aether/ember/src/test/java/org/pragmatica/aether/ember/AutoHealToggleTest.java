// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2939: the helper arm b disables auto-heal with retries a CONFLICT refusal from a young cluster and reports every other outcome as it is.
class AutoHealToggleTest {
    private static final TimeSpan ATTEMPT = TimeSpan.timeSpan(5).seconds();

    @Test
    void aConflictRefusal_isRetriedUntilTheToggleCommits() {
        var calls = new AtomicInteger();

        var outcome = AutoHealToggle.disable(() -> calls.incrementAndGet() <= 3
                                                    ? HierarchyStateWriter.Refusal.CONFLICT.<Boolean>promise()
                                                    : Promise.success(true),
                                             ATTEMPT,
                                             10_000L);

        assertThat(outcome).isEqualTo(AutoHealToggle.DISABLED);
        assertThat(calls).hasValue(4);
    }

    @Test
    void anInactiveRefusal_isNotRetried_andIsNamed() {
        var calls = new AtomicInteger();

        var outcome = AutoHealToggle.disable(() -> {
            calls.incrementAndGet();
            return HierarchyStateWriter.Refusal.INACTIVE.<Boolean>promise();
        }, ATTEMPT, 10_000L);

        assertThat(outcome).isEqualTo(HierarchyStateWriter.Refusal.INACTIVE.message());
        assertThat(calls).hasValue(1);
    }

    @Test
    void aConflictThatOutlastsTheBudget_failsLoudlyWithItsCause() {
        var outcome = AutoHealToggle.disable(() -> HierarchyStateWriter.Refusal.CONFLICT.<Boolean>promise(), ATTEMPT, 300L);

        assertThat(outcome).isEqualTo(HierarchyStateWriter.Refusal.CONFLICT.message());
    }
}
