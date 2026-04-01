package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgNotificationConfigParserTest {

    @Nested
    class ValidConfig {

        @Test
        void parseSingleSection() {
            var toml = """
                [pg-notifications.order-events]
                datasource = "database.primary"
                channels = ["orders_changed", "orders_deleted"]
                """;

            var result = PgNotificationConfigParser.parse(toml);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(configs -> {
                      assertThat(configs).hasSize(1);
                      assertThat(configs).containsKey("order-events");
                      var config = configs.get("order-events");
                      assertThat(config.datasource()).isEqualTo("database.primary");
                      assertThat(config.channels()).containsExactly("orders_changed", "orders_deleted");
                  });
        }

        @Test
        void parseMultipleSections() {
            var toml = """
                [pg-notifications.order-events]
                datasource = "database.primary"
                channels = ["orders_changed"]

                [pg-notifications.user-events]
                datasource = "database.analytics"
                channels = ["users_created", "users_updated"]
                """;

            var result = PgNotificationConfigParser.parse(toml);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(configs -> {
                      assertThat(configs).hasSize(2);
                      assertThat(configs).containsKeys("order-events", "user-events");
                      assertThat(configs.get("user-events").channels()).containsExactly("users_created", "users_updated");
                  });
        }

        @Test
        void defaultDatasource_whenMissing() {
            var toml = """
                [pg-notifications.events]
                channels = ["ch1"]
                """;

            var result = PgNotificationConfigParser.parse(toml);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(configs -> assertThat(configs.get("events").datasource()).isEqualTo("database"));
        }

        @Test
        void emptyChannels_whenMissing() {
            var toml = """
                [pg-notifications.events]
                datasource = "db"
                """;

            var result = PgNotificationConfigParser.parse(toml);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(configs -> assertThat(configs.get("events").channels()).isEmpty());
        }
    }

    @Nested
    class EmptyInput {

        @Test
        void nullInput_returnsEmptyMap() {
            var result = PgNotificationConfigParser.parse(null);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(configs -> assertThat(configs).isEmpty());
        }

        @Test
        void blankInput_returnsEmptyMap() {
            var result = PgNotificationConfigParser.parse("   ");

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(configs -> assertThat(configs).isEmpty());
        }
    }

    @Nested
    class NonMatchingSections {

        @Test
        void ignoresUnrelatedSections() {
            var toml = """
                [database.primary]
                host = "localhost"

                [pg-notifications.events]
                datasource = "database.primary"
                channels = ["ch1"]
                """;

            var result = PgNotificationConfigParser.parse(toml);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(configs -> {
                      assertThat(configs).hasSize(1);
                      assertThat(configs).containsKey("events");
                  });
        }
    }
}
