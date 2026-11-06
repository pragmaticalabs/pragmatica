// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;


/// A real hand-off [OperatorWarningSink] and a FIFO barrier over it. The sink publishes on one worker in order, so a snapshot taken
/// at once could miss a warning in flight. [#settled] raises a marker through the SAME sink and waits until the publisher has seen
/// it: every warning raised before the marker has then been published, with no sleep deciding the outcome. A raise that lands after
/// the marker is not in the snapshot, so a late raise fails an assertion that expects it, and a duplicate fails one that expects
/// exactly one.
final class WarningProbe {
    private static final String BARRIER_SUBJECT = "barrier-marker";

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(this::publish);
    private volatile Promise<Unit> barrier = Promise.promise();

    OperatorWarningSink sink() {
        return sink;
    }

    List<OperatorWarning> settled() {
        var arrived = Promise.<Unit> promise();

        barrier = arrived;
        sink.accept(OperatorWarning.operatorWarning(OperatorWarningCode.WORKFLOW_RESOLVED, BARRIER_SUBJECT, "barrier"));
        assertThat(arrived.await(TimeSpan.timeSpan(10).seconds()).isSuccess()).as("the marker reached the publisher").isTrue();

        return List.copyOf(warnings);
    }

    private void publish(OperatorWarning warning) {
        if (BARRIER_SUBJECT.equals(warning.subject())) {
            barrier.succeed(Unit.unit());
        } else {
            warnings.add(warning);
        }
    }
}
