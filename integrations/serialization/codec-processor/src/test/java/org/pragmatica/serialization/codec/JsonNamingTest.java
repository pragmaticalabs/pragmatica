package org.pragmatica.serialization.codec;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.UncheckedIOException;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Build-time JSON names: `-Ajson.naming` translates component names into the generated name tables, `@JsonName`
/// overrides it on a component or an enum constant, and a name that cannot go into a name table is a compile error.
class JsonNamingTest {
    static final JavaFileObject ACCOUNT = JavaFileObjects.forSourceString("com.example.Account",
                                                                          """
        package com.example;

        import org.pragmatica.lang.Option;
        import org.pragmatica.serialization.json.Json;
        import org.pragmatica.serialization.json.JsonName;

        @Json
        public record Account(String userId, @JsonName("legacy_Name") String displayName, Option<String> homePage, Tier tier) {
            public enum Tier {
                BASIC,
                @JsonName("pro-tier") PRO
            }
        }
        """);

    static Compilation compile(JavaFileObject source, String... options) {
        return javac().withProcessors(new CodecProcessor())
                    .withOptions((Object[]) options)
                    .compile(source);
    }

    static String generated(Compilation compilation) {
        assertThat(compilation).succeeded();
        try {
            return compilation.generatedSourceFile("com.example.AccountJson")
                              .orElseThrow()
                              .getCharContent(true)
                              .toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void noNamingOption_keepsDeclaredNames_butHonoursJsonName() {
        var source = generated(compile(ACCOUNT));

        assertTrue(source.contains("fieldNames(\"userId\", \"legacy_Name\", \"homePage\", \"tier\")"), source);
        assertTrue(source.contains("fieldNames(\"BASIC\", \"pro-tier\")"), source);
    }

    @Test
    void snakeCaseOption_translatesComponentNames_exceptRenamedOnes_andNeverEnumConstants() {
        var source = generated(compile(ACCOUNT, "-Ajson.naming=snake_case"));

        assertTrue(source.contains("fieldNames(\"user_id\", \"legacy_Name\", \"home_page\", \"tier\")"), source);
        assertTrue(source.contains("fieldNames(\"BASIC\", \"pro-tier\")"), source);
    }

    @Test
    void kebabAndPascalOptions_translateComponentNames() {
        assertTrue(generated(compile(ACCOUNT, "-Ajson.naming=kebab-case")).contains("fieldNames(\"user-id\", \"legacy_Name\", \"home-page\", \"tier\")"));
        assertTrue(generated(compile(ACCOUNT, "-Ajson.naming=PascalCase")).contains("fieldNames(\"UserId\", \"legacy_Name\", \"HomePage\", \"Tier\")"));
    }

    @Test
    void entryPoint_offersAnOptionsBoundCodec() {
        assertTrue(generated(compile(ACCOUNT)).contains("public static org.pragmatica.serialization.json.JsonTypeCodec<com.example.Account> codec(org.pragmatica.serialization.json.JsonOptions options)"));
    }

    @Test
    void unknownNamingOption_failsTheCompilation_namingTheOption() {
        var compilation = compile(ACCOUNT, "-Ajson.naming=camel");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("-Ajson.naming: Unknown JSON naming strategy 'camel'");
    }

    @Test
    void jsonNameWithAQuote_failsTheCompilation() {
        var compilation = compile(JavaFileObjects.forSourceString("com.example.Quoted",
                                                                  """
            package com.example;

            import org.pragmatica.serialization.json.Json;
            import org.pragmatica.serialization.json.JsonName;

            @Json
            public record Quoted(@JsonName("a\\"b") String a) {}
            """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Json type 'com.example.Quoted' cannot get a generated codec");
        assertThat(compilation).hadErrorContaining("holds a quote, a backslash or a control character");
    }

    @Test
    void twoComponentsWithOneJsonName_failTheCompilation_evenWhenOnlyTheNamingMakesThemEqual() {
        var renamed = compile(JavaFileObjects.forSourceString("com.example.Twice",
                                                              """
            package com.example;

            import org.pragmatica.serialization.json.Json;
            import org.pragmatica.serialization.json.JsonName;

            @Json
            public record Twice(String first, @JsonName("first") String second) {}
            """));
        var translated = compile(JavaFileObjects.forSourceString("com.example.Clash",
                                                                 """
            package com.example;

            import org.pragmatica.serialization.json.Json;

            @Json
            public record Clash(String userId, String user_id) {}
            """),
                                 "-Ajson.naming=snake_case");

        assertThat(renamed).failed();
        assertThat(renamed).hadErrorContaining("two components have the JSON name 'first'");
        assertThat(translated).failed();
        assertThat(translated).hadErrorContaining("two components have the JSON name 'user_id'");
    }

    @Test
    void twoConstantsWithOneJsonName_failTheCompilation() {
        var compilation = compile(JavaFileObjects.forSourceString("com.example.Pick",
                                                                  """
            package com.example;

            import org.pragmatica.serialization.json.Json;
            import org.pragmatica.serialization.json.JsonName;

            @Json
            public enum Pick {
                A,
                @JsonName("A") B
            }
            """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("two constants have the JSON name 'A'");
    }
}
