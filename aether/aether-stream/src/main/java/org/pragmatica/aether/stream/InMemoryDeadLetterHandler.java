// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.stream.DeadLetterHandler.DeadLetterEntry;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;


/// VOLATILE in-memory sink — entries do not survive process restart (rc3 audit note on #386):
/// a retry-exhausted event recorded here is gone after a node restart, silently. Acceptable for
/// Forge and tests, where the process lifetime IS the test lifetime; a production deployment
/// needs a durable sink (durable-pubsub-spec §9's DLQ-stream path, D3). The append never fails,
/// which is exactly the property that makes it unsuitable as evidence the failure-aware contract
/// works — durable-sink tests must use a failing stub, not this class.
final class InMemoryDeadLetterHandler implements DeadLetterHandler {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<DeadLetterEntry>> entries = new ConcurrentHashMap<>();

    @Override
    public Promise<Unit> append(String streamName,
                                int partition,
                                long offset,
                                String failingGroup,
                                byte[] payload,
                                String errorMessage,
                                int attemptCount) {
        var entry = DeadLetterEntry.deadLetterEntry(streamName,
                                                    partition,
                                                    offset,
                                                    failingGroup,
                                                    payload,
                                                    errorMessage,
                                                    attemptCount,
                                                    System.currentTimeMillis());

        entries.computeIfAbsent(streamName, _ -> new CopyOnWriteArrayList<>()).add(entry);

        return Promise.unitPromise();
    }

    @Override
    public List<DeadLetterEntry> read(String streamName, int maxCount) {
        return option(entries.get(streamName)).map(list -> list.stream()
                                                               .limit(maxCount)
                                                               .toList())
                     .or(List.of());
    }
}
