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
