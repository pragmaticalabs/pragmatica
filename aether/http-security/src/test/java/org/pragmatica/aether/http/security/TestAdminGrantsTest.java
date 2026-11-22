// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.http.handler.security.Principal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class TestAdminGrantsTest {
    @Test
    void withAdminGrant_grantIsLiveInsideTheWrite_deadAfterTheFixtureReturns() {
        var liveInside = new AtomicBoolean(false);
        var held = new AtomicReference<AdminGrant>();

        TestAdminGrants.withAdminGrant(grant -> recordLiveness(grant, liveInside, held));
        assertThat(liveInside.get()).as("the grant is live inside the fixture's run").isTrue();
        assertThat(held.get()).isNotNull();
        assertThat(held.get().live()).as("the grant is dead once the fixture returned").isFalse();
    }

    @Test
    void withAdminGrant_grantIsTheAdminKeyPrincipal() {
        var granted = new AtomicReference<Principal>();

        TestAdminGrants.withAdminGrant(grant -> granted.set(grant.grantedBy()));
        assertThat(granted.get().value()).isEqualTo("api-key:test-admin");
    }

    private static void recordLiveness(AdminGrant grant, AtomicBoolean liveInside, AtomicReference<AdminGrant> held) {
        liveInside.set(grant.live());
        held.set(grant);
    }
}
