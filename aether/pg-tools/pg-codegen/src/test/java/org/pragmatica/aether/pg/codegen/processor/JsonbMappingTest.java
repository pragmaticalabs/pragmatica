// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.pragmatica.aether.resource.db.JsonText;
import org.pragmatica.aether.resource.db.RowDecodeError;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

        /// `VALUES (:record)` expands an unmarked record param into its components; a marked one is one document.
        @Test
        void markedRecordParam_inValuesList_isNotExpanded() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                record Profile(String name, String city) {}

                @PgSql
                public interface AuditRepo {
                    @Query("INSERT INTO documents (profile) VALUES (:profile)")
                    Promise<Unit> record(@Jsonb Profile profile);
                }
                """;

            var result = compile(source, "test/AuditRepo.java");

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.AuditRepoFactory")).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, profile)")
                                                                       .doesNotContain("profile.name()");
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
        void optionValueObjectFromAnotherPackage_isImported() throws Exception {
            var nick = """
                package other;

                import org.pragmatica.aether.slice.mapping.ValueMapping;
                import org.pragmatica.lang.Result;

                public record Nick(String value) {
                    public static Result<Nick> nick(String raw) { return Result.success(new Nick(raw)); }
                    public static ValueMapping<Nick, String> valueMapping() { return ValueMapping.of(Nick::value, Nick::nick); }
                }
                """;
            var repo = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record TagRow(long id, Option<other.Nick> nick) {}

                @PgSql
                public interface TagRepo {
                    @Query("SELECT id, nick FROM documents WHERE id = :id")
                    Promise<Option<TagRow>> find(long id);
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(java.util.List.of(new TestCompilationHelper.SourceEntry("other/Nick.java", nick),
                                                                                            new TestCompilationHelper.SourceEntry("test/TagRepo.java", repo)),
                                                                          tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.TagRepoFactory")).contains("import other.Nick;");
        }

        @Test
        void documentMethodAsOnlyLift_importsRowDecodeError_andCompiles() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                @PgSql
                public interface OnlyDocRepo {
                    @Query("SELECT profile FROM documents WHERE id = :id")
                    @Jsonb
                    Promise<Profile> profileOf(long id);
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(source, "test/OnlyDocRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.OnlyDocRepoFactory")).contains("import org.pragmatica.aether.resource.db.RowDecodeError;");
        }

        // Bounded on its own thread: a broken clash numbering spins forever, which the test thread cannot interrupt.
        @Test
        @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        void sameSimpleNameInTwoPackages_getsDistinctCodecConstants() throws Exception {
            var otherProfile = """
                package other;

                public record Profile(String nickname) {}
                """;
            var repo = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                record DocumentRow(long id, @Jsonb Profile profile, @Jsonb other.Profile legacy) {}

                @PgSql
                public interface TwinRepo {
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(java.util.List.of(new TestCompilationHelper.SourceEntry("other/Profile.java", otherProfile),
                                                                                            new TestCompilationHelper.SourceEntry("test/TwinRepo.java", repo)),
                                                                          tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.TwinRepoFactory")).contains("PROFILE_JSON = jsonCodec(test.Profile.class)")
                                                                      .contains("PROFILE_JSON2 = jsonCodec(other.Profile.class)")
                                                                      .contains("text.decode(PROFILE_JSON2)");
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
    class RuntimeDecode {
        private static final String PROFILE = "{\"name\":\"Ann\",\"nick\":\"an\",\"addresses\":[],\"home\":{\"city\":\"Kyiv\",\"zip\":\"01001\"}}";

        @Test
        void storedDocuments_decodeIntoTheRecord_nullAsEmpty() throws Exception {
            var decoded = (Result<?>) documentRowMapper().invoke(null, row(Map.of("profile", PROFILE, "legacy", PROFILE)));

            assertThat(decoded.isSuccess()).as(String.valueOf(decoded)).isTrue();
            assertThat(decoded.toString()).contains("profile=Profile[name=Ann, nick=Some(an), addresses=[], home=Address[city=Kyiv, zip=01001]]")
                                          .contains("previous=None()");
        }

        @Test
        void corruptDocument_yieldsTypedRowDecodeNamingTheColumn_notException() throws Exception {
            var decoded = (Result<?>) documentRowMapper().invoke(null, row(Map.of("profile", "{\"name\":42}", "legacy", PROFILE)));
            var captured = new AtomicReference<Cause>();

            decoded.onFailure(captured::set);

            // A multi-column row collects every column's failure (Result.all), so the typed cause sits in the composite.
            assertThat(captured.get().stream()).singleElement()
                                               .isInstanceOfSatisfying(RowDecodeError.RowDecode.class,
                                                                       decode -> assertThat(decode.column()).isEqualTo("profile"));
        }

        private Method documentRowMapper() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(DOC_REPO, "test/DocRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();

            var loader = new URLClassLoader(new URL[]{tempDir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
            var mapper = Arrays.stream(loader.loadClass("test.DocRepoFactory").getDeclaredMethods())
                               .filter(method -> method.getName().equals("mapDocumentRow"))
                               .findFirst()
                               .orElseThrow();

            mapper.setAccessible(true);

            return mapper;
        }

        // Document columns from `documents`: present ones as JsonText, absent ones as SQL NULL.
        private static RowMapper.RowAccessor row(Map<String, String> documents) {
            return new RowMapper.RowAccessor() {
                @Override public Result<String> getString(String column) { return Result.success("t"); }
                @Override public Result<Integer> getInt(String column) { return Result.success(0); }
                @Override public Result<Long> getLong(String column) { return Result.success(1L); }
                @Override public Result<Double> getDouble(String column) { return Result.success(0.0); }
                @Override public Result<Boolean> getBoolean(String column) { return Result.success(false); }
                @Override public Result<byte[]> getBytes(String column) { return Result.success(new byte[0]); }

                @Override
                public <V> Result<V> getObject(String column, Class<V> type) {
                    return Result.success(type.cast(JsonText.jsonText(documents.get(column))));
                }

                @Override
                public <V> Result<Option<V>> getOptional(String column, Class<V> type) {
                    return Result.success(Option.option(documents.get(column)).map(text -> type.cast(type == JsonText.class
                                                                                                    ? JsonText.jsonText(text)
                                                                                                    : text)));
                }
            };
        }
    }

    /// #2401: where the schema knows the column is json/jsonb, an unmarked record (or `Option` of one) is a document.
    @Nested
    class Inference {
        @Test
        void crudComponentsOnJsonColumns_areDocuments_withoutMarker() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(INFERRED_REPO, "test/InferredRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.InferredRepoFactory"))
                .contains("RowDecodeError.guard(\"profile\", row.getObject(\"profile\", org.pragmatica.aether.resource.db.JsonText.class)"
                          + ".flatMap(text -> text.decode(PROFILE_JSON)))")
                .contains("row.getOptional(\"previous\", org.pragmatica.aether.resource.db.JsonText.class)")
                .contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, row.legacy())")
                .contains("row.previous().map(present$ -> org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, present$))");
        }

        @Test
        void crudConditionParamOnJsonColumn_isDocument() throws Exception {
            var generated = factory(compile(INFERRED_REPO, "test/InferredRepo.java"), "test.InferredRepoFactory");

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, profile)")
                                 .contains("title, org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, legacy)");
        }

        @Test
        void queryPairOnJsonColumn_isDocument() throws Exception {
            var generated = factory(compile(INFERRED_REPO, "test/InferredRepo.java"), "test.InferredRepoFactory");

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROBE_JSON, probe)")
                                 .contains("previous.map(present$ -> org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, present$))");
        }

        @Test
        void recordExpandedInQueryValues_componentOnJsonColumnIsDocument() throws Exception {
            var generated = factory(compile(INFERRED_REPO, "test/InferredRepo.java"), "test.InferredRepoFactory");

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, entry.profile())")
                                 .contains("entry.title()");
        }

        @Test
        void crudEnumComponentOnJsonColumn_isAJsonString() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Table;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                enum Mood { CALM, BUSY }

                record DocumentRow(long id, Mood profile, Mood legacy, String title) {}

                @PgSql
                public interface EnumRepo {
                    @Table(DocumentRow.class)
                    Promise<Unit> insert(DocumentRow row);

                    @Table(DocumentRow.class)
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(source, "test/EnumRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.EnumRepoFactory")).contains("text.decode(MOOD_JSON)")
                                                                     .contains("org.pragmatica.aether.resource.db.JsonText.jsonText(MOOD_JSON, row.profile())");
        }

        @Test
        void inferredTypeTheGeneratorCannotHandle_isCompileErrorWithItsReason() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Task(Runnable action) {}

                record DocumentRow(long id, Task profile) {}

                @PgSql
                public interface InferredTaskRepo {
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = compile(source, "test/InferredTaskRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("'profile' maps to a json/jsonb column, so its type 'test.Task' is read and written as a JSON document")
                                            .contains("component action: neither a record nor an enum")
                                            .contains(". Write a 'static ValueMapping<test.Task, JsonText> valueMapping()' for it, or bind JsonText.");
        }

        /// A `ValueMapping` beats inference: an `Option` of a value object on a json column stays a nullable value object.
        @Test
        void optionValueObjectOnJsonColumn_staysValueMapping() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.JsonText;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.aether.slice.mapping.ValueMapping;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Result;

                record Settings(String raw) {
                    static Result<Settings> settings(JsonText text) { return Result.success(new Settings(text.value())); }
                    static ValueMapping<Settings, JsonText> valueMapping() {
                        return ValueMapping.of(settings -> JsonText.jsonText(settings.raw()), Settings::settings);
                    }
                }

                record DocumentRow(long id, Option<Settings> previous) {}

                @PgSql
                public interface SettingsRowRepo {
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var generated = factory(compile(source, "test/SettingsRowRepo.java"), "test.SettingsRowRepoFactory");

            assertThat(generated).contains("RowDecodeError.liftOptional(raw, Settings.valueMapping().lift())")
                                 .doesNotContain("jsonCodec");
        }

        /// A `::type` cast hands the SQL the conversion, so a cast parameter is not inferred as a document.
        @Test
        void castRecordParam_isNotInferred() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                record Profile(String name) {}

                @PgSql
                public interface CastRepo {
                    @Query("UPDATE documents SET profile = :profile::jsonb WHERE id = :id")
                    Promise<Unit> store(long id, Profile profile);
                }
                """;

            var result = compile(source, "test/CastRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Parameter 'profile'").contains("mark a json/jsonb column @Jsonb");
        }

        /// The component name maps to its column as the mapper maps it (camelCase to snake_case).
        @Test
        void camelCaseComponentOnJsonColumn_isDocument() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Extra(String note) {}

                record DocumentRow(long id, Option<Extra> extraDoc) {}

                @PgSql
                public interface ExtraRepo {
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var generated = factory(compile(source, "test/ExtraRepo.java"), "test.ExtraRepoFactory");

            assertThat(generated).contains("row.getOptional(\"extra_doc\", org.pragmatica.aether.resource.db.JsonText.class)");
        }

        /// Only a json/jsonb column infers a document; an unmarked record on a text column stays an error.
        @Test
        void recordOnTextColumn_isNotInferred() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Title(String text) {}

                record DocumentRow(long id, Title title) {}

                @PgSql
                public interface TextRepo {
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = compile(source, "test/TextRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Field 'title'").contains("mark a json/jsonb column @Jsonb");
        }

        @Test
        void inferredTwinSimpleNames_compile() throws Exception {
            var otherProfile = """
                package other;

                public record Profile(String nickname) {}
                """;
            var repo = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                record DocumentRow(long id, Profile profile, other.Profile legacy) {}

                @PgSql
                public interface InferredTwinRepo {
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(java.util.List.of(new TestCompilationHelper.SourceEntry("other/Profile.java", otherProfile),
                                                                                            new TestCompilationHelper.SourceEntry("test/InferredTwinRepo.java", repo)),
                                                                          tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.InferredTwinRepoFactory")).contains("PROFILE_JSON2 = jsonCodec(other.Profile.class)");
        }
    }

    /// #2401: a String bound to a column the schema knows is json/jsonb is a compile error; casts and String reads stay.
    @Nested
    class StringOnJsonColumn {
        @Test
        void crudInsertStringComponent_isCompileError() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                record DocumentRow(long id, String profile, String legacy, String title) {}

                @PgSql
                public interface StringRepo {
                    @Table(DocumentRow.class)
                    Promise<Unit> insert(DocumentRow row);
                }
                """, "'profile' is a String bound to column 'documents.profile' of type jsonb");
        }

        @Test
        void crudInsertStringOnCamelCaseJsonColumn_isCompileError() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                record DocumentRow(long id, String title) {}

                @PgSql
                public interface StringRepo {
                    @Table(DocumentRow.class)
                    Promise<Unit> insert(long id, String extraDoc);
                }
                """, "'extraDoc' is a String bound to column 'documents.extra_doc' of type jsonb");
        }

        @Test
        void crudInsertScalarStringParam_isCompileError() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                record DocumentRow(long id, String title) {}

                @PgSql
                public interface StringRepo {
                    @Table(DocumentRow.class)
                    Promise<Unit> insert(long id, String profile, String legacy, String title);
                }
                """, "'legacy' is a String bound to column 'documents.legacy' of type json");
        }

        @Test
        void crudConditionStringParam_isCompileError() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                record DocumentRow(long id, String title) {}

                @PgSql
                public interface StringRepo {
                    @Table(DocumentRow.class)
                    Promise<List<DocumentRow>> findByProfile(String profile);
                }
                """, "'profile' is a String bound to column 'documents.profile' of type jsonb");
        }

        @Test
        void queryPairStringParam_isCompileError() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                @PgSql
                public interface StringRepo {
                    @Query("UPDATE documents SET profile = :raw WHERE id = :id")
                    Promise<Unit> store(long id, String raw);
                }
                """, "'raw' is a String bound to column 'documents.profile' of type jsonb");
        }

        @Test
        void queryPairStringParam_messageNamesTheWaysOut() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                @PgSql
                public interface StringRepo {
                    @Query("UPDATE documents SET profile = :raw WHERE id = :id")
                    Promise<Unit> store(long id, String raw);
                }
                """, "A String parameter is sent as varchar, which json/jsonb refuse: bind a record (a JSON document), "
                     + "JsonText, or cast the parameter in the SQL (CAST(:raw AS jsonb))");
        }

        @Test
        void valueObjectLoweredToString_isCompileError() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                import org.pragmatica.aether.slice.mapping.ValueMapping;
                import org.pragmatica.lang.Result;

                record Raw(String text) {
                    static Result<Raw> raw(String text) { return Result.success(new Raw(text)); }
                    static ValueMapping<Raw, String> valueMapping() { return ValueMapping.of(Raw::text, Raw::raw); }
                }

                @PgSql
                public interface StringRepo {
                    @Query("UPDATE documents SET profile = :raw WHERE id = :id")
                    Promise<Unit> store(long id, Raw raw);
                }
                """, "'raw' is a String bound to column 'documents.profile' of type jsonb");
        }

        @Test
        void queryOptionStringParam_isCompileError() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                @PgSql
                public interface StringRepo {
                    @Query("UPDATE documents SET previous = :raw WHERE id = :id")
                    Promise<Unit> store(long id, Option<String> raw);
                }
                """, "'raw' is a String bound to column 'documents.previous' of type jsonb");
        }

        @Test
        void queryExpandedStringComponent_isCompileError() throws Exception {
            assertRefused(STRING_REPO_HEADER + """
                record Entry(long id, String profile, String legacy, String title) {}

                @PgSql
                public interface StringRepo {
                    @Query("INSERT INTO documents VALUES(:entry)")
                    Promise<Unit> add(Entry entry);
                }
                """, "'profile' is a String bound to column 'documents.profile' of type jsonb");
        }

        @Test
        void castInSql_stringReads_andJsonText_areAllowed() throws Exception {
            var source = STRING_REPO_HEADER + """
                import org.pragmatica.aether.resource.db.JsonText;

                record DocumentRow(long id, String profile, String legacy) {}

                @PgSql
                public interface AllowedRepo {
                    @Query("UPDATE documents SET profile = :raw::jsonb WHERE id = :id")
                    Promise<Unit> storeCast(long id, String raw);

                    @Query("UPDATE documents SET profile = CAST(:raw AS jsonb) WHERE id = :id")
                    Promise<Unit> storeCastFunction(long id, String raw);

                    @Query("UPDATE documents SET profile = :text WHERE id = :id")
                    Promise<Unit> storeText(long id, JsonText text);

                    @Query("SELECT id FROM documents WHERE profile ->> 'name' = :name")
                    Promise<List<Long>> idsByName(String name);

                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = compile(source, "test/AllowedRepo.java");

            assertThat(result.success()).as(result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.AllowedRepoFactory")).contains("row.getString(\"profile\")");
        }

        private void assertRefused(String source, String expected) throws Exception {
            var result = compile(source, "test/StringRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains(expected)
                                            .contains("A String parameter is sent as varchar, which json/jsonb refuse");
        }
    }

    /// #2401: where the schema is silent, the marker stays the only signal.
    @Nested
    class SchemaSilent {
        @Test
        void unmarkedRecordParam_inValuesList_isExpanded() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                record Titled(long id, String title) {}

                @PgSql
                public interface ExpandRepo {
                    @Query("INSERT INTO documents VALUES(:doc)")
                    Promise<Unit> add(Titled doc);
                }
                """;

            var generated = factory(compile(source, "test/ExpandRepo.java"), "test.ExpandRepoFactory");

            assertThat(generated).contains("doc.id()").contains("doc.title()").doesNotContain("jsonCodec");
        }

        @Test
        void unmarkedQueryMethodRecordResult_isRowRecord() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                @PgSql
                public interface RowRepo {
                    @Query("SELECT profile FROM documents WHERE id = :id")
                    Promise<Option<Profile>> profileOf(long id);
                }
                """;

            var generated = factory(compile(source, "test/RowRepo.java"), "test.RowRepoFactory");

            assertThat(generated).contains("row.getString(\"name\")").doesNotContain("jsonCodec");
        }

        @Test
        void unmarkedEnumInQueryResultComponent_isNotInferred() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                enum Mood { CALM, BUSY }

                record MoodRow(long id, Mood profile) {}

                @PgSql
                public interface EnumRepo {
                    @Query("SELECT id, profile FROM documents WHERE id = :id")
                    Promise<Option<MoodRow>> find(long id);
                }
                """;

            var result = compile(source, "test/EnumRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Field 'profile'").contains("mark a json/jsonb column @Jsonb");
        }

        @Test
        void unmarkedRecordInQueryResultComponent_isNotInferred() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                record ProfileRow(long id, Profile profile) {}

                @PgSql
                public interface QueryRowRepo {
                    @Query("SELECT id, profile FROM documents WHERE id = :id")
                    Promise<Option<ProfileRow>> find(long id);
                }
                """;

            var result = compile(source, "test/QueryRowRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("mark a json/jsonb column @Jsonb");
        }

        @Test
        void unmarkedRecordInExpression_isNotInferred() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                @PgSql
                public interface ExpressionRepo {
                    @Query("SELECT count(*) FROM documents WHERE jsonb_typeof(profile) = 'object' AND profile = coalesce(:probe, profile)")
                    Promise<Long> countLike(Profile probe);
                }
                """;

            var result = compile(source, "test/ExpressionRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Parameter 'probe'").contains("mark a json/jsonb column @Jsonb");
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
                                            .contains("component action: neither a record nor an enum")
                                            .contains("ValueMapping<test.Task, JsonText>");
        }

        @Test
        void componentTypeTheCodecGeneratorCannotHandle_isCompileErrorWithItsReason() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Task(Runnable action) {}

                record TaskRow(long id, @Jsonb Task profile) {}

                @PgSql
                public interface TaskRowRepo {
                    @Query("SELECT id, profile FROM documents WHERE id = :id")
                    Promise<Option<TaskRow>> find(long id);
                }
                """;

            var result = compile(source, "test/TaskRowRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb target 'profile' has type 'test.Task'")
                                            .contains("component action: neither a record nor an enum");
        }

        @Test
        void markedTypeThatIsNoRecordOrEnum_isCompileError() throws Exception {
            var source = """
                package test;

                import java.util.List;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                @PgSql
                public interface ListRepo {
                    @Query("UPDATE documents SET profile = :profiles WHERE id = :id")
                    Promise<Long> store(long id, @Jsonb List<Profile> profiles);
                }
                """;

            var result = compile(source, "test/ListRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb target 'profiles'").contains("is not a record or enum type");
        }

        @Test
        void markedPlainStringOrGenericRecord_isCompileError() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Unit;
                import org.pragmatica.lang.Promise;

                record Box<T>(T value) {}

                @PgSql
                public interface RawRepo {
                    @Query("UPDATE documents SET profile = :raw WHERE id = :id")
                    Promise<Unit> storeRaw(long id, @Jsonb String raw);

                    @Query("UPDATE documents SET legacy = :box WHERE id = :id")
                    Promise<Unit> storeBox(long id, @Jsonb Box<String> box);
                }
                """;

            var result = compile(source, "test/RawRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb target 'raw' has type 'java.lang.String'")
                                            .contains("@Jsonb target 'box' has type 'test.Box<java.lang.String>'");
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
            assertThat(result.diagnostics()).contains("@Jsonb target 'title' maps to column 'documents.title' of type text; a JSON document needs a json or jsonb column");
        }

        @Test
        void markedComponentOfInsertedRecordOnTextColumn_isCompileError() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Table;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                record Title(String text) {}

                record Profile(String name) {}

                record DocumentRow(long id, @Jsonb Profile profile, @Jsonb Profile legacy, @Jsonb Title title) {}

                @PgSql
                public interface TitleInsertRepo {
                    @Table(DocumentRow.class)
                    Promise<Unit> insert(DocumentRow row);
                }
                """;

            var result = compile(source, "test/TitleInsertRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb target 'title' maps to column 'documents.title' of type text; a JSON document needs a json or jsonb column");
        }

        /// The schema check maps the component name to its column the same way the mapper does (camelCase to snake_case).
        @Test
        void markedCamelCaseComponentOnTextColumn_isCompileError() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Title(String text) {}

                record DocumentRow(long id, @Jsonb Title documentTitle) {}

                @PgSql
                public interface CamelRepo {
                    Promise<Option<DocumentRow>> findById(long id);
                }
                """;

            var result = compile(source, "test/CamelRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("@Jsonb target 'documentTitle' maps to column 'documents.document_title' of type text");
        }

        /// The holder name is taken by a type that is not generated output: the processor reports the generator's
        /// refusal instead of silently leaving the factory without codecs.
        @Test
        void holderNameTakenByAnotherType_isCompileError() throws Exception {
            var squatter = """
                package test;

                public final class ClashRepoJsonCodecs {}
                """;
            var repo = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                @PgSql
                public interface ClashRepo {
                    @Query("SELECT profile FROM documents WHERE id = :id")
                    @Jsonb
                    Promise<Option<Profile>> profileOf(long id);
                }
                """;

            var result = TestCompilationHelper.compileWithProcessor(java.util.List.of(new TestCompilationHelper.SourceEntry("test/ClashRepoJsonCodecs.java", squatter),
                                                                                      new TestCompilationHelper.SourceEntry("test/ClashRepo.java", repo)),
                                                                    tempDir);

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Cannot generate test.ClashRepoJsonCodecs for test.ClashRepo")
                                            .contains("is not marked as generated JSON codec code");
        }

        @Test
        void unmarkedRecordParam_errorPointsAtJsonb() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                record Profile(String name) {}

                @PgSql
                public interface UnmarkedParamRepo {
                    @Query("SELECT count(*) FROM documents WHERE profile @> :probe")
                    Promise<Long> countLike(Profile probe);
                }
                """;

            var result = compile(source, "test/UnmarkedParamRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Parameter 'probe' has type 'test.Profile'")
                                            .contains("use a raw column type, or mark a json/jsonb column @Jsonb.");
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
            assertThat(result.diagnostics()).contains("@Jsonb target 'title' maps to column 'documents.title' of type text; a JSON document needs a json or jsonb column");
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
            assertThat(result.diagnostics()).contains("@Jsonb method 'remove' must return Promise<T>, Promise<Option<T>> or Promise<List<T>> of a record or enum");
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

    static final String INFERRED_REPO = """
        package test;

        import java.util.List;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.pg.codegen.annotation.Table;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Unit;

        record Address(String city, String zip) {}

        record Profile(String name, Option<String> nick, List<Address> addresses, Address home) {}

        record Probe(String name) {}

        record DocumentRow(long id, Profile profile, Option<Profile> previous, Profile legacy, String title, Option<String> nick) {}

        record Entry(long id, Profile profile, Profile legacy, String title) {}

        @PgSql
        public interface InferredRepo {
            @Table(DocumentRow.class)
            Promise<Unit> insert(DocumentRow row);

            @Table(DocumentRow.class)
            Promise<Option<DocumentRow>> findById(long id);

            @Table(DocumentRow.class)
            Promise<List<DocumentRow>> findByProfile(Profile profile);

            @Table(DocumentRow.class)
            Promise<List<DocumentRow>> findByTitleAndLegacy(String title, Profile legacy);

            @Query("SELECT count(*) FROM documents WHERE profile = :probe")
            Promise<Long> countEqual(Probe probe);

            @Query("UPDATE documents SET previous = :previous WHERE id = :id")
            Promise<Unit> replacePrevious(long id, Option<Profile> previous);

            @Query("INSERT INTO documents VALUES(:entry)")
            Promise<Unit> add(Entry entry);

            @Query("INSERT INTO documents (title, profile, legacy, id) VALUES (:title, :profile, :legacy, :id)")
            Promise<Unit> addPositional(long id, String title, Profile profile, Profile legacy);
        }
        """;

    private static final String STRING_REPO_HEADER = """
        package test;

        import java.util.List;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.pg.codegen.annotation.Table;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Unit;

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

    /// #2401: parameters are paired with their columns off the pg-parser syntax tree, so comments, whitespace, letter
    /// case and clause order cannot change a pairing. A String the tree cannot place, in an INSERT, UPDATE or DELETE
    /// whose table has json/jsonb columns, is refused unless cast.
    @Nested
    class TreePairing {
        private static final String PROFILE_RECORD = "record Profile(String name) {}\n\n";
        private static final String NOT_PLACED = "is a String used where its column cannot be determined from the SQL";

        // --- INSERT: the n-th value of every row with the n-th column ---
        @Test
        void plainInsert_stringOnJsonColumn_isCompileErrorNamingTheColumn() throws Exception {
            var result = compile(STRING_REPO_HEADER + """
                @PgSql
                public interface PositionalRepo {
                    @Query("INSERT INTO documents (id, profile, legacy, title) VALUES (:id, :raw, :legacy, :title)")
                    Promise<Unit> store(long id, String raw, String legacy, String title);
                }
                """, "test/PositionalRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("'raw' is a String bound to column 'documents.profile' of type jsonb")
                                            .contains("'legacy' is a String bound to column 'documents.legacy' of type json")
                                            .contains("A String parameter is sent as varchar, which json/jsonb refuse");
        }

        @Test
        void plainInsert_recordOnJsonColumn_isDocument_pairedByPositionNotByName() throws Exception {
            var generated = factory(compile(INFERRED_REPO, "test/InferredRepo.java"), "test.InferredRepoFactory");

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, profile)")
                                 .contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, legacy)");
        }

        @Test
        void plainInsert_textColumnFirst_jsonColumnSecond_isPairedInOrder() throws Exception {
            assertDocuments("INSERT INTO documents (title, profile, id, legacy) VALUES (:t, :doc, :id, :old)",
                            "long id, String t, Profile doc, Profile old",
                            "doc",
                            "old");
        }

        @Test
        void plainInsert_jsonColumnFirst_textColumnSecond_isPairedInOrder() throws Exception {
            assertDocuments("INSERT INTO documents (profile, title, id, legacy) VALUES (:doc, :t, :id, :old)",
                            "String t, long id, Profile doc, Profile old",
                            "doc",
                            "old");
        }

        @Test
        void plainInsert_stringOnTextColumns_compiles() throws Exception {
            assertCompilesWithoutDocuments("INSERT INTO documents (id, title, nick) VALUES (:id, :title, :nick)",
                                           "long id, String title, String nick");
        }

        @Test
        void insertWithoutSpaces_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents(id,profile)VALUES(:id,:raw)", "long id, String raw");
        }

        @Test
        void insertWithExtraSpaces_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents ( id , profile ) VALUES ( :id , :raw )", "long id, String raw");
        }

        @Test
        void lowerCaseKeywords_arePaired() throws Exception {
            assertStringOnJsonRefused("insert into documents (id, profile) values (:id, :raw) returning id", "long id, String raw");
        }

        @Test
        void leadingSpace_isPaired() throws Exception {
            assertStringOnJsonRefused(" INSERT INTO documents (id, profile) VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void trailingSemicolon_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw);", "long id, String raw");
        }

        @Test
        void trailingSemicolonAndSpace_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw); ", "long id, String raw");
        }

        @Test
        void newlineAndTabBetweenClauses_arePaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile)\n\tVALUES (:id, :raw)\nRETURNING id", "long id, String raw");
        }

        @Test
        void returningRightAfterTheValues_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw)RETURNING id", "long id, String raw");
        }

        @Test
        void commentRightAfterTheValues_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw)/*c*/RETURNING id", "long id, String raw");
        }

        @Test
        void commentBeforeTheTail_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw) /* c */ RETURNING id", "long id, String raw");
        }

        @Test
        void commentInTheColumnList_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, /* c */ profile) VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void lineCommentBeforeValues_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) -- c\nVALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void commentBeforeTheStatement_isPaired() throws Exception {
            assertStringOnJsonRefused("/* audit */ INSERT INTO documents (id, profile) VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void cteBeforeTheInsert_isPaired() throws Exception {
            assertStringOnJsonRefused("WITH x AS (SELECT 1) INSERT INTO documents (id, profile) VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void upperCaseColumn_foldsToTheLowerCaseColumn() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, PROFILE) VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void upperCaseTable_foldsToTheLowerCaseTable() throws Exception {
            assertStringOnJsonRefused("INSERT INTO Documents (id, profile) VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void quotedLowerCaseColumn_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, \"profile\") VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void quotedColumn_keepsItsCase() throws Exception {
            assertRefused("INSERT INTO kw (id, \"Doc\") VALUES (:id, :raw)",
                          "long id, String raw",
                          "'raw' is a String bound to column 'kw.Doc' of type jsonb");
            assertCompilesWithoutDocuments("INSERT INTO kw (id, doc) VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void keywordNamedColumns_arePaired() throws Exception {
            assertRefused("INSERT INTO kw (id, value) VALUES (:id, :raw)",
                          "long id, String raw",
                          "'raw' is a String bound to column 'kw.value' of type jsonb");
            assertRefused("INSERT INTO kw (id, \"order\") VALUES (:id, :raw)",
                          "long id, String raw",
                          "'raw' is a String bound to column 'kw.order' of type jsonb");
        }

        @Test
        void tableAlias_keepsTheColumnList() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents AS d (id, profile) VALUES (:id, :raw)", "long id, String raw");
        }

        @Test
        void everyRowOfAMultiRowInsert_isPaired() throws Exception {
            assertRefused("INSERT INTO documents (id, profile) VALUES (:id, :doc), (:id2, :raw2)",
                          "long id, Profile doc, long id2, String raw2",
                          "'raw2' is a String bound to column 'documents.profile' of type jsonb");
        }

        @Test
        void defaultAmongTheValues_keepsThePositions() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, title, profile) VALUES (:id, DEFAULT, :raw)", "long id, String raw");
        }

        @Test
        void castValue_isAllowed_andNotInferred() throws Exception {
            assertCompilesWithoutDocuments("INSERT INTO documents (id, profile) VALUES (:id, :raw::jsonb)", "long id, String raw");
            assertCompilesWithoutDocuments("INSERT INTO documents (id, profile) VALUES (:id, :raw ::jsonb)", "long id, String raw");
            assertCompilesWithoutDocuments("INSERT INTO documents (id, profile) VALUES (:id, to_jsonb(:raw::text))", "long id, String raw");
        }

        @Test
        void castRecordValue_isNotInferred() throws Exception {
            var result = compileSql("INSERT INTO documents (id, profile) VALUES (:id, :doc::jsonb)", "long id, Profile doc");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Parameter 'doc' has type 'test.Profile' which is neither a supported column type");
        }

        // --- tails ---
        @Test
        void returning_tail_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw) RETURNING profile", "long id, String raw");
        }

        @Test
        void onConflictDoNothing_tail_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw) ON CONFLICT (id, title) DO NOTHING",
                                      "long id, String raw");
        }

        @Test
        void onConflictOnANewLine_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw) ON\nCONFLICT (id) DO NOTHING",
                                      "long id, String raw");
        }

        @Test
        void onConflictDoUpdate_tail_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw) ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title WHERE documents.id > 0",
                                      "long id, String raw");
        }

        @Test
        void returning_tail_recordOnJsonColumn_isDocument() throws Exception {
            assertDocuments("INSERT INTO documents (id, profile) VALUES (:id, :doc) ON CONFLICT (id) DO NOTHING RETURNING profile",
                            "long id, Profile doc",
                            "doc");
        }

        @Test
        void onConflictDoUpdate_setItem_isPairedWithTheTarget() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, title) VALUES (:id, :title) ON CONFLICT (id) DO UPDATE SET profile = :raw",
                                      "long id, String title, String raw");
        }

        @Test
        void onConflictWhere_excludedIsTheTarget() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, title) VALUES (:id, :title) ON CONFLICT (id) DO UPDATE SET title = :title WHERE excluded.profile = :raw",
                                      "long id, String title, String raw");
        }

        // --- the table comes from the statement's own target, never from a FROM in a tail ---
        @Test
        void tailSelectingFromAnotherTable_keepsTheTarget_forAString() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, :raw) ON CONFLICT (id) DO UPDATE SET title = (SELECT o.name FROM other o WHERE o.id = 1)",
                                      "long id, String raw");
        }

        @Test
        void tailSelectingFromAnotherTable_keepsTheTarget_forARecord() throws Exception {
            assertDocuments("INSERT INTO documents (id, profile) VALUES (:id, :doc) ON CONFLICT (id) DO UPDATE SET title = (SELECT o.name FROM other o WHERE o.id = 1)",
                            "long id, Profile doc",
                            "doc");
        }

        @Test
        void textTargetWithATailSelectingFromDocuments_recordIsTheTypeError_notADocument() throws Exception {
            var result = compileSql("INSERT INTO other (id, profile) VALUES (:id, :doc) ON CONFLICT (id) DO UPDATE SET name = (SELECT d.title FROM documents d WHERE d.id = 1)",
                                    "long id, Profile doc");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Parameter 'doc' has type 'test.Profile' which is neither a supported column type")
                                            .doesNotContain("documents.profile");
        }

        @Test
        void textTargetWithATailSelectingFromDocuments_stringCompiles() throws Exception {
            assertCompilesWithoutDocuments("INSERT INTO other (id, profile) VALUES (:id, :raw) ON CONFLICT (id) DO UPDATE SET name = (SELECT d.title FROM documents d WHERE d.id = 1)",
                                           "long id, String raw");
        }

        // --- one parameter, two columns ---
        @Test
        void sameRecordOnAJsonAndATextColumn_isRefused() throws Exception {
            assertRefused("INSERT INTO documents (id, profile, title) VALUES (:id, :doc, :doc)",
                          "long id, Profile doc",
                          "'doc' is bound to json/jsonb column 'documents.profile', so it is sent as a JSON document, and also to column 'documents.title'");
        }

        @Test
        void sameRecordOnTwoJsonColumns_isDocument() throws Exception {
            assertDocuments("INSERT INTO documents (id, profile, legacy) VALUES (:id, :doc, :doc)", "long id, Profile doc", "doc");
        }

        @Test
        void sameStringOnAJsonAndATextColumn_isRefused() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, title, profile) VALUES (:id, :raw, :raw)", "long id, String raw");
        }

        // --- UPDATE, DELETE and SELECT pairs ---
        @Test
        void setItem_isPaired() throws Exception {
            assertStringOnJsonRefused("UPDATE documents SET profile = :raw WHERE id = :id", "long id, String raw");
        }

        @Test
        void setItemWithAKeywordOrQuotedColumn_isPaired() throws Exception {
            assertRefused("UPDATE kw SET value = :raw WHERE id = :id",
                          "long id, String raw",
                          "'raw' is a String bound to column 'kw.value' of type jsonb");
            assertRefused("UPDATE kw SET \"order\" = :raw WHERE id = :id",
                          "long id, String raw",
                          "'raw' is a String bound to column 'kw.order' of type jsonb");
        }

        @Test
        void setColumnList_isPairedByPosition() throws Exception {
            assertStringOnJsonRefused("UPDATE documents SET (title, profile) = (:t, :raw) WHERE id = :id", "long id, String t, String raw");
        }

        @Test
        void wherePair_upperCaseColumn_isPaired() throws Exception {
            assertStringOnJsonRefused("SELECT id FROM documents WHERE PROFILE = :raw", "String raw");
        }

        @Test
        void wherePair_reversed_isPaired() throws Exception {
            assertStringOnJsonRefused("SELECT id FROM documents WHERE :raw = profile", "String raw");
        }

        @Test
        void wherePair_secondTableOfAJoin_isPairedThroughItsAlias() throws Exception {
            assertStringOnJsonRefused("SELECT d.id FROM other o JOIN documents d ON d.id = o.id WHERE d.profile = :raw", "String raw");
        }

        @Test
        void wherePair_castAfterASpace_isAllowed() throws Exception {
            assertCompilesWithoutDocuments("SELECT id FROM documents WHERE profile = :raw ::jsonb", "String raw");
        }

        @Test
        void inLikeBetweenAndDistinct_arePairedWithTheirColumn() throws Exception {
            assertCompilesWithoutDocuments("UPDATE documents SET title = :t WHERE title LIKE :p AND nick BETWEEN :a AND :b AND title IN (:i, :j) AND nick IS DISTINCT FROM :n",
                                           "String t, String p, String a, String b, String i, String j, String n");
            assertStringOnJsonRefused("DELETE FROM documents WHERE profile IN (:raw)", "String raw");
        }

        @Test
        void updateFromAnotherTable_resolvesItsQualifiedColumns() throws Exception {
            assertCompilesWithoutDocuments("UPDATE documents d SET title = :t FROM other o WHERE o.profile = :p AND d.id = o.id",
                                           "String t, String p");
        }

        @Test
        void subqueryColumn_resolvesInTheSubquery() throws Exception {
            assertCompilesWithoutDocuments("UPDATE documents SET title = (SELECT o.name FROM other o WHERE o.profile = :p) WHERE id = :id",
                                           "long id, String p");
        }

        @Test
        void targetAlias_qualifiesColumns() throws Exception {
            assertStringOnJsonRefused("UPDATE documents d SET title = :t WHERE d.profile = :raw", "String t, String raw");
        }

        @Test
        void parenthesisedValue_isPaired() throws Exception {
            assertStringOnJsonRefused("INSERT INTO documents (id, profile) VALUES (:id, (:raw))", "long id, String raw");
        }

        @Test
        void correlatedColumn_resolvesInTheEnclosingStatement() throws Exception {
            assertRefused("UPDATE documents SET title = (SELECT o.name FROM other o WHERE o.id = 1 AND legacy = :raw)",
                          "String raw",
                          "'raw' is a String bound to column 'documents.legacy' of type json");
            assertStringOnJsonRefused("UPDATE documents SET title = (SELECT o.name FROM other o WHERE o.id = documents.id AND documents.profile = :raw)",
                                      "String raw");
        }

        @Test
        void nestedSelectTables_stayInTheirSelect() throws Exception {
            assertStringOnJsonRefused("SELECT id FROM documents WHERE profile = :raw AND id IN (SELECT o.id FROM other o)", "String raw");
        }

        @Test
        void ambiguousBareColumn_isNotPlaced() throws Exception {
            assertNotPlaced("UPDATE documents SET title = :t FROM other WHERE profile = :p AND documents.id = other.id",
                            "String t, String p",
                            "p");
        }

        @Test
        void derivedTableColumn_blocksBareResolution() throws Exception {
            assertNotPlaced("UPDATE documents SET title = :t FROM (SELECT 1 AS id) s WHERE legacy = :p AND documents.id = s.id",
                            "String t, String p",
                            "p");
            assertNotPlaced("WITH x AS (SELECT 1 AS id) UPDATE documents SET title = :t FROM x WHERE legacy = :p AND documents.id = x.id",
                            "String t, String p",
                            "p");
            assertNotPlaced("UPDATE documents SET title = :t FROM generate_series(1, 2) g WHERE legacy = :p",
                            "String t, String p",
                            "p");
        }

        @Test
        void insertSelectSource_doesNotSeeTheTarget() throws Exception {
            assertNotPlaced("INSERT INTO documents (id, title) SELECT o.id, o.name FROM other o WHERE legacy = :p", "String p", "p");
        }

        @Test
        void enumOnAJsonColumn_isAJsonString() throws Exception {
            var generated = factory(compile(STRING_REPO_HEADER + """
                enum Mood { CALM, BUSY }

                @PgSql
                public interface MoodRepo {
                    @Query("UPDATE documents SET profile = :mood WHERE id = :id")
                    Promise<Unit> store(long id, Mood mood);
                }
                """, "test/MoodRepo.java"), "test.MoodRepoFactory");

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(MOOD_JSON, mood)");
        }

        // --- a String the tree cannot place ---
        @Test
        void unplacedString_inAStatementOnAJsonTable_isRefused() throws Exception {
            assertNotPlaced("INSERT INTO documents (id, profile) SELECT :id, :raw", "long id, String raw", "raw");
            assertNotPlaced("INSERT INTO documents (id, profile) VALUES (:id, coalesce(:raw, '{}'))", "long id, String raw", "raw");
            assertNotPlaced("INSERT INTO documents (id, profile) VALUES (:id, :raw || :more)", "long id, String raw, String more", "more");
            assertNotPlaced("INSERT INTO documents (id, profile, title) VALUES (:id, :raw)", "long id, String raw", "raw");
            assertNotPlaced("INSERT INTO documents VALUES (:id, :raw)", "long id, String raw", "raw");
            assertNotPlaced("UPDATE documents SET title = lower(:t) WHERE id = :id", "long id, String t", "t");
            assertNotPlaced("DELETE FROM documents WHERE title = lower(:t)", "String t", "t");
        }

        @Test
        void unplacedString_withACast_compiles() throws Exception {
            assertCompilesWithoutDocuments("UPDATE documents SET title = lower(:t::text) WHERE id = :id", "long id, String t");
            assertCompilesWithoutDocuments("INSERT INTO documents (id, profile) SELECT :id, CAST(:raw AS jsonb)", "long id, String raw");
        }

        @Test
        void unplacedString_onATableWithoutJsonColumns_orInASelect_compiles() throws Exception {
            assertCompilesWithoutDocuments("UPDATE other SET name = lower(:t) WHERE id = :id", "long id, String t");
            assertCompilesWithoutDocuments("SELECT id FROM documents WHERE lower(title) = lower(:t)", "String t");
        }

        @Test
        void unplacedNonString_compiles() throws Exception {
            assertCompilesWithoutDocuments("UPDATE documents SET title = :t WHERE id = abs(:id)", "long id, String t");
        }

        // --- record expansion: the column list is read off the tree ---
        @Test
        void columnList_oneJsonColumn_recordIsDocument_notExpanded() throws Exception {
            var generated = factory(compile(STRING_REPO_HEADER + PROFILE_RECORD + """
                @PgSql
                public interface ListRepo {
                    @Query("INSERT INTO documents (profile) VALUES (:p)")
                    Promise<Unit> store(Profile p);
                }
                """, "test/ListRepo.java"), "test.ListRepoFactory");

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, p)")
                                 .doesNotContain("p.name()");
        }

        @Test
        void columnListAfterATableAlias_recordIsNotExpanded() throws Exception {
            assertDocuments("INSERT INTO documents AS d (profile) VALUES (:p)", "Profile p", "p");
        }

        @Test
        void columnList_recordOnTextColumn_isTheExistingTypeError_notAParseFailure() throws Exception {
            var result = compileSql("INSERT INTO documents (title) VALUES (:p)", "Profile p");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("Parameter 'p' has type 'test.Profile' which is neither a supported column type")
                                            .doesNotContain("SQL parse failed");
        }

        @Test
        void noColumnList_recordStillExpands() throws Exception {
            assertExpanded("INSERT INTO documents VALUES (:p)");
        }

        @Test
        void commentWithParenthesesBeforeValues_isNotAColumnList() throws Exception {
            assertExpanded("INSERT INTO documents -- (x)\nVALUES (:p)");
            assertExpanded("INSERT INTO documents /* (x) */ VALUES (:p)");
        }

        @Test
        void parenthesesBeforeTheInsert_areNotAColumnList() throws Exception {
            assertExpanded("WITH c AS (SELECT 1) INSERT INTO documents VALUES (:p)");
        }

        private void assertExpanded(String sql) throws Exception {
            var generated = factory(compile(STRING_REPO_HEADER + """
                record TitleOnly(String title) {}

                @PgSql
                public interface ExpandRepo {
                    @Query("%s")
                    Promise<Unit> store(TitleOnly p);
                }
                """.formatted(escaped(sql)), "test/ExpandRepo.java"), "test.ExpandRepoFactory");

            assertThat(generated).contains("(title) VALUES ($1)")
                                 .contains("p.title()");
        }

        private void assertStringOnJsonRefused(String sql, String params) throws Exception {
            assertRefused(sql, params, "'raw' is a String bound to column 'documents.profile' of type jsonb");
        }

        private void assertNotPlaced(String sql, String params, String param) throws Exception {
            assertRefused(sql, params, "'" + param + "' " + NOT_PLACED + ", in a statement on table 'documents'");
        }

        private void assertRefused(String sql, String params, String expected) throws Exception {
            var result = compileSql(sql, params);

            assertThat(result.success()).as(sql).isFalse();
            assertThat(result.diagnostics()).as(sql).contains(expected);
        }

        private void assertDocuments(String sql, String params, String... documents) throws Exception {
            var generated = factory(compileSql(sql, params), "test.PairRepoFactory");

            for (var document : documents) {
                assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, " + document + ")");
            }
        }

        private void assertCompilesWithoutDocuments(String sql, String params) throws Exception {
            var result = compileSql(sql, params);

            assertThat(result.success()).as(sql + "\n" + result.diagnostics()).isTrue();
            assertThat(result.generatedSource("test.PairRepoFactory")).as(sql).doesNotContain("JsonText");
        }

        private TestCompilationHelper.CompilationResult compileSql(String sql, String params) throws Exception {
            return compile(STRING_REPO_HEADER + PROFILE_RECORD + """
                @PgSql
                public interface PairRepo {
                    @Query("%s")
                    Promise<Unit> store(%s);
                }
                """.formatted(escaped(sql), params), "test/PairRepo.java");
        }

        private static String escaped(String sql) {
            return sql.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\t", "\\t");
        }
    }

    /// The CRUD condition walk skips a condition that takes no parameter (`IsNull`), so the next parameter keeps its
    /// own column.
    @Test
    void crudNoParamCondition_doesNotShiftTheNextParam() throws Exception {
        var result = compile(STRING_REPO_HEADER + """
            record TitleOnly(String title) {}

            record DocumentRow(long id, String title) {}

            @PgSql
            public interface CrudRepo {
                @Table(DocumentRow.class)
                Promise<List<DocumentRow>> findByPreviousIsNullAndTitle(TitleOnly title);
            }
            """, "test/CrudRepo.java");

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics()).contains("Parameter 'title' has type 'test.TitleOnly' which is neither a supported column type")
                                        .doesNotContain("[PG-VALIDATE] @Jsonb");
    }

    // --- helpers ---

    private TestCompilationHelper.CompilationResult compile(String source) throws Exception {
        return compile(source, "test/DocRepo.java");
    }

    private TestCompilationHelper.CompilationResult compile(String source, String fileName) throws Exception {
        return TestCompilationHelper.compileWithProcessor(source, fileName, tempDir);
    }

    private static String factory(TestCompilationHelper.CompilationResult result, String factoryName) throws Exception {
        assertThat(result.success()).as(result.diagnostics()).isTrue();

        var generated = result.generatedSource(factoryName);

        assertThat(generated).isNotNull();

        return generated;
    }

    private static String factory(TestCompilationHelper.CompilationResult result) throws Exception {
        assertThat(result.success()).as(result.diagnostics()).isTrue();

        var generated = result.generatedSource("test.DocRepoFactory");

        assertThat(generated).isNotNull();

        return generated;
    }
}
