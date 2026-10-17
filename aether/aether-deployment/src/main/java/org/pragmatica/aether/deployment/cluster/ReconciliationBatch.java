// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// Independent reconciliation lanes. A failed or overdue item cannot starve the rest.
/// FER: failures leave durable work pending for the next pass. The deadline bounds the
/// observation, not the external effect: callers must persist uncertainty before dispatch.
interface ReconciliationBatch {
    static <T> Promise<Unit> reconcile(List<T> items,
                                       int concurrency,
                                       TimeSpan deadline,
                                       Function<T, Promise<Unit>> reconcile,
                                       BiConsumer<T, Cause> failure) {
        var lanes = new ArrayList<Promise<Unit>>();

        for (int lane = 0; lane < Math.min(concurrency, items.size()); lane++) {
            lanes.add(lane(items, lane, concurrency, deadline, reconcile, failure));
        }

        return Promise.allOf(lanes).mapToUnit();
    }

    private static <T> Promise<Unit> lane(List<T> items,
                                          int start,
                                          int stride,
                                          TimeSpan deadline,
                                          Function<T, Promise<Unit>> reconcile,
                                          BiConsumer<T, Cause> failure) {
        var pass = Promise.unitPromise();

        for (int index = start; index < items.size(); index += stride) {
            var item = items.get(index);

            pass = pass.flatMap(_ -> attempt(item, deadline, reconcile, failure));
        }

        return pass;
    }

    private static <T> Promise<Unit> attempt(T item,
                                             TimeSpan deadline,
                                             Function<T, Promise<Unit>> reconcile,
                                             BiConsumer<T, Cause> failure) {
        return reconcile.apply(item)
                        .timeout(deadline)
                        .onFailure(cause -> failure.accept(item, cause))
                        .fold(_ -> Promise.unitPromise());
    }
}
