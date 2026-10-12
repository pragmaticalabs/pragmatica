// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;

import org.pragmatica.lang.Unit;


/// A persistent set over [PMap]: updates share structure, and iteration is a counted scan.
public record PSet<T>(PMap<T, Unit> members) {
    private static final PSet<?> EMPTY = new PSet<>(PMap.empty());

    @SuppressWarnings("unchecked")
    public static <T> PSet<T> empty() {
        return (PSet<T>) EMPTY;
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public boolean contains(T value) {
        return members.containsKey(value);
    }

    public PSet<T> add(T value) {
        return new PSet<>(members.put(value, Unit.unit()));
    }

    public PSet<T> remove(T value) {
        return new PSet<>(members.remove(value));
    }

    public List<T> toList() {
        return members.keys();
    }
}
