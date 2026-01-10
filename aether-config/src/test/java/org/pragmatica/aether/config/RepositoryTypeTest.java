package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryTypeTest {

    @Test
    void repositoryType_succeeds_forLocal() {
        RepositoryType.repositoryType("local")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(type -> assertThat(type).isEqualTo(RepositoryType.LOCAL));
    }

    @Test
    void repositoryType_succeeds_forBuiltin() {
        RepositoryType.repositoryType("builtin")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(type -> assertThat(type).isEqualTo(RepositoryType.BUILTIN));
    }

    @Test
    void repositoryType_succeeds_forUpperCase() {
        RepositoryType.repositoryType("LOCAL")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(type -> assertThat(type).isEqualTo(RepositoryType.LOCAL));
    }

    @Test
    void repositoryType_succeeds_forMixedCase() {
        RepositoryType.repositoryType("Builtin")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(type -> assertThat(type).isEqualTo(RepositoryType.BUILTIN));
    }

    @Test
    void repositoryType_succeeds_withWhitespace() {
        RepositoryType.repositoryType("  local  ")
                      .onFailureRun(Assertions::fail)
                      .onSuccess(type -> assertThat(type).isEqualTo(RepositoryType.LOCAL));
    }

    @Test
    void repositoryType_fails_forUnknownType() {
        RepositoryType.repositoryType("remote")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("unknown repository type"));
    }

    @Test
    void repositoryType_fails_forNull() {
        RepositoryType.repositoryType(null)
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("cannot be blank"));
    }

    @Test
    void repositoryType_fails_forBlank() {
        RepositoryType.repositoryType("   ")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("cannot be blank"));
    }

    @Test
    void configName_returnsExpectedValues() {
        assertThat(RepositoryType.LOCAL.configName()).isEqualTo("local");
        assertThat(RepositoryType.BUILTIN.configName()).isEqualTo("builtin");
    }
}
