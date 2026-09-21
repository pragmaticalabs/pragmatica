// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node;

import java.util.Map;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #276 R20: the legacy `ConfigService` adapter parsed numbers with `Long.parseLong` /
/// `Double.parseDouble` inside `.map`, so a malformed value THREW `NumberFormatException` out of
/// `requireLong`/`requireDouble`/`getLong`/`getDouble` instead of failing the `Result` (or reading
/// as absent). Every probe here calls through a `ConfigService` whose value is not a number and
/// asserts no exception escapes.
class ConfigServiceConfigFacadeNumericTest {
    private static final ConfigFacade FACADE = NodeDeploymentManager.configServiceToFacade(serviceOver(Map.of("pool.size",
                                                                                                              "twelve",
                                                                                                              "pool.ratio",
                                                                                                              "half",
                                                                                                              "pool.flag",
                                                                                                              "yes",
                                                                                                              "pool.max",
                                                                                                              "42",
                                                                                                              "pool.load",
                                                                                                              "0.75")));

    @Test
    void requireLong_malformedValue_failsTheResult_doesNotThrow() {
        var result = callWithoutThrowing(() -> FACADE.requireLong("pool", "size"));

        assertThat(result.isFailure()).as("a malformed long must be a failed Result").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).as("the cause names the key and the value, not a stack trace")
                                            .contains("twelve")
                                            .contains("pool.size")
                                            .doesNotContain("\n"));
    }

    @Test
    void requireDouble_malformedValue_failsTheResult_doesNotThrow() {
        var result = callWithoutThrowing(() -> FACADE.requireDouble("pool", "ratio"));

        assertThat(result.isFailure()).as("a malformed double must be a failed Result").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("half")
                                            .contains("pool.ratio"));
    }

    /// #1098 — the optional twin must not read a malformed value as ABSENT either: that is the
    /// path on which the caller's default applies silently. (Before #1098 this test asserted the
    /// opposite, `readsAsAbsent` — it specified the defect.)
    @Test
    void getLong_malformedValue_failsNamingKeyAndValue_doesNotThrow() {
        assertRefused(callWithoutThrowing(() -> FACADE.getLong("pool", "size")), "pool.size", "twelve");
    }

    @Test
    void getDouble_malformedValue_failsNamingKeyAndValue_doesNotThrow() {
        assertRefused(callWithoutThrowing(() -> FACADE.getDouble("pool", "ratio")), "pool.ratio", "half");
    }

    @Test
    void getInt_malformedValue_failsNamingKeyAndValue_doesNotThrow() {
        assertRefused(callWithoutThrowing(() -> FACADE.getInt("pool", "size")), "pool.size", "twelve");
    }

    /// Before #1098 `ConfigService.getBoolean` went through `Boolean.parseBoolean`, so `"yes"` was
    /// not absent but a silent `false`.
    @Test
    void getBoolean_malformedValue_failsNamingKeyAndValue_doesNotThrow() {
        assertRefused(callWithoutThrowing(() -> FACADE.getBoolean("pool", "flag")), "pool.flag", "yes");
    }

    @Test
    void requireInt_and_requireBoolean_malformedValue_failNamingKeyAndValue() {
        assertRefused(FACADE.requireInt("pool", "size"), "pool.size", "twelve");
        assertRefused(FACADE.requireBoolean("pool", "flag"), "pool.flag", "yes");
    }

    private static void assertRefused(Result<?> read, String key, String raw) {
        assertThat(read.isFailure()).describedAs("%s=\"%s\" must be refused, read %s", key, raw, read).isTrue();
        read.onFailure(cause -> assertThat(cause.message()).contains(key)
                                          .contains(raw)
                                          .doesNotContain("not found"));
    }

    /// Control: well-formed values still parse.
    @Test
    void wellFormedValues_parse() {
        var max = FACADE.requireLong("pool", "max").fold(cause -> fail(cause.message()), v -> v);
        var load = FACADE.requireDouble("pool", "load").fold(cause -> fail(cause.message()), v -> v);

        assertThat(max).isEqualTo(42L);
        assertThat(load).isEqualTo(0.75);
        assertThat(FACADE.getLong("pool", "max")).isEqualTo(Result.success(Option.some(42L)));
        assertThat(FACADE.getDouble("pool", "load")).isEqualTo(Result.success(Option.some(0.75)));
    }

    /// Control: an absent key is a missing-key failure, distinct from a malformed one.
    @Test
    void absentKey_isMissing() {
        assertThat(FACADE.requireLong("pool", "nope").isFailure()).isTrue();
        assertThat(FACADE.getLong("pool", "nope")).isEqualTo(Result.success(Option.none()));
        assertThat(FACADE.getInt("pool", "nope")).isEqualTo(Result.success(Option.none()));
    }

    private static <T> T callWithoutThrowing(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return fail("must not throw across the Result boundary, threw " + e);
        }
    }

    /// The real `ProviderBasedConfigService` rather than a fake: a fake whose `getInt`/`getBoolean`
    /// always answered `none()` could not distinguish a malformed value from an absent one, which is
    /// the very thing #1098 pins.
    private static ConfigService serviceOver(Map<String, String> values) {
        var source = MapConfigSource.mapConfigSource("test-source", values).unwrap();

        return providerBasedConfigService(ConfigurationProvider.builder().withSource(source).build());
    }
}
