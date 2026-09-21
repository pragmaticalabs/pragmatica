package org.pragmatica.config;

import java.util.Map;

import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;


/// #1098 — a value that is PRESENT but UNPARSEABLE must not read the same as a key that was never
/// configured. Every probe here feeds a malformed value and asserts the read does not come back as
/// "absent" (or, for booleans, as a silently coerced `false`), because that is exactly what lets a
/// caller's default apply without a word.
class MalformedValueReaderTest {
    private static ConfigSource source(Map<String, String> values) {
        return MapConfigSource.mapConfigSource("test-source", values).unwrap();
    }

    private static ConfigService service(Map<String, String> values) {
        return providerBasedConfigService(ConfigurationProvider.builder().withSource(source(values)).build());
    }

    @Nested
    class ConfigSourceReaders {
        @Test
        void getInt_malformedValue_isNotAbsent() {
            var read = source(Map.of("server.port", "80x")).getInt("server.port");

            assertThat(read.isEmpty()).describedAs("server.port=\"80x\" must not read as absent, read %s", read)
                                      .isFalse();
        }

        @Test
        void getLong_malformedValue_isNotAbsent() {
            var read = source(Map.of("pool.size", "twelve")).getLong("pool.size");

            assertThat(read.isEmpty()).describedAs("pool.size=\"twelve\" must not read as absent, read %s", read)
                                      .isFalse();
        }

        @Test
        void getDouble_malformedValue_isNotAbsent() {
            var read = source(Map.of("pool.ratio", "half")).getDouble("pool.ratio");

            assertThat(read.isEmpty()).describedAs("pool.ratio=\"half\" must not read as absent, read %s", read)
                                      .isFalse();
        }

        @Test
        void getBoolean_malformedValue_isNotAbsent() {
            var read = source(Map.of("server.secure", "yes")).getBoolean("server.secure");

            assertThat(read.isEmpty()).describedAs("server.secure=\"yes\" must not read as absent, read %s", read)
                                      .isFalse();
        }

        /// Control: absent stays absent.
        @Test
        void absentKey_isAbsent() {
            assertThat(source(Map.of()).getInt("server.port").isEmpty()).isTrue();
            assertThat(source(Map.of()).getLong("pool.size").isEmpty()).isTrue();
            assertThat(source(Map.of()).getDouble("pool.ratio").isEmpty()).isTrue();
            assertThat(source(Map.of()).getBoolean("server.secure").isEmpty()).isTrue();
        }
    }

    @Nested
    class ConfigServiceReaders {
        @Test
        void getInt_malformedValue_isNotAbsent() {
            var read = service(Map.of("server.port", "80x")).getInt("server.port");

            assertThat(read.isEmpty()).describedAs("server.port=\"80x\" must not read as absent, read %s", read)
                                      .isFalse();
        }

        @Test
        void getBoolean_malformedValue_isNotCoercedToFalse() {
            var read = service(Map.of("server.secure", "yes")).getBoolean("server.secure");

            assertThat(read).describedAs("server.secure=\"yes\" must not read as a boolean")
                            .isNotEqualTo(Option.some(false));
        }
    }
}
