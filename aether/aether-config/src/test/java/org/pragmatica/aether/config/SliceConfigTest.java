package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SliceConfigTest {

    @Test
    void sliceConfig_returnsLocalRepository() {
        var config = SliceConfig.sliceConfig();

        assertThat(config.repositories()).containsExactly(RepositoryType.LOCAL);
    }

    @Test
    void sliceConfig_succeeds_forSingleRepository() {
        SliceConfig.sliceConfigFromNames(List.of("local"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> assertThat(config.repositories()).containsExactly(RepositoryType.LOCAL));
    }

    @Test
    void sliceConfig_succeeds_forMultipleRepositories() {
        SliceConfig.sliceConfigFromNames(List.of("local", "builtin"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> assertThat(config.repositories())
                       .containsExactly(RepositoryType.LOCAL, RepositoryType.BUILTIN));
    }

    @Test
    void sliceConfig_succeeds_forBuiltinOnly() {
        SliceConfig.sliceConfigFromNames(List.of("builtin"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> assertThat(config.repositories()).containsExactly(RepositoryType.BUILTIN));
    }

    @Test
    void sliceConfig_preservesOrder() {
        SliceConfig.sliceConfigFromNames(List.of("builtin", "local"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> assertThat(config.repositories())
                       .containsExactly(RepositoryType.BUILTIN, RepositoryType.LOCAL));
    }

    @Test
    void sliceConfig_fails_forEmptyList() {
        SliceConfig.sliceConfigFromNames(List.of())
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("cannot be empty"));
    }

    @Test
    void sliceConfig_fails_forNull() {
        SliceConfig.sliceConfigFromNames((List<String>) null)
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("cannot be empty"));
    }

    @Test
    void sliceConfig_fails_forInvalidRepository() {
        SliceConfig.sliceConfigFromNames(List.of("local", "unknown"))
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("unknown repository type"));
    }

    @Test
    void sliceConfig_createsConfigWithSpecifiedTypes() {
        var config = SliceConfig.sliceConfig(RepositoryType.BUILTIN, RepositoryType.LOCAL);

        assertThat(config.repositories()).containsExactly(RepositoryType.BUILTIN, RepositoryType.LOCAL);
    }

    @Test
    void withRepositories_createsNewConfigWithNewTypes() {
        var original = SliceConfig.sliceConfig();
        var updated = original.withRepositories(List.of(RepositoryType.BUILTIN));

        assertThat(original.repositories()).containsExactly(RepositoryType.LOCAL);
        assertThat(updated.repositories()).containsExactly(RepositoryType.BUILTIN);
    }
}
