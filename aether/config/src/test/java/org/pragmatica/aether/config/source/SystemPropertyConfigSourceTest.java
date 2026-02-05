package org.pragmatica.aether.config.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPropertyConfigSourceTest {

    private static final String TEST_PREFIX = "test.config.";

    @BeforeEach
    void setUp() {
        System.setProperty(TEST_PREFIX + "database.host", "localhost");
        System.setProperty(TEST_PREFIX + "database.port", "5432");
        System.setProperty(TEST_PREFIX + "enabled", "true");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(TEST_PREFIX + "database.host");
        System.clearProperty(TEST_PREFIX + "database.port");
        System.clearProperty(TEST_PREFIX + "enabled");
    }

    @Test
    void getString_returnsValue_whenPropertyExists() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX);

        var result = source.getString("database.host");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("localhost");
    }

    @Test
    void getString_returnsEmpty_whenPropertyMissing() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX);

        var result = source.getString("missing.key");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void getInt_parsesInteger() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX);

        var result = source.getInt("database.port");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo(5432);
    }

    @Test
    void getBoolean_parsesBoolean() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX);

        var result = source.getBoolean("enabled");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isTrue();
    }

    @Test
    void keys_returnsMatchingKeys() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX);

        var keys = source.keys();

        assertThat(keys).contains("database.host", "database.port", "enabled");
    }

    @Test
    void asMap_returnsMatchingProperties() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX);

        var map = source.asMap();

        assertThat(map).containsEntry("database.host", "localhost")
                       .containsEntry("database.port", "5432")
                       .containsEntry("enabled", "true");
    }

    @Test
    void priority_returnsDefaultTwoHundred() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX);

        assertThat(source.priority()).isEqualTo(200);
    }

    @Test
    void priority_returnsCustomPriority() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX, 300);

        assertThat(source.priority()).isEqualTo(300);
    }

    @Test
    void name_containsPrefix() {
        var source = SystemPropertyConfigSource.systemPropertyConfigSource(TEST_PREFIX);

        assertThat(source.name()).contains(TEST_PREFIX);
    }
}
