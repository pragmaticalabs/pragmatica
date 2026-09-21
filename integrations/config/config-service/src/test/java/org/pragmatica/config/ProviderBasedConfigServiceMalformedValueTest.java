package org.pragmatica.config;

import java.util.Map;

import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;


/// #1098 at record-binding level — the same conflation the `Option`-returning readers had, one
/// layer down. A malformed component value used to become either `Option.none()` (an `Option<T>`
/// component) or a `SectionNotFound`, and `SectionNotFound` is precisely what lets a record's
/// `DEFAULT` instance stand in — so `port = "80x"` bound `DEFAULT.port()` and nothing said so.
/// Every record here carries a `DEFAULT` so the silent path is the one under test; the assertions
/// require a failure that names the key and the raw value.
class ProviderBasedConfigServiceMalformedValueTest {
    private static ConfigService serviceFrom(Map<String, String> values) {
        var source = MapConfigSource.mapConfigSource("test-source", values).unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();

        return providerBasedConfigService(provider);
    }

    record PortConfig(String name, int port) {
        public static final PortConfig DEFAULT = new PortConfig("default", 8080);
    }

    record OptionalPortConfig(String name, Option<Integer> port) {
        public static final OptionalPortConfig DEFAULT = new OptionalPortConfig("default", Option.none());
    }

    enum Mode { FAST, SAFE }

    record ModeConfig(String name, Mode mode) {
        public static final ModeConfig DEFAULT = new ModeConfig("default", Mode.SAFE);
    }

    record FlagConfig(String name, boolean enabled) {
        public static final FlagConfig DEFAULT = new FlagConfig("default", true);
    }

    record OptionalFlagConfig(String name, Option<Boolean> enabled) {
        public static final OptionalFlagConfig DEFAULT = new OptionalFlagConfig("default", Option.none());
    }

    record TimeoutConfig(String name, TimeSpan timeout) {
        public static final TimeoutConfig DEFAULT = new TimeoutConfig("default", TimeSpan.timeSpan(1).seconds());
    }

    record RetryConfig(int maxAttempts, BackoffStrategy backoffStrategy) {
        public static final RetryConfig DEFAULT = new RetryConfig(3, BackoffStrategy.fixed().interval(TimeSpan.timeSpan(1).seconds()));
    }

    private static void assertRefusedNamingKeyAndValue(Result<?> result, String key, String raw) {
        assertThat(result.isFailure()).describedAs("%s=\"%s\" must fail the bind, not bind %s", key, raw, result)
                                      .isTrue();
        result.onFailure(cause -> assertThat(cause.message()).describedAs("the refusal names the key and the raw value")
                                                             .contains(key)
                                                             .contains(raw));
    }

    @Nested
    class RequiredComponents {
        @Test
        void malformedInt_isRefused_notReplacedByDefault() {
            var result = serviceFrom(Map.of("test.name", "primary", "test.port", "80x")).config("test", PortConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.port", "80x");
        }

        @Test
        void malformedEnum_isRefused_notReplacedByDefault() {
            var result = serviceFrom(Map.of("test.name", "primary", "test.mode", "TURBO")).config("test", ModeConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.mode", "TURBO");
        }

        @Test
        void malformedBoolean_isRefused_notCoercedToFalse() {
            var result = serviceFrom(Map.of("test.name", "primary", "test.enabled", "yes")).config("test", FlagConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.enabled", "yes");
        }

        @Test
        void malformedTimeSpan_isRefused_notReplacedByDefault() {
            var result = serviceFrom(Map.of("test.name", "primary", "test.timeout", "soon")).config("test", TimeoutConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.timeout", "soon");
        }

        /// Control: absence still takes the DEFAULT instance — that path is what `DEFAULT` is for.
        @Test
        void absentComponent_stillBindsDefault() {
            var result = serviceFrom(Map.of("test.name", "primary")).config("test", PortConfig.class);

            assertThat(result.unwrap().port()).isEqualTo(8080);
        }
    }

    @Nested
    class OptionalComponents {
        @Test
        void malformedOptionalInt_isRefused_notReadAsAbsent() {
            var result = serviceFrom(Map.of("test.name", "primary", "test.port", "80x")).config("test", OptionalPortConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.port", "80x");
        }

        @Test
        void malformedOptionalBoolean_isRefused_notCoercedToFalse() {
            var result = serviceFrom(Map.of("test.name", "primary", "test.enabled", "yes")).config("test", OptionalFlagConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.enabled", "yes");
        }

        /// Control: an absent optional component is `none()`, and a well-formed one binds.
        @Test
        void absentOptional_isNone_wellFormedOptional_binds() {
            assertThat(serviceFrom(Map.of("test.name", "primary")).config("test", OptionalPortConfig.class).unwrap().port()).isEqualTo(Option.none());
            assertThat(serviceFrom(Map.of("test.name", "primary", "test.port", "8443")).config("test", OptionalPortConfig.class).unwrap().port()).isEqualTo(Option.some(8443));
            assertThat(serviceFrom(Map.of("test.name", "primary", "test.enabled", "FALSE")).config("test", OptionalFlagConfig.class).unwrap().enabled()).isEqualTo(Option.some(false));
        }
    }

    @Nested
    class BackoffStrategyComponents {
        @Test
        void exponential_malformedFactor_isRefused_notReplacedByFallback() {
            var result = serviceFrom(Map.of("test.max_attempts", "3",
                                            "test.backoff_strategy.type", "exponential",
                                            "test.backoff_strategy.factor", "2x")).config("test", RetryConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.backoff_strategy.factor", "2x");
        }

        @Test
        void exponential_malformedDelay_isRefused_notReplacedByFallback() {
            var result = serviceFrom(Map.of("test.max_attempts", "3",
                                            "test.backoff_strategy.type", "exponential",
                                            "test.backoff_strategy.initial_delay", "soon")).config("test", RetryConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.backoff_strategy.initial_delay", "soon");
        }

        @Test
        void exponential_malformedJitter_isRefused_notCoercedToFalse() {
            var result = serviceFrom(Map.of("test.max_attempts", "3",
                                            "test.backoff_strategy.type", "exponential",
                                            "test.backoff_strategy.with_jitter", "yes")).config("test", RetryConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.backoff_strategy.with_jitter", "yes");
        }

        @Test
        void fixed_malformedInterval_isRefused_notReplacedByDefault() {
            var result = serviceFrom(Map.of("test.max_attempts", "3",
                                            "test.backoff_strategy.type", "fixed",
                                            "test.backoff_strategy.interval", "soon")).config("test", RetryConfig.class);

            assertRefusedNamingKeyAndValue(result, "test.backoff_strategy.interval", "soon");
        }
    }
}
