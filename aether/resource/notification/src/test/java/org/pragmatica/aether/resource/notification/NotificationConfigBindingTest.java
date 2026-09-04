// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.notification;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.net.smtp.SmtpTlsMode;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end verification (#671) that `NotificationConfig` binds from `aether.toml` text
/// through the real production path: [TomlConfigSource] -> [ConfigurationProvider] ->
/// [ProviderBasedConfigService] — the same wiring `SpiResourceProvider` uses at runtime.
///
/// `smtpConfig`/`httpConfig`/`retryConfig` are all `Option<Record>` components, so this also
/// pins the #671 recursive-Option-binding fix against the concrete casualty the ticket named:
/// before that fix every one of these silently bound to `Option.empty()` regardless of what the
/// TOML held.
///
/// The section names below (`smtp_config`/`http_config`/`retry_config`) are NOT what
/// `aether/docs/slice-developers/resource-reference.md` documented before this change — the docs
/// said `[notification.smtp]`/`[notification.http]`/`[notification.retry]`. The binder derives a
/// nested-record section from the RECORD COMPONENT NAME via camelCase -> snake_case
/// (`ProviderBasedConfigService#toSnakeCase`), with no override mechanism, so
/// `Option<SmtpConfig> smtpConfig` can only ever read `smtp_config`. That mechanism is correct and
/// consistent with every other nested config in the codebase — the docs were wrong, not the
/// binder, and were fixed in the same change that added this test.
class NotificationConfigBindingTest {

    private static NotificationConfig bindNotificationConfig(String toml) {
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();
        var service = ProviderBasedConfigService.providerBasedConfigService(provider);

        return service.config("notification", NotificationConfig.class).unwrap();
    }

    @Test
    void config_smtpBackend_bindsNestedSmtpAndRetryFromDocumentedToml() {
        var toml = """
            [notification]
            backend = "smtp"

            [notification.smtp_config]
            host = "smtp.example.com"
            port = 587
            tls_mode = "STARTTLS"
            connect_timeout = "10s"
            command_timeout = "30s"

            [notification.smtp_config.auth]
            username = "noreply@example.com"
            password = "s3cret"

            [notification.retry_config]
            max_attempts = 5
            """;

        var config = bindNotificationConfig(toml);

        assertThat(config.backend()).isEqualTo("smtp");
        assertThat(config.smtpConfig().isPresent()).isTrue();

        var smtp = config.smtpConfig().unwrap();
        assertThat(smtp.host()).isEqualTo("smtp.example.com");
        assertThat(smtp.port()).isEqualTo(587);
        assertThat(smtp.tlsMode()).isEqualTo(SmtpTlsMode.STARTTLS);
        assertThat(smtp.connectTimeout().millis()).isEqualTo(10_000L);
        assertThat(smtp.commandTimeout().millis()).isEqualTo(30_000L);
        assertThat(smtp.auth().isPresent()).isTrue();
        assertThat(smtp.auth().unwrap().username()).isEqualTo("noreply@example.com");
        assertThat(smtp.auth().unwrap().password()).isEqualTo("s3cret");

        assertThat(config.httpConfig().isEmpty()).isTrue();
        assertThat(config.effectiveRetryConfig().maxAttempts()).isEqualTo(5);
    }

    /// Pins the binder-mechanism gap discovered while writing the test above (not part of
    /// #671's named scope, reported as a follow-up): `SmtpConfig` declares convenience factory
    /// methods with defaults (`587`/`STARTTLS`/`10s`/`30s`), but the binder only ever calls a
    /// factory whose parameters match the full constructor — none of `SmtpConfig`'s overloads
    /// do — so it always falls back to the constructor, which needs every field. Unlike
    /// `RetryConfig`, `SmtpConfig` has no static `DEFAULT` instance for the binder's per-field
    /// fallback ([ProviderBasedConfigService#getDefaultComponentValue]) to read from, so omitting
    /// `connect_timeout`/`command_timeout` does not fail on those fields — it fails the entire
    /// `Option<SmtpConfig>` bind with a misattributed `NotificationConfig.smtpConfig` "section not
    /// found" error, even though `[notification.smtp_config]` is present.
    @Test
    void config_smtpBackend_omittedTimeoutFields_failsWithMisattributedSectionError() {
        var toml = """
            [notification]
            backend = "smtp"

            [notification.smtp_config]
            host = "smtp.example.com"
            port = 587
            tls_mode = "STARTTLS"
            """;

        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();
        var service = ProviderBasedConfigService.providerBasedConfigService(provider);

        var result = service.config("notification", NotificationConfig.class);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("NotificationConfig.smtpConfig"));
    }

    @Test
    void config_httpBackend_bindsNestedHttpConfigFromDocumentedToml() {
        var toml = """
            [notification]
            backend = "http"

            [notification.http_config]
            provider_hint = "sendgrid"
            api_key = "${secrets:sendgrid/api-key}"
            from_address = "noreply@example.com"

            [notification.retry_config]
            max_attempts = 5
            initial_delay = "2s"
            """;

        var config = bindNotificationConfig(toml);

        assertThat(config.backend()).isEqualTo("http");
        assertThat(config.httpConfig().isPresent()).isTrue();

        var http = config.httpConfig().unwrap();
        assertThat(http.providerHint()).isEqualTo("sendgrid");
        assertThat(http.apiKey()).isEqualTo("${secrets:sendgrid/api-key}");
        assertThat(http.fromAddress().unwrap()).isEqualTo("noreply@example.com");

        assertThat(config.smtpConfig().isEmpty()).isTrue();
        assertThat(config.effectiveRetryConfig().maxAttempts()).isEqualTo(5);
        assertThat(config.effectiveRetryConfig().initialDelay().millis()).isEqualTo(2000L);
    }

    /// Documents the #671 defect precisely: the section name the OLD docs told operators to
    /// write (`[notification.smtp]`) does not error — [ProviderBasedConfigService]'s
    /// `Option<Record>` path treats an absent section as `Option.empty()` by design (that is what
    /// lets an operator genuinely omit optional config). So writing the old documented TOML for a
    /// `"smtp"` backend silently produces a `NotificationConfig` with no SMTP settings at all,
    /// which is exactly the "SMTP backend selected but no SMTP configuration provided" failure
    /// `NotificationSenderFactory` would raise at provisioning time — with the operator's TOML
    /// looking, to them, entirely correct.
    @Test
    void config_smtpBackend_staleDocSectionName_silentlyBindsEmptyNotAnError() {
        var toml = """
            [notification]
            backend = "smtp"

            [notification.smtp]
            host = "smtp.example.com"
            """;

        var config = bindNotificationConfig(toml);

        assertThat(config.backend()).isEqualTo("smtp");
        assertThat(config.smtpConfig().isEmpty()).isTrue();
    }
}
