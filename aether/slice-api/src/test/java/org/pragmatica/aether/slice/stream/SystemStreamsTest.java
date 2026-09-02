// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class SystemStreamsTest {

    /// Enumerated-set pin, not a structural/reflective scan: [SystemStreams#ALL] is a hand-maintained
    /// catalog (its own doc comment says as much — "adding a new system stream ... must also list it
    /// here"), and [SystemStreamBootstrap] registers exactly what it iterates. This test hardcodes the
    /// expected engine-key set so a future addition to `ALL` requires a conscious update here too,
    /// rather than silently changing what the management-api write-gate (`ManagementServer`) and
    /// bootstrap both treat as "framework-internal." Deliberately does NOT include
    /// `audit.lifecycle.commands` or any other stream not already in `ALL` — whether such streams
    /// belong in `ALL` at all depends on their own bootstrap mechanism, which is out of scope here.
    @Test
    void all_engineKeys_matchExpectedEnumeratedSet() {
        var actual = SystemStreams.ALL.stream()
                                      .map(address -> address.name().value())
                                      .collect(Collectors.toSet());

        assertThat(actual).isEqualTo(Set.of("cluster-events"));
    }

    @Test
    void isForbiddenEngineKey_clusterEvents_isTrue() {
        assertThat(SystemStreams.isForbiddenEngineKey("cluster-events")).isTrue();
    }

    @Test
    void isForbiddenEngineKey_appStreamName_isFalse() {
        assertThat(SystemStreams.isForbiddenEngineKey("orders")).isFalse();
    }

    @Test
    void isForbiddenEngineKey_caseVariant_isFalse() {
        // Engine keys are bare names compared exactly, not case-insensitively — a caller cannot evade
        // by casing, but nor does the gate treat "Cluster-Events" as if it were the real stream: it
        // simply isn't the same engine key, so it resolves as an (unrelated) app-namespace write.
        assertThat(SystemStreams.isForbiddenEngineKey("Cluster-Events")).isFalse();
    }

    @Test
    void isForbiddenEngineKey_emptyString_isFalse() {
        assertThat(SystemStreams.isForbiddenEngineKey("")).isFalse();
    }
}
