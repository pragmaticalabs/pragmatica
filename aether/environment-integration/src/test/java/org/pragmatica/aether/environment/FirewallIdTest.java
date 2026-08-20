// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.FirewallId.firewallId;


class FirewallIdTest {

    private static void assertAccepted(String raw) {
        firewallId(raw).onFailure(cause -> fail("'" + raw + "' must be accepted: " + cause.message()))
                       .onSuccess(id -> assertThat(id.value()).isEqualTo(raw));
    }

    private static void assertRejected(String raw) {
        firewallId(raw).onSuccess(id -> fail("'" + raw + "' must be rejected, produced " + id));
    }

    @Nested
    class ValidationTests {

        /// Provider-opaque on purpose: each cloud mints ids in its own shape and a grammar invented here
        /// would reject ids the providers legitimately issue.
        @Test
        void firewallId_accepts_everyProviderIdShape() {
            List.of("12345", "sg-0abc123def", "aether-prod-eu-hetzner-eu",
                    "/subscriptions/abc-123/resourceGroups/rg1/providers/Microsoft.Network/networkSecurityGroups/nsg1")
                .forEach(FirewallIdTest::assertAccepted);
        }

        @Test
        void firewallId_rejects_null() {
            assertRejected(null);
        }

        /// A blank id reaches a delete call that matches nothing, so the firewall leaks as a paid orphan
        /// while the cleanup ledger records it as reclaimed. That is the ONE invariant worth holding here.
        @Test
        void firewallId_rejects_blank() {
            List.of("", " ", "\t").forEach(FirewallIdTest::assertRejected);
        }
    }

    @Nested
    class NumericConversionTests {

        @Test
        void asNumeric_yieldsTheNumber_forAHetznerShapedId() {
            firewallId("12345").flatMap(FirewallId::asNumeric)
                               .onFailure(cause -> fail("a numeric id must convert: " + cause.message()))
                               .onSuccess(id -> assertThat(id).isEqualTo(12345L));
        }

        /// Refuse rather than substitute a guess: a wrong id under a numeric-id provider deletes whatever
        /// resource happens to hold it, which on a shared cloud account is somebody else's firewall.
        @Test
        void asNumeric_failsWithNotNumeric_forAProviderIdThatIsNotANumber() {
            firewallId("sg-0abc123def").flatMap(FirewallId::asNumeric)
                                       .onSuccess(id -> fail("a non-numeric id must not convert, produced " + id))
                                       .onFailure(cause -> assertThat(cause).isInstanceOf(FirewallId.NotNumeric.class));
        }

        /// The refusal has to NAME the id and say it is refusing, because that message is what an operator
        /// acts on when destroy stops short of a billable resource.
        @Test
        void asNumeric_notNumericMessage_carriesTheRawIdAndTheRefusal() {
            firewallId("sg-0abc123def").flatMap(FirewallId::asNumeric)
                                       .onFailure(cause -> assertThat(cause.message()).contains("sg-0abc123def")
                                                                                      .contains("not numeric")
                                                                                      .contains("Refusing to guess"));
        }

        @Test
        void asNumeric_failsWithNotNumeric_forAnIdThatOverflowsALong() {
            firewallId("9".repeat(25)).flatMap(FirewallId::asNumeric)
                                      .onSuccess(id -> fail("an overflowing id must not convert, produced " + id))
                                      .onFailure(cause -> assertThat(cause).isInstanceOf(FirewallId.NotNumeric.class));
        }
    }

    @Nested
    class RenderingTests {

        /// Cleanup log lines and cause messages interpolate this type directly (`id=<value>`); `toString`
        /// must stay the raw value or every one of them changes shape.
        @Test
        void toString_rendersTheRawValue_soInterpolationStaysIdentical() {
            assertThat("id=" + firewallId("sg-0abc123def").unwrap()).isEqualTo("id=sg-0abc123def");
        }
    }
}
