package org.pragmatica.config.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TomlConfigSourceTest {

    @Test
    void tomlConfigSource_parsesInlineContent() {
        var toml = """
            title = "My App"

            [database]
            host = "localhost"
            port = 5432

            [server]
            port = 8080
            """;

        var result = TomlConfigSource.tomlConfigSource(toml);

        assertThat(result.isSuccess()).isTrue();
        var source = result.unwrap();

        assertThat(source.getString("title").unwrap()).isEqualTo("My App");
        assertThat(source.getString("database.host").unwrap()).isEqualTo("localhost");
        assertThat(source.getString("database.port").unwrap()).isEqualTo("5432");
        assertThat(source.getString("server.port").unwrap()).isEqualTo("8080");
    }

    @Test
    void tomlConfigSource_returnsErrorForInvalidToml() {
        var invalidToml = """
            [database
            host = "localhost"
            """;

        var result = TomlConfigSource.tomlConfigSource(invalidToml);

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void keys_returnsAllFlattenedKeys() {
        var toml = """
            title = "My App"

            [database]
            host = "localhost"
            port = 5432
            """;

        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        var keys = source.keys();

        assertThat(keys).contains("title", "database.host", "database.port");
    }

    @Test
    void asMap_returnsFlattenedValues() {
        var toml = """
            title = "My App"

            [database]
            host = "localhost"
            """;

        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        var map = source.asMap();

        assertThat(map).containsEntry("title", "My App")
                       .containsEntry("database.host", "localhost");
    }

    @Test
    void getInt_parsesIntegerFromToml() {
        var toml = """
            [server]
            port = 8080
            """;

        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        var result = source.getInt("server.port");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo(8080);
    }

    @Test
    void getBoolean_parsesBooleanFromToml() {
        var toml = """
            [features]
            enabled = true
            """;

        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        var result = source.getBoolean("features.enabled");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isTrue();
    }

    @Test
    void priority_returnsDefaultZero() {
        var toml = "title = 'test'";
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        assertThat(source.priority()).isEqualTo(0);
    }

    @Test
    void name_indicatesInlineSource() {
        var toml = "title = 'test'";
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        assertThat(source.name()).contains("inline");
    }

    @Test
    void document_returnsUnderlyingDocument() {
        var toml = """
            [database]
            host = "localhost"
            """;

        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        var document = source.document();

        assertThat(document.hasSection("database")).isTrue();
        assertThat(document.getString("database", "host").unwrap()).isEqualTo("localhost");
    }

    @Test
    void reload_returnsThis_forInlineContent() {
        var toml = "title = 'test'";
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        var reloaded = source.reload();

        assertThat(reloaded.isSuccess()).isTrue();
        assertThat(reloaded.unwrap()).isSameAs(source);
    }
}
