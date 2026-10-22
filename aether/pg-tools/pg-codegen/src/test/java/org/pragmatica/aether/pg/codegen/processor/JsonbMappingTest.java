// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// Code generation for json/jsonb columns (#1779): `@Jsonb` on a method, a param or a record component maps the
/// column to the declared record through a generated codec; `Option<T>` components and params read and write
/// SQL NULL; a type the codec generator cannot handle, or a `@Jsonb` target on a non-JSON column, is a compile
/// error; a hand-written `ValueMapping<T, JsonText>` is the escape hatch. The live round trip is
/// `JsonbPostgresRoundTripTest`.
class JsonbMappingTest {
    @TempDir
    Path tempDir;

    @Nested
    class Generation {
        @Test
        void documentRepo_compilesToBytecode_withHolderAndCodecConstant() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(DOC_REPO, "test/DocRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.DocRepoJsonCodecs")).as("the generated codec holder").isNotNull();
            assertThat(factory(result)).contains("PROFILE_JSON = jsonCodec(test.Profile.class)")
                                       .contains("DocRepoJsonCodecs.CODECS.get(type)");
        }

        @Test
        void markedMethod_decodesItsSingleColumn() throws Exception {
            var generated = factory(compile(DOC_REPO));

            assertThat(generated).contains("row -> RowDecodeError.guard(\"profile\", row.getObject(\"profile\", "
                                           + "org.pragmatica.aether.resource.db.JsonText.class)"
                                           + ".flatMap(text -> text.decode(PROFILE_JSON)))");
        }

        @Test
        void markedParam_bindsTheDocument_notItsComponents() throws Exception {
            var generated = factory(compile(DOC_REPO));

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROBE_JSON, probe)");
            assertThat(generated).doesNotContain("probe.name()");
        }

        @Test
        void markedComponent_decodesAndBindsThroughTheCodec() throws Exception {
            var generated = factory(compile(DOC_REPO));

            assertThat(generated).contains("RowDecodeError.guard(\"legacy\", row.getObject(\"legacy\", "
                                           + "org.pragmatica.aether.resource.db.JsonText.class)"
                                           + ".flatMap(text -> text.decode(PROFILE_JSON)))");
            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, row.legacy())");
        }

        @Test
        void markedOptionComponent_readsNullAsEmpty_andWritesEmptyAsNull() throws Exception {
            var generated = factory(compile(DOC_REPO));

            assertThat(generated).contains("RowDecodeError.guard(\"previous\", row.getOptional(\"previous\", "
                                           + "org.pragmatica.aether.resource.db.JsonText.class)"
                                           + ".flatMap(raw -> RowDecodeError.liftOptional(raw, text -> text.decode(PROFILE_JSON))))");
            assertThat(generated).contains("row.previous().map(present$ -> org.pragmatica.aether.resource.db.JsonText"
                                           + ".jsonText(PROFILE_JSON, present$)).toOptional().orElse(null)");
        }

        @Test
        void optionScalarComponent_readsNullableColumn() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(OPTION_SCALAR_REPO, "test/NickRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.NickRepoFactory")).contains("row.getOptional(\"nick\", java.lang.String.class)")
                                                                      .doesNotContain("JsonCodecs");
        }

        @Test
        void optionParam_bindsEmptyAsNull_withGenericSignature() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(OPTION_SCALAR_REPO, "test/NickRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.NickRepoFactory")).contains("org.pragmatica.lang.Option<java.lang.String> nick")
                                                                      .contains("nick.toOptional().orElse(null)");
        }

        @Test
        void optionValueObjectComponent_liftsOnlyAPresentValue() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(OPTION_VO_REPO, "test/TagRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.TagRepoFactory"))
                .contains("row.getOptional(\"nick\", java.lang.String.class)"
                          + ".flatMap(raw -> RowDecodeError.liftOptional(raw, Nick.valueMapping().lift()))")
                .contains("nick.map(present$ -> Nick.valueMapping().lower().apply(present$)).toOptional().orElse(null)");
        }

        @Test
        void unmarkedRepo_generatesNoJsonMachinery() throws Exception {
            var result = compile(OPTION_SCALAR_REPO, "test/NickRepo.java");

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.NickRepoJsonCodecs")).isNull();
            assertThat(result.generatedSource("test.NickRepoFactory")).doesNotContain("jsonCodec");
        }
    }

    @Nested
    class EscapeHatch {
        @Test
        void valueMappingToJsonText_readsAndBindsJsonText() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(SETTINGS_REPO, "test/SettingsRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.SettingsRepoFactory"))
                .contains("row.getObject(\"profile\", org.pragmatica.aether.resource.db.JsonText.class)"
                          + ".flatMap(Settings.valueMapping().lift())")
                .contains("Settings.valueMapping().lower().apply(settings)")
                .doesNotContain("jsonCodec");
        }
    }

    @Nested
    class CompileErrors {
        @Test
        void typeTheCodecGeneratorCannotHandle_isCompileErrorWithItsReason() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Task(Runnable action) {}

                @PgSql
                public interface TaskRepo {
                    @Query("SELECT profile FROM documents WHERE id = :id")
                    @Jsonb
                    Promise<Option<Task>> taskOf(long id);
                }
                """;

            var result = compile(source, "test/TaskRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("PG-VALIDATE")
                                            .contains("JSON codec generator cannot handle")
                                            .contains("java.lang.Runnable")
                                            .contains("ValueMapping<test.Task, JsonText>");
        }

        @Test
        void markedComponentOnTextColumn_isCompileError() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Title(String text) {}

                record DocumentRow(long id, @Jsonb Title title) {}

                @PgSql
                public interface TitleRepo {
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = compile(source, "test/TitleRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb target 'title' maps to column 'documents.title' of type text");
        }

        @Test
        void markedParamOnTextColumn_isCompileError() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                record Title(String text) {}

                @PgSql
                public interface TitleRepo {
                    @Query("SELECT count(*) FROM documents WHERE title = :title")
                    Promise<Long> countByTitle(@Jsonb Title title);
                }
                """;

            var result = compile(source, "test/TitleRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb target 'title' maps to column 'documents.title' of type text");
        }

        @Test
        void markedCrudMethod_isCompileError() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record DocumentRow(long id, String title) {}

                @PgSql
                public interface CrudRepo {
                    @Jsonb
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = compile(source, "test/CrudRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb on method 'findById'").contains("needs @Query");
        }

        @Test
        void markedMethodReturningUnit_isCompileError() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface UnitRepo {
                    @Query("DELETE FROM documents WHERE id = :id")
                    @Jsonb
                    Promise<Unit> remove(long id);
                }
                """;

            var result = compile(source, "test/UnitRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb method 'remove' must return");
        }

        @Test
        void unmarkedRecordComponent_errorPointsAtJsonb() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                record ProfileRow(long id, Profile profile) {}

                @PgSql
                public interface UnmarkedRepo {
                    @Query("SELECT id, profile FROM documents WHERE id = :id")
                    Promise<Option<ProfileRow>> find(long id);
                }
                """;

            var result = compile(source, "test/UnmarkedRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("mark a json/jsonb column @Jsonb");
        }
    }

    // --- shared sources ---

    static final String DOC_REPO = """
        package test;

        import java.util.List;

        import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.pg.codegen.annotation.Table;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Unit;

        record Address(String city, String zip) {}

        record Profile(String name, Option<String> nick, List<Address> addresses, Address home) {}

        record Probe(String name) {}

        record DocumentRow(long id,
                           @Jsonb Profile profile,
                           @Jsonb Option<Profile> previous,
                           @Jsonb Profile legacy,
                           String title,
                           Option<String> nick) {}

        @PgSql
        public interface DocRepo {
            @Table(DocumentRow.class)
            Promise<Unit> insert(DocumentRow row);

            Promise<Option<DocumentRow>> findById(long id);

            @Query("SELECT id, profile, previous, legacy, title, nick FROM documents WHERE profile @> :probe ORDER BY id")
            Promise<List<DocumentRow>> containing(@Jsonb Probe probe);

            @Query("SELECT profile FROM documents WHERE id = :id")
            @Jsonb
            Promise<Option<Profile>> profileOf(long id);

            @Query("SELECT legacy FROM documents ORDER BY id")
            @Jsonb
            Promise<List<Profile>> legacies();

            @Query("UPDATE documents SET previous = :previous WHERE id = :id")
            Promise<Unit> replacePrevious(long id, @Jsonb Option<Profile> previous);
        }
        """;

    private static final String OPTION_SCALAR_REPO = """
        package test;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Unit;

        record NickRow(long id, Option<String> nick) {}

        @PgSql
        public interface NickRepo {
            @Query("SELECT id, nick FROM documents WHERE id = :id")
            Promise<Option<NickRow>> find(long id);

            @Query("UPDATE documents SET nick = :nick WHERE id = :id")
            Promise<Unit> rename(long id, Option<String> nick);
        }
        """;

    private static final String OPTION_VO_REPO = """
        package test;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.aether.slice.mapping.ValueMapping;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Result;
        import org.pragmatica.lang.Unit;

        record Nick(String value) {
            static Result<Nick> nick(String raw) { return Result.success(new Nick(raw)); }
            static ValueMapping<Nick, String> valueMapping() { return ValueMapping.of(Nick::value, Nick::nick); }
        }

        record TagRow(long id, Option<Nick> nick) {}

        @PgSql
        public interface TagRepo {
            @Query("SELECT id, nick FROM documents WHERE id = :id")
            Promise<Option<TagRow>> find(long id);

            @Query("UPDATE documents SET nick = :nick WHERE id = :id")
            Promise<Unit> rename(long id, Option<Nick> nick);
        }
        """;

    private static final String SETTINGS_REPO = """
        package test;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.JsonText;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.aether.slice.mapping.ValueMapping;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Result;
        import org.pragmatica.lang.Unit;

        record Settings(String raw) {
            static Result<Settings> settings(JsonText text) { return Result.success(new Settings(text.value())); }
            static ValueMapping<Settings, JsonText> valueMapping() {
                return ValueMapping.of(settings -> JsonText.jsonText(settings.raw()), Settings::settings);
            }
        }

        record SettingsRow(long id, Settings profile) {}

        @PgSql
        public interface SettingsRepo {
            @Query("SELECT id, profile FROM documents WHERE id = :id")
            Promise<Option<SettingsRow>> find(long id);

            @Query("UPDATE documents SET profile = :settings WHERE id = :id")
            Promise<Unit> store(long id, Settings settings);
        }
        """;

    // --- helpers ---

    private TestCompilationHelper.CompilationResult compile(String source) throws Exception {
        return compile(source, "test/DocRepo.java");
    }

    private TestCompilationHelper.CompilationResult compile(String source, String fileName) throws Exception {
        return TestCompilationHelper.compileWithProcessor(source, fileName, tempDir);
    }

    private static String factory(TestCompilationHelper.CompilationResult result) throws Exception {
        assertThat(result.success()).as(result.diagnostics()).isTrue();

        var generated = result.generatedSource("test.DocRepoFactory");

        assertThat(generated).isNotNull();

        return generated;
    }
}
