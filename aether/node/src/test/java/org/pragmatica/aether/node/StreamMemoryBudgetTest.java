// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.StreamMemoryBudget.StreamMemoryBudgetError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// NT-22 (#2201 C-03, spec 4.4 items 1-3): the node's stream memory budget is `STREAM_MAX_MEMORY_BYTES` over
/// `[streaming] max_memory_bytes` over the derived default; a set variable that is malformed or not positive refuses the boot
/// naming the variable (no silent fallback, D-12); the sealer's pending-seal heap cap is decoupled from the budget.
class StreamMemoryBudgetTest {
    private static final long MIB = 1024 * 1024L;
    private static final long FOUR_GIB = 4096 * MIB;
    private static final StreamingConfig DERIVED = StreamingConfig.streamingConfig();
    private static final StreamingConfig CONFIGURED = DERIVED.withMemoryBudget(300 * MIB, StreamingConfig.DERIVE_SEAL_PENDING_CAP_BYTES);

    @Test
    void streamMemoryBudget_theVariableWins_overTheKey_andTheKeyWins_overTheDerivedDefault() {
        assertThat(budget(Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, "200MB"), CONFIGURED).maxMemoryBytes()).as("env over TOML")
                                                                                                    .isEqualTo(200 * MIB);
        assertThat(budget(Map.of(), CONFIGURED).maxMemoryBytes()).as("TOML over derived").isEqualTo(300 * MIB);
        assertThat(budget(Map.of(), DERIVED).maxMemoryBytes()).as("derived: an eighth of 4 GiB").isEqualTo(512 * MIB);
        assertThat(budget(Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, "  "), CONFIGURED).maxMemoryBytes()).as("a blank variable is unset")
                                                                                                .isEqualTo(300 * MIB);
        assertThat(budget(Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, " 134217728 "), DERIVED).maxMemoryBytes())
            .as("a whole number of bytes still reads, as before the key existed").isEqualTo(128 * MIB);
    }

    @Test
    void streamMemoryBudget_aMalformedVariable_refusesTheBoot_namingIt_andCarriesTheParseFailure() {
        for (var bad : List.of("lots", "12XB", "-1", "1.5GB", "99999999999999999999")) {
            resolve(Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, bad), CONFIGURED)
                .onSuccess(budget -> fail(bad + " must refuse the boot, not resolve to " + budget))
                .onFailure(cause -> {
                    assertThat(cause).as(bad).isInstanceOf(StreamMemoryBudgetError.NotADataSize.class);
                    assertThat(cause.message()).as(bad).contains(StreamMemoryBudget.MAX_MEMORY_VARIABLE + "='" + bad + "'");
                    assertThat(cause.source().isPresent()).as("%s: the parser's failure is the origin", bad).isTrue();
                });
        }
    }

    @Test
    void streamMemoryBudget_aZeroVariable_refusesTheBoot_namingIt() {
        for (var zero : List.of("0", "0MB", " 0B ")) {
            resolve(Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, zero), CONFIGURED)
                .onSuccess(budget -> fail(zero + " must refuse the boot, not resolve to " + budget))
                .onFailure(cause -> {
                    assertThat(cause).as(zero).isInstanceOf(StreamMemoryBudgetError.NotPositive.class);
                    assertThat(cause.message()).as(zero).contains(StreamMemoryBudget.MAX_MEMORY_VARIABLE + "='" + zero + "'");
                });
        }
    }

    @Test
    void streamMemoryBudget_thePendingCap_isTheSmallerOfTheBudgetAnd256MiB_unlessConfigured() {
        assertThat(budget(Map.of(), DERIVED.withMemoryBudget(128 * MIB, StreamingConfig.DERIVE_SEAL_PENDING_CAP_BYTES)).sealPendingCapBytes())
            .as("a budget below 256 MiB caps the heap copies at the budget").isEqualTo(128 * MIB);
        assertThat(budget(Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, "1GB"), DERIVED).sealPendingCapBytes())
            .as("decoupled: raising the off-heap budget to 1 GiB does not raise the heap exposure past 256 MiB").isEqualTo(256 * MIB);
        assertThat(budget(Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, "1GB"), DERIVED.withMemoryBudget(StreamingConfig.DERIVE_MAX_MEMORY_BYTES, 64 * MIB))
                       .sealPendingCapBytes()).as("a configured cap is kept whatever the budget").isEqualTo(64 * MIB);
    }

    @Test
    void physicalMemoryBytes_isTheHostsMemory() {
        assertThat(StreamMemoryBudget.physicalMemoryBytes()).as("the platform MXBean answers on this JDK").isPositive();
    }

    private static StreamMemoryBudget budget(Map<String, String> environment, StreamingConfig streaming) {
        return resolve(environment, streaming).onFailure(cause -> fail(cause.message())).unwrap();
    }

    private static Result<StreamMemoryBudget> resolve(Map<String, String> environment, StreamingConfig streaming) {
        return StreamMemoryBudget.streamMemoryBudget(variable -> Option.option(environment.get(variable)), streaming, FOUR_GIB);
    }
}
