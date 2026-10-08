// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// What a rotation validates BEFORE it touches a running listener: each context the configured protocols need, and that
/// the private key belongs to the certificate. Each branch is pinned on its own so the H1-only listener tests do not
/// have to carry the HTTP/3 and key-match branches.
class TlsRotationValidationTest {
    private final TlsRotation rotation = TlsRotation.tlsRotation("validation");

    @Test
    void validate_validBundle_passesForEveryProtocolCombination() {
        var bundle = TlsProbe.validBundle("validation-node");

        assertThat(rotation.validate(bundle, true, false).isSuccess()).as("H1").isTrue();
        assertThat(rotation.validate(bundle, false, true).isSuccess()).as("H3").isTrue();
        assertThat(rotation.validate(bundle, true, true).isSuccess()).as("BOTH").isTrue();
    }

    @Test
    void validate_garbageBundle_isRefusedByEachProtocolBranchAlone() {
        var garbage = TlsProbe.garbageBundle();

        assertThat(rotation.validate(garbage, true, false).isFailure()).as("H1 branch").isTrue();
        assertThat(rotation.validate(garbage, false, true).isFailure()).as("H3 branch alone").isTrue();
        assertThat(rotation.validate(garbage, false, false).isFailure())
            .as("control: with no protocol to build, only the key check runs and the garbage key fails it").isTrue();
    }

    @Test
    void validate_mismatchedKeyAndCertificate_isRefusedEvenThoughEveryContextBuilds() {
        var mismatched = TlsProbe.mismatchedBundle();

        assertThat(rotation.validate(mismatched, true, false).isFailure()).isTrue();
        assertThat(rotation.validate(mismatched, false, true).isFailure()).isTrue();
        rotation.validate(mismatched, true, true)
                .onFailure(cause -> assertThat(cause.message()).contains("does not match"));
    }
}
