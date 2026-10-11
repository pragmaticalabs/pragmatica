// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgNotificationSubscriberTest {

    @Test
    void isMarkerInterface_withNoMethods() {
        assertThat(PgNotificationSubscriber.class.getMethods())
            .filteredOn(m -> m.getDeclaringClass() == PgNotificationSubscriber.class)
            .isEmpty();
    }

    @Test
    void isInterface() {
        assertThat(PgNotificationSubscriber.class.isInterface()).isTrue();
    }
}
