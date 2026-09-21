package org.pragmatica.config;

import java.util.Map;

import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;
import static org.assertj.core.api.Assertions.assertThat;


/// #1098 — a value that is PRESENT but UNPARSEABLE must not read the same as a key that was never
/// configured. Every typed reader answers `Result<Option<T>>`: absent → `Success(None)`, malformed
/// → a [ConfigError.TypeMismatch] naming the key and the raw value. Before #1098 every probe here
/// read `None()` (or, for booleans, a silently coerced `false`), which is exactly what let a
/// caller's default apply without a word.
class MalformedValueReaderTest {
    private static ConfigSource source(Map<String, String> values) {
        return MapConfigSource.mapConfigSource("test-source", values).unwrap();
    }

    private static ConfigService service(Map<String, String> values) {
        return providerBasedConfigService(ConfigurationProvider.builder().withSource(source(values)).build());
    }

    private static void assertRefusedNamingKeyAndValue(Result<?> read, String key, String raw) {
        assertThat(read.isFailure()).describedAs("%s=\"%s\" must be refused, not read as %s", key, raw, read).isTrue();
        read.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ConfigError.TypeMismatch.class);
            assertThat(cause.message()).contains(key)
                      .contains(raw);
        });
    }

    @Nested
    class ConfigSourceReaders {
        @Test
        void getInt_malformedValue_isRefusedNamingKeyAndValue() {
            assertRefusedNamingKeyAndValue(source(Map.of("server.port", "80x")).getInt("server.port"),
                                           "server.port",
                                           "80x");
        }

        @Test
        void getLong_malformedValue_isRefusedNamingKeyAndValue() {
            assertRefusedNamingKeyAndValue(source(Map.of("pool.size", "twelve")).getLong("pool.size"),
                                           "pool.size",
                                           "twelve");
        }

        @Test
        void getDouble_malformedValue_isRefusedNamingKeyAndValue() {
            assertRefusedNamingKeyAndValue(source(Map.of("pool.ratio", "half")).getDouble("pool.ratio"),
                                           "pool.ratio",
                                           "half");
        }

        @Test
        void getBoolean_malformedValue_isRefusedNamingKeyAndValue() {
            assertRefusedNamingKeyAndValue(source(Map.of("server.secure", "yes")).getBoolean("server.secure"),
                                           "server.secure",
                                           "yes");
        }

        /// Control: absent stays absent, as `Success(None)`.
        @Test
        void absentKey_isSuccessNone() {
            assertThat(source(Map.of()).getInt("server.port")).isEqualTo(Result.success(Option.none()));
            assertThat(source(Map.of()).getLong("pool.size")).isEqualTo(Result.success(Option.none()));
            assertThat(source(Map.of()).getDouble("pool.ratio")).isEqualTo(Result.success(Option.none()));
            assertThat(source(Map.of()).getBoolean("server.secure")).isEqualTo(Result.success(Option.none()));
        }

        /// Control: well-formed values parse, booleans in any case.
        @Test
        void wellFormedValue_isSuccessSome() {
            var values = Map.of("server.port",
                                "8443",
                                "pool.size",
                                "9000000000",
                                "pool.ratio",
                                "0.25",
                                "server.secure",
                                "TRUE");

            assertThat(source(values).getInt("server.port")).isEqualTo(Result.success(Option.some(8443)));
            assertThat(source(values).getLong("pool.size")).isEqualTo(Result.success(Option.some(9_000_000_000L)));
            assertThat(source(values).getDouble("pool.ratio")).isEqualTo(Result.success(Option.some(0.25)));
            assertThat(source(values).getBoolean("server.secure")).isEqualTo(Result.success(Option.some(true)));
        }
    }

    @Nested
    class ConfigServiceReaders {
        @Test
        void getInt_malformedValue_isRefusedNamingKeyAndValue() {
            assertRefusedNamingKeyAndValue(service(Map.of("server.port", "80x")).getInt("server.port"),
                                           "server.port",
                                           "80x");
        }

        @Test
        void getBoolean_malformedValue_isRefusedNotCoercedToFalse() {
            assertRefusedNamingKeyAndValue(service(Map.of("server.secure", "yes")).getBoolean("server.secure"),
                                           "server.secure",
                                           "yes");
        }

        @Test
        void absentKey_isSuccessNone_wellFormed_isSuccessSome() {
            assertThat(service(Map.of()).getInt("server.port")).isEqualTo(Result.success(Option.none()));
            assertThat(service(Map.of()).getBoolean("server.secure")).isEqualTo(Result.success(Option.none()));
            assertThat(service(Map.of("server.port", "80")).getInt("server.port")).isEqualTo(Result.success(Option.some(80)));
            assertThat(service(Map.of("server.secure", "false")).getBoolean("server.secure")).isEqualTo(Result.success(Option.some(false)));
        }
    }

    /// The TOML-backed `ConfigService` (no production caller, kept honest all the same): a quoted
    /// value that is not a number or a boolean is refused, not read as absent.
    @Nested
    class TomlConfigServiceReaders {
        private ConfigService toml(String content) {
            return TomlConfigService.tomlConfigService(content).unwrap();
        }

        @Test
        void getInt_malformedValue_isRefusedNamingKeyAndValue() {
            assertRefusedNamingKeyAndValue(toml("[server]\nport = \"80x\"\n").getInt("server.port"), "server.port", "80x");
        }

        @Test
        void getBoolean_malformedValue_isRefusedNamingKeyAndValue() {
            assertRefusedNamingKeyAndValue(toml("[server]\nsecure = \"yes\"\n").getBoolean("server.secure"),
                                           "server.secure",
                                           "yes");
        }

        record OptionalPort(String name, Option<Integer> port) {}

        /// The record binder reads through TomlDocument's typed getters; a mistyped optional component
        /// bound `none()` and the record was built. The bind refuses it by name (#1098, A4).
        @Test
        void config_malformedOptionalComponent_isRefusedNamingKeyAndValue() {
            var result = toml("[test]\nname = \"n\"\nport = \"80x\"\n").config("test", OptionalPort.class);

            assertThat(result.isFailure()).describedAs("port = \"80x\" must refuse the bind, bound %s", result)
                                          .isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("test.port")
                                                                 .contains("80x"));
            assertThat(toml("[test]\nname = \"n\"\nport = 8443\n").config("test", OptionalPort.class).unwrap().port()).isEqualTo(Option.some(8443));
        }

        @Test
        void absentKey_isSuccessNone_wellFormed_isSuccessSome() {
            assertThat(toml("[server]\nport = 80\nsecure = true\n").getInt("server.nope")).isEqualTo(Result.success(Option.none()));
            assertThat(toml("[server]\nport = 80\nsecure = true\n").getInt("server.port")).isEqualTo(Result.success(Option.some(80)));
            assertThat(toml("[server]\nport = 80\nsecure = true\n").getBoolean("server.secure")).isEqualTo(Result.success(Option.some(true)));
        }
    }
}
