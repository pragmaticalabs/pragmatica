package org.pragmatica.aether.resource.db.async;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.PgNotificationConfig;
import org.pragmatica.aether.slice.PgNotificationSubscriber;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationListenerFactoryTest {

    private final NotificationListenerFactory factory = new NotificationListenerFactory();

    @Nested
    class ResourceType {

        @Test
        void resourceType_isPgNotificationSubscriber() {
            assertThat(factory.resourceType()).isEqualTo(PgNotificationSubscriber.class);
        }

        @Test
        void configType_isPgNotificationConfig() {
            assertThat(factory.configType()).isEqualTo(PgNotificationConfig.class);
        }
    }

    @Nested
    class SpiDiscovery {

        @Test
        @SuppressWarnings("rawtypes")
        void isDiscoverableViaServiceLoader() {
            var factories = ServiceLoader.load(ResourceFactory.class)
                                         .stream()
                                         .map(ServiceLoader.Provider::get)
                                         .filter(f -> f.resourceType() == PgNotificationSubscriber.class)
                                         .toList();

            assertThat(factories).hasSize(1);
            assertThat(factories.getFirst()).isInstanceOf(NotificationListenerFactory.class);
        }
    }

    @Nested
    class Supports {

        @Test
        void supports_anyConfig() {
            var config = PgNotificationConfig.pgNotificationConfig("db", java.util.List.of("ch1"));

            assertThat(factory.supports(config)).isTrue();
        }
    }
}
