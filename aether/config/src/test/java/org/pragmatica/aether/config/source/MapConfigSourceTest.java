package org.pragmatica.aether.config.source;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapConfigSourceTest {

    @Test
    void getString_returnsValue_whenKeyExists() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("key", "value"));

        var result = source.getString("key");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("value");
    }

    @Test
    void getString_returnsEmpty_whenKeyMissing() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("key", "value"));

        var result = source.getString("missing");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void getInt_parsesInteger_whenValid() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("port", "8080"));

        var result = source.getInt("port");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo(8080);
    }

    @Test
    void getInt_returnsEmpty_whenNotInteger() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("port", "not-a-number"));

        var result = source.getInt("port");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void getBoolean_parsesTrue() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("enabled", "true"));

        var result = source.getBoolean("enabled");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isTrue();
    }

    @Test
    void getBoolean_parsesFalse() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("enabled", "false"));

        var result = source.getBoolean("enabled");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isFalse();
    }

    @Test
    void getBoolean_returnsEmpty_whenNotBoolean() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("enabled", "maybe"));

        var result = source.getBoolean("enabled");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void keys_returnsAllKeys() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("a", "1", "b", "2"));

        var keys = source.keys();

        assertThat(keys).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void asMap_returnsCopy() {
        var original = Map.of("key", "value");
        var source = MapConfigSource.mapConfigSource("test", original);

        var map = source.asMap();

        assertThat(map).containsEntry("key", "value");
        assertThat(map).isNotSameAs(original);
    }

    @Test
    void priority_returnsCustomPriority() {
        var source = MapConfigSource.mapConfigSource("test", Map.of(), 42);

        assertThat(source.priority()).isEqualTo(42);
    }

    @Test
    void priority_returnsDefaultZero() {
        var source = MapConfigSource.mapConfigSource("test", Map.of());

        assertThat(source.priority()).isEqualTo(0);
    }

    @Test
    void name_returnsProvidedName() {
        var source = MapConfigSource.mapConfigSource("my-source", Map.of());

        assertThat(source.name()).isEqualTo("my-source");
    }

    @Test
    void emptyMapConfigSource_createsEmptySource() {
        var source = MapConfigSource.emptyMapConfigSource("empty");

        assertThat(source.keys()).isEmpty();
        assertThat(source.asMap()).isEmpty();
    }
}
