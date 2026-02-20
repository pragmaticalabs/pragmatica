package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;

import static org.assertj.core.api.Assertions.assertThat;

class LogLevelKeyTest {

    @Nested
    class ForLoggerFactory {
        @Test
        void forLogger_creates_key_with_given_logger_name() {
            var key = LogLevelKey.forLogger("com.example.MyService");
            assertThat(key.loggerName()).isEqualTo("com.example.MyService");
        }

        @Test
        void forLogger_asString_produces_correct_format() {
            var key = LogLevelKey.forLogger("com.example.MyService");
            assertThat(key.asString()).isEqualTo("log-level/com.example.MyService");
        }

        @Test
        void forLogger_toString_returns_asString() {
            var key = LogLevelKey.forLogger("com.example.MyService");
            assertThat(key.toString()).isEqualTo(key.asString());
        }
    }

    @Nested
    class LogLevelKeyParsing {
        @Test
        void logLevelKey_from_valid_string_succeeds() {
            LogLevelKey.logLevelKey("log-level/com.example.MyService")
                       .onSuccess(key -> assertThat(key.loggerName()).isEqualTo("com.example.MyService"))
                       .onFailureRun(Assertions::fail);
        }

        @Test
        void logLevelKey_from_string_with_invalid_prefix_fails() {
            LogLevelKey.logLevelKey("invalid/com.example.MyService")
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertThat(cause.message()).contains("Invalid log-level key format"));
        }

        @Test
        void logLevelKey_from_string_with_empty_logger_fails() {
            LogLevelKey.logLevelKey("log-level/")
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertThat(cause.message()).contains("Invalid log-level key format"));
        }
    }

    @Nested
    class RoundTrip {
        @Test
        void logLevelKey_roundtrip_consistency() {
            var originalKey = LogLevelKey.forLogger("com.example.MyService");
            var keyString = originalKey.asString();
            LogLevelKey.logLevelKey(keyString)
                       .onSuccess(parsedKey -> assertThat(parsedKey).isEqualTo(originalKey))
                       .onFailureRun(Assertions::fail);
        }
    }

    @Nested
    class Equality {
        @Test
        void logLevelKey_equality_works() {
            var key1 = LogLevelKey.forLogger("com.example.MyService");
            var key2 = LogLevelKey.forLogger("com.example.MyService");
            assertThat(key1).isEqualTo(key2);
            assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        }

        @Test
        void logLevelKey_inequality_with_different_logger() {
            var key1 = LogLevelKey.forLogger("com.example.ServiceA");
            var key2 = LogLevelKey.forLogger("com.example.ServiceB");
            assertThat(key1).isNotEqualTo(key2);
        }
    }
}
