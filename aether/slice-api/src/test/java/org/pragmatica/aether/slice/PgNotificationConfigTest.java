// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.aether.slice.PgNotificationConfig.pgNotificationConfig;

class PgNotificationConfigTest {

    @Nested
    class FactoryMethod {

        @Test
        void datasource_isPreserved() {
            var config = pgNotificationConfig("database.primary", List.of("ch1", "ch2"));

            assertThat(config.datasource()).isEqualTo("database.primary");
        }

        @Test
        void channels_arePreserved() {
            var config = pgNotificationConfig("db", List.of("orders", "users"));

            assertThat(config.channels()).containsExactly("orders", "users");
        }

        @Test
        void channels_areImmutableCopy() {
            var mutable = new ArrayList<>(List.of("ch1", "ch2"));
            var config = pgNotificationConfig("db", mutable);
            mutable.add("ch3");

            assertThat(config.channels()).hasSize(2);
        }

        @Test
        void channels_listIsUnmodifiable() {
            var config = pgNotificationConfig("db", List.of("ch1"));

            assertThatThrownBy(() -> config.channels().add("ch2"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
