package org.pragmatica.config.source;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapConfigSourceTest {

    @Test
    void getString_returnsValue_whenKeyExists() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("key", "value")).unwrap();

        var result = source.getString("key");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("value");
    }

    @Test
    void getString_returnsEmpty_whenKeyMissing() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("key", "value")).unwrap();

        var result = source.getString("missing");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void getInt_parsesInteger_whenValid() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("port", "8080")).unwrap();

        var result = source.getInt("port");

        assertThat(result.unwrap().isPresent()).isTrue();
        assertThat(result.unwrap().unwrap()).isEqualTo(8080);
    }

    /// #1098: a present, unparseable value is a refusal naming the key and the value — before
    /// #1098 this test asserted `returnsEmpty`, i.e. it specified the defect.
    @Test
    void getInt_failsNamingKeyAndValue_whenNotInteger() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("port", "not-a-number")).unwrap();

        var result = source.getInt("port");

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("port").contains("not-a-number"));
    }

    @Test
    void getBoolean_parsesTrue() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("enabled", "true")).unwrap();

        var result = source.getBoolean("enabled");

        assertThat(result.unwrap().isPresent()).isTrue();
        assertThat(result.unwrap().unwrap()).isTrue();
    }

    @Test
    void getBoolean_parsesFalse() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("enabled", "false")).unwrap();

        var result = source.getBoolean("enabled");

        assertThat(result.unwrap().isPresent()).isTrue();
        assertThat(result.unwrap().unwrap()).isFalse();
    }

    @Test
    void getBoolean_failsNamingKeyAndValue_whenNotBoolean() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("enabled", "maybe")).unwrap();

        var result = source.getBoolean("enabled");

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("enabled").contains("maybe"));
    }

    @Test
    void keys_returnsAllKeys() {
        var source = MapConfigSource.mapConfigSource("test", Map.of("a", "1", "b", "2")).unwrap();

        var keys = source.keys();

        assertThat(keys).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void asMap_returnsCopy() {
        var original = Map.of("key", "value");
        var source = MapConfigSource.mapConfigSource("test", original).unwrap();

        var map = source.asMap();

        assertThat(map).containsEntry("key", "value");
        assertThat(map).isNotSameAs(original);
    }

    @Test
    void priority_returnsCustomPriority() {
        var source = MapConfigSource.mapConfigSource("test", Map.of(), 42).unwrap();

        assertThat(source.priority()).isEqualTo(42);
    }

    @Test
    void priority_returnsDefaultZero() {
        var source = MapConfigSource.mapConfigSource("test", Map.of()).unwrap();

        assertThat(source.priority()).isEqualTo(0);
    }

    @Test
    void name_returnsProvidedName() {
        var source = MapConfigSource.mapConfigSource("my-source", Map.of()).unwrap();

        assertThat(source.name()).isEqualTo("my-source");
    }

    @Test
    void mapConfigSource_createsEmptySource() {
        var source = MapConfigSource.mapConfigSource("empty").unwrap();

        assertThat(source.keys()).isEmpty();
        assertThat(source.asMap()).isEmpty();
    }
}
