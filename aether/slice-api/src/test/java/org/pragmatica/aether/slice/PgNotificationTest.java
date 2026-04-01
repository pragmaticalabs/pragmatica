package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.PgNotification.pgNotification;

class PgNotificationTest {

    @Nested
    class FactoryMethod {

        @Test
        void fields_areAccessible() {
            var notification = pgNotification("orders_changed", "{\"id\":1}", 42);

            assertThat(notification.channel()).isEqualTo("orders_changed");
            assertThat(notification.payload()).isEqualTo("{\"id\":1}");
            assertThat(notification.pid()).isEqualTo(42);
        }

        @Test
        void equality_byValues() {
            var a = pgNotification("ch", "payload", 1);
            var b = pgNotification("ch", "payload", 1);

            assertThat(a).isEqualTo(b);
        }
    }
}
