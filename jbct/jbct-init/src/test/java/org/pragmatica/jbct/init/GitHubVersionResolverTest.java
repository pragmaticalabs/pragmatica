package org.pragmatica.jbct.init;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubVersionResolverTest {
    private static final Cause NETWORK_DOWN = Causes.cause("network down (test stub)");

    @Test
    void selfDerivedVersion_returnsPomVersion_whenBuildResourceFiltered() {
        var resolver = GitHubVersionResolver.gitHubVersionResolver();

        assertThat(resolver.selfDerivedVersion()
                           .isPresent())
                  .isTrue();
        assertThat(resolver.selfDerivedVersion()
                           .or(""))
                  .matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void resolveMonorepoVersion_fallsBackToSelfDerived_whenGitHubFails() {
        var resolver = GitHubVersionResolver.gitHubVersionResolver(failingHttp(),
                                                                   new Properties(),
                                                                   Option.some("9.9.9-test"));

        var result = resolver.resolveMonorepoVersion();

        assertThat(result.isSuccess())
                  .isTrue();
        result.onSuccess(version -> assertThat(version).isEqualTo("9.9.9-test"));
    }

    @Test
    void resolveMonorepoVersion_failsWithActionableCause_whenAllSourcesUnavailable() {
        var resolver = GitHubVersionResolver.gitHubVersionResolver(failingHttp(),
                                                                   new Properties(),
                                                                   Option.none());

        var result = resolver.resolveMonorepoVersion();

        assertThat(result.isSuccess())
                  .isFalse();
        result.onFailure(cause -> assertThat(cause.message()).contains("--version")
                                                             .contains("jbct init"));
    }

    // Stub whose only request path fails, simulating an offline / rate-limited GitHub API.
    private static HttpOperations failingHttp() {
        return new HttpOperations() {
            @Override
            public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
                return NETWORK_DOWN.promise();
            }
        };
    }
}
