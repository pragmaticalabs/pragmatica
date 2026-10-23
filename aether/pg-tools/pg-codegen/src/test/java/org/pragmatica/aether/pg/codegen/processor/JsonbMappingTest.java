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

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, profile)");
        }

        @Test
        void queryPairOnJsonColumn_isDocument() throws Exception {
            var generated = factory(compile(INFERRED_REPO, "test/InferredRepo.java"), "test.InferredRepoFactory");

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, probe)")
                                 .contains("previous.map(present$ -> org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, present$))");
        }

        @Test
        void recordExpandedInQueryValues_componentOnJsonColumnIsDocument() throws Exception {
            var generated = factory(compile(INFERRED_REPO, "test/InferredRepo.java"), "test.InferredRepoFactory");

            assertThat(generated).contains("org.pragmatica.aether.resource.db.JsonText.jsonText(PROFILE_JSON, entry.profile())")
                                 .contains("entry.title()");
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
                                            .contains("component action: neither a record nor an enum");
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
        void unmarkedEnumOnJsonColumn_isNotInferred() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                enum Mood { CALM, BUSY }

                record DocumentRow(long id, Mood profile) {}

                @PgSql
                public interface EnumRepo {
                    Promise<Option<DocumentRow>> findById(long id);
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

            @Query("SELECT count(*) FROM documents WHERE profile = :probe")
            Promise<Long> countEqual(Probe probe);

            @Query("UPDATE documents SET previous = :previous WHERE id = :id")
            Promise<Unit> replacePrevious(long id, Option<Profile> previous);

            @Query("INSERT INTO documents VALUES(:entry)")
            Promise<Unit> add(Entry entry);
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
