// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.DeadLetterHandler.DeadLetterEntry;
import org.pragmatica.lang.Contract;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.pragmatica.lang.Option.option;


final class InMemoryDeadLetterHandler implements DeadLetterHandler {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<DeadLetterEntry>> entries = new ConcurrentHashMap<>();

    @Contract
    @Override
    public void record(String streamName,
                       int partition,
                       long offset,
                       byte[] payload,
                       String errorMessage,
                       int attemptCount) {
        var entry = DeadLetterEntry.deadLetterEntry(streamName,
                                                    partition,
                                                    offset,
                                                    payload,
                                                    errorMessage,
                                                    attemptCount,
                                                    System.currentTimeMillis());

        entries.computeIfAbsent(streamName, _ -> new CopyOnWriteArrayList<>()).add(entry);
    }

    @Override
    public List<DeadLetterEntry> read(String streamName, int maxCount) {
        return option(entries.get(streamName)).map(list -> list.stream()
                                                               .limit(maxCount)
                                                               .toList())
                     .or(List.of());
    }
}
