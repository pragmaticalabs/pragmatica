package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SliceConfigTest {

    @Test
    void defaults_returnsLocalRepository() {
        var config = SliceConfig.defaults();

        assertThat(config.repositories()).containsExactly(RepositoryType.LOCAL);
    }

    @Test
    void sliceConfig_succeeds_forSingleRepository() {
        SliceConfig.sliceConfig(List.of("local"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> assertThat(config.repositories()).containsExactly(RepositoryType.LOCAL));
    }

    @Test
    void sliceConfig_succeeds_forMultipleRepositories() {
        SliceConfig.sliceConfig(List.of("local", "builtin"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> assertThat(config.repositories())
                       .containsExactly(RepositoryType.LOCAL, RepositoryType.BUILTIN));
    }

    @Test
    void sliceConfig_succeeds_forBuiltinOnly() {
        SliceConfig.sliceConfig(List.of("builtin"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> assertThat(config.repositories()).containsExactly(RepositoryType.BUILTIN));
    }

    @Test
    void sliceConfig_preservesOrder() {
        SliceConfig.sliceConfig(List.of("builtin", "local"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> assertThat(config.repositories())
                       .containsExactly(RepositoryType.BUILTIN, RepositoryType.LOCAL));
    }

    @Test
    void sliceConfig_fails_forEmptyList() {
        SliceConfig.sliceConfig(List.of())
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("cannot be empty"));
    }

    @Test
    void sliceConfig_fails_forNull() {
        SliceConfig.sliceConfig(null)
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("cannot be empty"));
    }

    @Test
    void sliceConfig_fails_forInvalidRepository() {
        SliceConfig.sliceConfig(List.of("local", "unknown"))
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("unknown repository type"));
    }

    @Test
    void withTypes_createsConfigWithSpecifiedTypes() {
        var config = SliceConfig.withTypes(RepositoryType.BUILTIN, RepositoryType.LOCAL);

        assertThat(config.repositories()).containsExactly(RepositoryType.BUILTIN, RepositoryType.LOCAL);
    }

    @Test
    void withRepositories_createsNewConfigWithNewTypes() {
        var original = SliceConfig.defaults();
        var updated = original.withRepositories(List.of(RepositoryType.BUILTIN));

        assertThat(original.repositories()).containsExactly(RepositoryType.LOCAL);
        assertThat(updated.repositories()).containsExactly(RepositoryType.BUILTIN);
    }
}
