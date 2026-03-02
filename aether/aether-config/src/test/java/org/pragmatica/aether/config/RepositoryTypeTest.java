package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryTypeTest {

    @Nested
    class LocalAndBuiltin {
        @Test
        void repositoryType_succeeds_forLocal() {
            RepositoryType.repositoryType("local")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(type -> assertThat(type).isInstanceOf(RepositoryType.Local.class));
        }

        @Test
        void repositoryType_succeeds_forBuiltin() {
            RepositoryType.repositoryType("builtin")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(type -> assertThat(type).isInstanceOf(RepositoryType.Builtin.class));
        }

        @Test
        void repositoryType_succeeds_forUpperCase() {
            RepositoryType.repositoryType("LOCAL")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(type -> assertThat(type).isInstanceOf(RepositoryType.Local.class));
        }

        @Test
        void repositoryType_succeeds_forMixedCase() {
            RepositoryType.repositoryType("Builtin")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(type -> assertThat(type).isInstanceOf(RepositoryType.Builtin.class));
        }

        @Test
        void repositoryType_succeeds_withWhitespace() {
            RepositoryType.repositoryType("  local  ")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(type -> assertThat(type).isInstanceOf(RepositoryType.Local.class));
        }
    }

    @Nested
    class RemoteParsing {
        @Test
        void repositoryType_succeeds_forRemoteCentral() {
            RepositoryType.repositoryType("remote:central")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(type -> {
                              assertThat(type).isInstanceOf(RepositoryType.Remote.class);
                              var remote = (RepositoryType.Remote) type;
                              assertThat(remote.id()).isEqualTo("central");
                              assertThat(remote.url()).isEqualTo(RepositoryType.CENTRAL_URL);
                          });
        }

        @Test
        void repositoryType_succeeds_forRemoteUrl() {
            RepositoryType.repositoryType("remote:https://nexus.example.com/repo/")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(type -> {
                              assertThat(type).isInstanceOf(RepositoryType.Remote.class);
                              var remote = (RepositoryType.Remote) type;
                              assertThat(remote.id()).isEqualTo("nexus-example-com");
                              assertThat(remote.url()).isEqualTo("https://nexus.example.com/repo/");
                          });
        }

        @Test
        void repositoryType_succeeds_forRemoteWithExplicitId() {
            RepositoryType.repositoryType("remote:myid:https://nexus.example.com/repo/")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(type -> {
                              assertThat(type).isInstanceOf(RepositoryType.Remote.class);
                              var remote = (RepositoryType.Remote) type;
                              assertThat(remote.id()).isEqualTo("myid");
                              assertThat(remote.url()).isEqualTo("https://nexus.example.com/repo/");
                          });
        }

        @Test
        void repositoryType_fails_forEmptyRemote() {
            RepositoryType.repositoryType("remote:")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("requires an identifier or URL"));
        }

        @Test
        void repositoryType_fails_forInvalidRemoteFormat() {
            RepositoryType.repositoryType("remote:not-a-url")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("invalid remote repository format"));
        }
    }

    @Nested
    class ErrorCases {
        @Test
        void repositoryType_fails_forUnknownType() {
            RepositoryType.repositoryType("invalid")
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
    }
}
