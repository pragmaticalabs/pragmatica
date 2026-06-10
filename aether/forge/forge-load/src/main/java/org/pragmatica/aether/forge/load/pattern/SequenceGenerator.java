// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;


public final class SequenceGenerator implements PatternGenerator {
    public static final String TYPE = "seq";

    private final long start;
    private final AtomicLong counter;

    public static SequenceGenerator sequenceGenerator(long start) {
        return new SequenceGenerator(start);
    }

    SequenceGenerator(long start) {
        this.start = start;
        this.counter = new AtomicLong(start);
    }

    public static Result<PatternGenerator> sequenceGenerator(String seqSpec) {
        var trimmed = option(seqSpec).map(String::trim).filter(s -> !s.isBlank());

        return trimmed.map(SequenceGenerator::toLongAndCreate)
                      .or(success(sequenceGenerator(1)));
    }

    private static Result<PatternGenerator> toLongAndCreate(String s) {
        return Number.parseLong(s).map(SequenceGenerator::sequenceGenerator);
    }

    @Override
    public String generate() {
        return String.valueOf(counter.getAndIncrement());
    }

    @Override
    public String pattern() {
        return "${seq:" + start + "}";
    }

    public Result<Unit> reset() {
        counter.set(start);

        return unitResult();
    }

    public long current() {
        return counter.get();
    }
}
