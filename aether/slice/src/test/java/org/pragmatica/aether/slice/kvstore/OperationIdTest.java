// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.OperationId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// NT-P5 (q4 7.1, rulings 6e5003dd7f and f2d51c1322): the operation id is the server name at the provider, whose names are
/// case-SENSITIVE (P-G1 A4), so it is lower case at mint and the factory PARSES, never fixes: an upper-case input is refused,
/// not lower-cased into a different name than the caller holds.
class OperationIdTest {
    @Test
    void operationId_accepts_theLowerCaseGrammar() {
        for (var raw : List.of("heal-3f9a", "a", "0", "core-7", "n1-op-20261031-000042", "a-b-c")) {
            OperationId.operationId(raw)
                       .onFailure(cause -> fail(raw + ": " + cause.message()))
                       .onSuccess(id -> assertThat(id.value()).isEqualTo(raw));
        }
    }

    @Test
    void operationId_refuses_upperCaseAndCharactersOutsideTheGrammar() {
        for (var raw : List.of("Heal-3f9a", "HEAL", "heal-3F9a", "heal_3", "heal 3", "heal.3", "-heal", "heal-", "", " ", "heal/3", "héal")) {
            OperationId.operationId(raw)
                       .onSuccess(id -> fail("accepted '" + raw + "' as " + id));
        }
    }

    @Test
    void operationId_refuses_aNameLongerThanTheProvidersLimit() {
        OperationId.operationId("a".repeat(63))
                   .onFailure(cause -> fail(cause.message()));
        OperationId.operationId("a".repeat(64))
                   .onSuccess(id -> fail("accepted 64 characters"));
    }

    @Test
    void operationId_refuses_aNullInput() {
        OperationId.operationId(null)
                   .onSuccess(id -> fail("accepted null"));
    }

    @Test
    void operationId_doesNotLowerCaseSilently() {
        OperationId.operationId("Heal-1")
                   .onSuccess(id -> fail("lower-cased to " + id.value()));
    }
}
