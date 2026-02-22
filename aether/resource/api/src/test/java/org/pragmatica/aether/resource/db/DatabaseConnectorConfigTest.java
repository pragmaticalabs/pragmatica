package org.pragmatica.aether.resource.db;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfig;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class DatabaseConnectorConfigTest {

    @Nested
    class RequiredParamsFactory {

        @Test
        void databaseConnectorConfig_succeeds_withAllRequiredParams() {
            var config = databaseConnectorConfig("mydb", DatabaseType.POSTGRESQL, "localhost", "testdb", "user", "pass")
                .unwrap();

            assertThat(config.name()).isEqualTo("mydb");
            assertThat(config.type()).isEqualTo(DatabaseType.POSTGRESQL);
            assertThat(config.host()).isEqualTo("localhost");
            assertThat(config.database()).isEqualTo("testdb");
            assertThat(config.port()).isZero();
            assertThat(config.poolConfig()).isEqualTo(PoolConfig.DEFAULT);
            assertThat(config.properties()).isEmpty();
            assertThat(config.jdbcUrl().isEmpty()).isTrue();
            assertThat(config.r2dbcUrl().isEmpty()).isTrue();
        }

        @Test
        void databaseConnectorConfig_wrapsCredentials_asOptions() {
            var config = databaseConnectorConfig("db", DatabaseType.MYSQL, "host", "schema", "admin", "secret")
                .unwrap();

            assertThat(config.username().isPresent()).isTrue();
            assertThat(config.password().isPresent()).isTrue();
        }

        @Test
        void databaseConnectorConfig_handlesNullCredentials_asEmptyOptions() {
            var config = databaseConnectorConfig("db", DatabaseType.H2, "localhost", "testdb", null, null)
                .unwrap();

            assertThat(config.username().isEmpty()).isTrue();
            assertThat(config.password().isEmpty()).isTrue();
        }

        @Test
        void databaseConnectorConfig_fails_withBlankName() {
            var result = databaseConnectorConfig("", DatabaseType.POSTGRESQL, "localhost", "testdb", "user", "pass");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void databaseConnectorConfig_fails_withNullName() {
            var result = databaseConnectorConfig(null, DatabaseType.POSTGRESQL, "localhost", "testdb", "user", "pass");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void databaseConnectorConfig_fails_withNullType() {
            var result = databaseConnectorConfig("db", null, "localhost", "testdb", "user", "pass");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void databaseConnectorConfig_fails_withBlankHost() {
            var result = databaseConnectorConfig("db", DatabaseType.POSTGRESQL, "", "testdb", "user", "pass");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void databaseConnectorConfig_fails_withBlankDatabase() {
            var result = databaseConnectorConfig("db", DatabaseType.POSTGRESQL, "localhost", "", "user", "pass");

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class JdbcUrlFactory {

        @Test
        void databaseConnectorConfig_succeeds_withValidJdbcUrl() {
            var config = databaseConnectorConfig("db", "jdbc:postgresql://host:5432/mydb", "user", "pass")
                .unwrap();

            assertThat(config.name()).isEqualTo("db");
            assertThat(config.type()).isEqualTo(DatabaseType.POSTGRESQL);
            assertThat(config.jdbcUrl().isPresent()).isTrue();
        }

        @Test
        void databaseConnectorConfig_fails_withBlankJdbcUrl() {
            var result = databaseConnectorConfig("db", "", "user", "pass");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void databaseConnectorConfig_fails_withBlankNameAndJdbcUrl() {
            var result = databaseConnectorConfig("", "jdbc:postgresql://host/db", "user", "pass");

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class FullParamsFactory {

        @Test
        void databaseConnectorConfig_succeeds_withAllParams() {
            var config = databaseConnectorConfig("mydb",
                                                  DatabaseType.MYSQL,
                                                  "db.example.com",
                                                  3307,
                                                  "appdb",
                                                  some("admin"),
                                                  some("secret"),
                                                  PoolConfig.DEFAULT,
                                                  Map.of("useSSL", "true"),
                                                  none(),
                                                  none())
                .unwrap();

            assertThat(config.name()).isEqualTo("mydb");
            assertThat(config.type()).isEqualTo(DatabaseType.MYSQL);
            assertThat(config.host()).isEqualTo("db.example.com");
            assertThat(config.port()).isEqualTo(3307);
            assertThat(config.database()).isEqualTo("appdb");
            assertThat(config.properties()).containsEntry("useSSL", "true");
        }
    }

    @Nested
    class BuilderTests {

        @Test
        void builder_succeeds_withAllRequiredFields() {
            var config = databaseConnectorConfigBuilder()
                .withName("test-db")
                .withType(DatabaseType.POSTGRESQL)
                .withHost("localhost")
                .withDatabase("myapp")
                .build()
                .unwrap();

            assertThat(config.name()).isEqualTo("test-db");
            assertThat(config.type()).isEqualTo(DatabaseType.POSTGRESQL);
            assertThat(config.host()).isEqualTo("localhost");
            assertThat(config.database()).isEqualTo("myapp");
        }

        @Test
        void builder_appliesCustomPort_whenSet() {
            var config = databaseConnectorConfigBuilder()
                .withName("db")
                .withType(DatabaseType.POSTGRESQL)
                .withHost("localhost")
                .withDatabase("test")
                .withPort(5433)
                .build()
                .unwrap();

            assertThat(config.port()).isEqualTo(5433);
        }

        @Test
        void builder_fails_withoutName() {
            var result = databaseConnectorConfigBuilder()
                .withType(DatabaseType.POSTGRESQL)
                .withHost("localhost")
                .withDatabase("test")
                .build();

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void builder_fails_withoutHost() {
            var result = databaseConnectorConfigBuilder()
                .withName("db")
                .withType(DatabaseType.POSTGRESQL)
                .withDatabase("test")
                .build();

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class EffectiveUrls {

        @Test
        void effectiveJdbcUrl_constructsUrl_fromComponents() {
            var config = databaseConnectorConfig("db", DatabaseType.POSTGRESQL, "localhost", "testdb", "u", "p")
                .unwrap();

            assertThat(config.effectiveJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/testdb");
        }

        @Test
        void effectiveJdbcUrl_usesOverride_whenProvided() {
            var overrideUrl = "jdbc:postgresql://custom:9999/overridedb";
            var config = databaseConnectorConfig("db",
                                                  DatabaseType.POSTGRESQL,
                                                  "localhost",
                                                  0,
                                                  "testdb",
                                                  none(),
                                                  none(),
                                                  PoolConfig.DEFAULT,
                                                  Map.of(),
                                                  some(overrideUrl),
                                                  none())
                .unwrap();

            assertThat(config.effectiveJdbcUrl()).isEqualTo(overrideUrl);
        }

        @Test
        void effectiveR2dbcUrl_constructsUrl_fromComponents() {
            var config = databaseConnectorConfig("db", DatabaseType.POSTGRESQL, "localhost", "testdb", "u", "p")
                .unwrap();

            assertThat(config.effectiveR2dbcUrl()).isEqualTo("r2dbc:postgresql://localhost:5432/testdb");
        }
    }

    @Nested
    class JdbcProperties {

        @Test
        void toJdbcProperties_includesCredentials_whenPresent() {
            var config = databaseConnectorConfig("db", DatabaseType.POSTGRESQL, "localhost", "testdb", "admin", "secret")
                .unwrap();

            var props = config.toJdbcProperties();

            assertThat(props.getProperty("user")).isEqualTo("admin");
            assertThat(props.getProperty("password")).isEqualTo("secret");
        }

        @Test
        void toJdbcProperties_excludesCredentials_whenAbsent() {
            var config = databaseConnectorConfig("db", DatabaseType.POSTGRESQL, "localhost", "testdb", null, null)
                .unwrap();

            var props = config.toJdbcProperties();

            assertThat(props.containsKey("user")).isFalse();
            assertThat(props.containsKey("password")).isFalse();
        }

        @Test
        void toJdbcProperties_includesAdditionalProperties() {
            var config = databaseConnectorConfig("db",
                                                  DatabaseType.MYSQL,
                                                  "localhost",
                                                  3306,
                                                  "testdb",
                                                  none(),
                                                  none(),
                                                  PoolConfig.DEFAULT,
                                                  Map.of("useSSL", "true", "serverTimezone", "UTC"),
                                                  none(),
                                                  none())
                .unwrap();

            var props = config.toJdbcProperties();

            assertThat(props.getProperty("useSSL")).isEqualTo("true");
            assertThat(props.getProperty("serverTimezone")).isEqualTo("UTC");
        }
    }

    @Nested
    class ToStringTests {

        @Test
        void toString_redactsCredentials() {
            var config = databaseConnectorConfig("db", DatabaseType.POSTGRESQL, "localhost", "testdb", "admin", "secret")
                .unwrap();

            var str = config.toString();

            assertThat(str).contains("[REDACTED]");
            assertThat(str).doesNotContain("secret");
        }

        @Test
        void toString_masksCredentialsInJdbcUrl() {
            var config = databaseConnectorConfig("db",
                                                  DatabaseType.POSTGRESQL,
                                                  "localhost",
                                                  0,
                                                  "testdb",
                                                  none(),
                                                  none(),
                                                  PoolConfig.DEFAULT,
                                                  Map.of(),
                                                  some("jdbc:postgresql://user:pass@host/db"),
                                                  none())
                .unwrap();

            var str = config.toString();

            assertThat(str).doesNotContain("user:pass@");
        }
    }
}
