package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SliceConfigTest {

    @Test
    void sliceConfig_returnsLocalRepository() {
        var config = SliceConfig.sliceConfig();

        assertThat(config.repositories()).hasSize(1);
        assertThat(config.repositories().getFirst()).isInstanceOf(RepositoryType.Local.class);
    }

    @Test
    void sliceConfig_succeeds_forSingleRepository() {
        SliceConfig.sliceConfigFromNames(List.of("local"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> {
                       assertThat(config.repositories()).hasSize(1);
                       assertThat(config.repositories().getFirst()).isInstanceOf(RepositoryType.Local.class);
                   });
    }

    @Test
    void sliceConfig_succeeds_forMultipleRepositories() {
        SliceConfig.sliceConfigFromNames(List.of("local", "builtin"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> {
                       assertThat(config.repositories()).hasSize(2);
                       assertThat(config.repositories().get(0)).isInstanceOf(RepositoryType.Local.class);
                       assertThat(config.repositories().get(1)).isInstanceOf(RepositoryType.Builtin.class);
                   });
    }

    @Test
    void sliceConfig_succeeds_forBuiltinOnly() {
        SliceConfig.sliceConfigFromNames(List.of("builtin"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> {
                       assertThat(config.repositories()).hasSize(1);
                       assertThat(config.repositories().getFirst()).isInstanceOf(RepositoryType.Builtin.class);
                   });
    }

    @Test
    void sliceConfig_preservesOrder() {
        SliceConfig.sliceConfigFromNames(List.of("builtin", "local"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> {
                       assertThat(config.repositories()).hasSize(2);
                       assertThat(config.repositories().get(0)).isInstanceOf(RepositoryType.Builtin.class);
                       assertThat(config.repositories().get(1)).isInstanceOf(RepositoryType.Local.class);
                   });
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
        var config = SliceConfig.sliceConfig(new RepositoryType.Builtin(), new RepositoryType.Local());

        assertThat(config.repositories()).hasSize(2);
        assertThat(config.repositories().get(0)).isInstanceOf(RepositoryType.Builtin.class);
        assertThat(config.repositories().get(1)).isInstanceOf(RepositoryType.Local.class);
    }

    @Test
    void withRepositories_createsNewConfigWithNewTypes() {
        var original = SliceConfig.sliceConfig();
        var updated = original.withRepositories(List.of(new RepositoryType.Builtin()));

        assertThat(original.repositories()).hasSize(1);
        assertThat(original.repositories().getFirst()).isInstanceOf(RepositoryType.Local.class);
        assertThat(updated.repositories()).hasSize(1);
        assertThat(updated.repositories().getFirst()).isInstanceOf(RepositoryType.Builtin.class);
    }

    @Test
    void sliceConfig_succeeds_forRemoteRepository() {
        SliceConfig.sliceConfigFromNames(List.of("local", "remote:central"))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> {
                       assertThat(config.repositories()).hasSize(2);
                       assertThat(config.repositories().get(0)).isInstanceOf(RepositoryType.Local.class);
                       assertThat(config.repositories().get(1)).isInstanceOf(RepositoryType.Remote.class);
                       var remote = (RepositoryType.Remote) config.repositories().get(1);
                       assertThat(remote.id()).isEqualTo("central");
                   });
    }
}
