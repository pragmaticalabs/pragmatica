package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamSubscriberTest {

    @Test
    void isMarkerInterface_withNoMethods() {
        assertThat(StreamSubscriber.class.getMethods())
            .filteredOn(m -> m.getDeclaringClass() == StreamSubscriber.class)
            .isEmpty();
    }

    @Test
    void isInterface() {
        assertThat(StreamSubscriber.class.isInterface()).isTrue();
    }
}
