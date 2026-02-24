package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppHttpConfigApiKeysTest {

    @Test
    void appHttpConfig_defaultHasEmptyApiKeysAndSecurityDisabled() {
        var config = AppHttpConfig.appHttpConfig();

        assertThat(config.apiKeys()).isEmpty();
        assertThat(config.securityEnabled()).isFalse();
        assertThat(config.enabled()).isFalse();
    }

    @Test
    void appHttpConfig_withPortAndKeysWrapsWithDefaultEntry() {
        var config = AppHttpConfig.appHttpConfig(9090, Set.of("key1", "key2"));

        assertThat(config.enabled()).isTrue();
        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.apiKeys()).hasSize(2);
        assertThat(config.apiKeys()).containsKey("key1");
        assertThat(config.apiKeys()).containsKey("key2");
        assertThat(config.apiKeys().get("key1").roles()).containsExactly("service");
    }

    @Test
    void apiKeyValues_returnsKeyStringsFromMap() {
        var config = AppHttpConfig.appHttpConfig(8070, Set.of("alpha", "beta"));

        assertThat(config.apiKeyValues()).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void securityEnabled_returnsTrueWhenApiKeysNonEmpty() {
        var config = AppHttpConfig.appHttpConfig(8070, Set.of("key1"));

        assertThat(config.securityEnabled()).isTrue();
    }

    @Test
    void securityEnabled_returnsFalseWhenApiKeysEmpty() {
        var config = AppHttpConfig.appHttpConfig(8070);

        assertThat(config.securityEnabled()).isFalse();
    }

    @Test
    void withApiKeys_wrapsSimpleKeys() {
        var config = AppHttpConfig.appHttpConfig()
                                  .withEnabled(true)
                                  .withApiKeys(Set.of("my-key"));

        assertThat(config.securityEnabled()).isTrue();
        assertThat(config.apiKeys()).containsKey("my-key");
        assertThat(config.apiKeys().get("my-key").roles()).containsExactly("service");
    }

    @Test
    void withApiKeyMap_storesRichEntries() {
        var entries = Map.of(
            "key-abc", ApiKeyEntry.apiKeyEntry("admin-svc", Set.of("admin")),
            "key-xyz", ApiKeyEntry.apiKeyEntry("reader-svc", Set.of("service"))
        );

        var config = AppHttpConfig.appHttpConfig()
                                  .withEnabled(true)
                                  .withApiKeyMap(entries);

        assertThat(config.securityEnabled()).isTrue();
        assertThat(config.apiKeys()).hasSize(2);
        assertThat(config.apiKeys().get("key-abc").name()).isEqualTo("admin-svc");
        assertThat(config.apiKeys().get("key-abc").roles()).containsExactly("admin");
        assertThat(config.apiKeys().get("key-xyz").name()).isEqualTo("reader-svc");
    }

    @Test
    void apiKeys_areImmutable() {
        var config = AppHttpConfig.appHttpConfig(8070, Set.of("key1"));

        assertThat(config.apiKeys()).isUnmodifiable();
    }

    @Test
    void portFor_returnsOffsetPort() {
        var config = AppHttpConfig.appHttpConfig(8070);

        assertThat(config.portFor(0)).isEqualTo(8070);
        assertThat(config.portFor(2)).isEqualTo(8072);
    }

    @Test
    void withForwardTimeoutMs_overridesDefault() {
        var config = AppHttpConfig.appHttpConfig()
                                  .withForwardTimeoutMs(10000);

        assertThat(config.forwardTimeoutMs()).isEqualTo(10000);
    }

    @Test
    void withForwardMaxRetries_overridesDefault() {
        var config = AppHttpConfig.appHttpConfig()
                                  .withForwardMaxRetries(5);

        assertThat(config.forwardMaxRetries()).isEqualTo(5);
    }

    @Test
    void defaults_forwardTimeoutAndRetries() {
        var config = AppHttpConfig.appHttpConfig();

        assertThat(config.forwardTimeoutMs()).isEqualTo(AppHttpConfig.DEFAULT_FORWARD_TIMEOUT_MS);
        assertThat(config.forwardMaxRetries()).isEqualTo(AppHttpConfig.DEFAULT_FORWARD_MAX_RETRIES);
    }
}
