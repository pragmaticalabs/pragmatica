package org.pragmatica.aether.infra.feature;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFlagsTest {
    private FeatureFlags featureFlags;

    @BeforeEach
    void setUp() {
        featureFlags = FeatureFlags.inMemory();
    }

    @Test
    void isEnabled_returns_false_for_unknown_flag() {
        featureFlags.isEnabled("unknown-flag")
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(enabled -> assertThat(enabled).isFalse());
    }

    @Test
    void isEnabled_returns_default_value() {
        var config = FlagConfig.flagConfig(true).unwrap();
        featureFlags.setFlag("my-flag", config).await();

        featureFlags.isEnabled("my-flag")
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(enabled -> assertThat(enabled).isTrue());
    }

    @Test
    void isEnabled_respects_context_override() {
        var config = FlagConfig.flagConfig(false, Map.of("user", true)).unwrap();
        featureFlags.setFlag("my-flag", config).await();

        featureFlags.isEnabled("my-flag", Context.context("user", "123"))
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(enabled -> assertThat(enabled).isTrue());

        featureFlags.isEnabled("my-flag", Context.empty())
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(enabled -> assertThat(enabled).isFalse());
    }

    @Test
    void getVariant_returns_empty_for_unknown_flag() {
        featureFlags.getVariant("unknown-flag")
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(variant -> assertThat(variant.isEmpty()).isTrue());
    }

    @Test
    void getVariant_returns_enabled_or_disabled() {
        var enabledConfig = FlagConfig.flagConfig(true).unwrap();
        var disabledConfig = FlagConfig.flagConfig(false).unwrap();

        featureFlags.setFlag("enabled-flag", enabledConfig).await();
        featureFlags.setFlag("disabled-flag", disabledConfig).await();

        featureFlags.getVariant("enabled-flag")
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(variant -> {
                        assertThat(variant.isPresent()).isTrue();
                        variant.onPresent(v -> assertThat(v).isEqualTo("enabled"));
                    });

        featureFlags.getVariant("disabled-flag")
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(variant -> {
                        assertThat(variant.isPresent()).isTrue();
                        variant.onPresent(v -> assertThat(v).isEqualTo("disabled"));
                    });
    }

    @Test
    void setFlag_updates_existing_flag() {
        var config1 = FlagConfig.flagConfig(false).unwrap();
        var config2 = FlagConfig.flagConfig(true).unwrap();

        featureFlags.setFlag("my-flag", config1).await();
        featureFlags.setFlag("my-flag", config2).await();

        featureFlags.isEnabled("my-flag")
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(enabled -> assertThat(enabled).isTrue());
    }

    @Test
    void deleteFlag_removes_flag() {
        var config = FlagConfig.flagConfig(true).unwrap();
        featureFlags.setFlag("my-flag", config).await();

        featureFlags.deleteFlag("my-flag")
                    .await()
                    .onFailureRun(Assertions::fail);

        featureFlags.isEnabled("my-flag")
                    .await()
                    .onSuccess(enabled -> assertThat(enabled).isFalse());
    }

    @Test
    void deleteFlag_fails_for_unknown_flag() {
        featureFlags.deleteFlag("unknown-flag")
                    .await()
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause).isInstanceOf(FeatureFlagError.FlagNotFound.class));
    }

    @Test
    void listFlags_returns_all_flags() {
        var config1 = FlagConfig.flagConfig(true).unwrap();
        var config2 = FlagConfig.flagConfig(false).unwrap();

        featureFlags.setFlag("flag1", config1).await();
        featureFlags.setFlag("flag2", config2).await();

        featureFlags.listFlags()
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(flags -> {
                        assertThat(flags).hasSize(2);
                        assertThat(flags).containsKey("flag1");
                        assertThat(flags).containsKey("flag2");
                    });
    }

    @Test
    void percentage_rollout_is_consistent() {
        var config = FlagConfig.flagConfig(false, 50).unwrap();
        featureFlags.setFlag("rollout-flag", config).await();

        // Same context should always get same result
        var context = Context.context("user", "test-user-123");
        var firstResult = featureFlags.isEnabled("rollout-flag", context)
                                       .await()
                                       .fold(c -> false, b -> b);

        for (int i = 0; i < 10; i++) {
            var result = featureFlags.isEnabled("rollout-flag", context)
                                      .await()
                                      .fold(c -> false, b -> b);
            assertThat(result).isEqualTo(firstResult);
        }
    }

    @Test
    void flagConfig_rejects_invalid_percentage() {
        FlagConfig.flagConfig(true, -1)
                  .onSuccessRun(Assertions::fail);

        FlagConfig.flagConfig(true, 101)
                  .onSuccessRun(Assertions::fail);
    }

    @Test
    void flagConfig_accepts_valid_percentage() {
        FlagConfig.flagConfig(true, 0)
                  .onFailureRun(Assertions::fail);

        FlagConfig.flagConfig(true, 100)
                  .onFailureRun(Assertions::fail);

        FlagConfig.flagConfig(true, 50)
                  .onFailureRun(Assertions::fail)
                  .onSuccess(config -> {
                      assertThat(config.percentage().isPresent()).isTrue();
                      config.percentage().onPresent(p -> assertThat(p).isEqualTo(50));
                  });
    }

    @Test
    void context_factory_methods_work() {
        var ctx1 = Context.context("key", "value");
        ctx1.get("key").onPresent(v -> assertThat(v).isEqualTo("value"));
        assertThat(ctx1.get("key").isPresent()).isTrue();

        var ctx2 = Context.context(Map.of("a", "1", "b", "2"));
        ctx2.get("a").onPresent(v -> assertThat(v).isEqualTo("1"));
        ctx2.get("b").onPresent(v -> assertThat(v).isEqualTo("2"));

        var empty = Context.empty();
        assertThat(empty.attributes()).isEmpty();
        assertThat(empty.get("missing").isEmpty()).isTrue();
    }
}
