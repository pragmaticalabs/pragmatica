// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the pure [Topic] descriptor: the name is the single declared identity and the
/// payload type is captured via [TypeToken] through either factory overload.
class TopicTest {
    record SeatSold(String seatId) {}

    @Test
    void of_withClass_carriesNameAndPayloadType() {
        var topic = Topic.of("seat-sold", SeatSold.class);

        assertThat(topic.name()).isEqualTo("seat-sold");
        assertThat(topic.payloadType().rawType()).isEqualTo(SeatSold.class);
    }

    @Test
    void of_withTypeToken_carriesGenericPayloadType() {
        var token = new TypeToken<SeatSold>() {};
        var topic = Topic.of("seat-sold", token);

        assertThat(topic.name()).isEqualTo("seat-sold");
        assertThat(topic.payloadType()).isEqualTo(token);
    }

    @Test
    void of_withClass_producesSamePayloadTypeAsMatchingTypeToken() {
        var topic = Topic.of("seat-sold", SeatSold.class);

        assertThat(topic.payloadType()).isEqualTo(new TypeToken<SeatSold>() {});
    }
}
