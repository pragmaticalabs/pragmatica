package org.pragmatica.aether.resource.http;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigError;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;

/// #822 round 3 — v-cw2's probe. `json` is an `Option<JsonConfig>`, a TABLE (`[http.api.json]`). An operator who
/// writes `json = "snake_case"` meant something the binder cannot honour; at base that failed loudly
/// (`SectionNotFound`), and treating the scalar as an absent table would have bound `json = None` silently and
/// dropped it. It must stay loud, and typed: the key, and that a table was expected.
class HttpClientConfigScalarJsonTest {
    @Test
    void scalarWhereTheJsonTableIsExpected_failsWithATypeMismatchNamingTheKey_notBindingNone() {
        var source = MapConfigSource.mapConfigSource("test",
                                                     Map.of("http.api.base_url", "http://x",
                                                            "http.api.connect_timeout", "5s",
                                                            "http.api.request_timeout", "10s",
                                                            "http.api.follow_redirects", "NORMAL",
                                                            "http.api.json", "snake_case"))
                                     .unwrap();
        var result = providerBasedConfigService(ConfigurationProvider.builder().withSource(source).build())
                         .config("http.api", HttpClientConfig.class);

        assertThat(result.isFailure()).as("a scalar where a table is expected must not bind json = None").isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ConfigError.TypeMismatch.class);
            assertThat(cause.message()).contains("http.api.json").contains("table").doesNotContain("snake_case");
        });
    }
}
