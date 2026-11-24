// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Result;
import org.pragmatica.terra.launcher.LaunchHttp.RuntimeInputs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3026: `terra:assemble --check` runs where the deployment's secrets are not. It checks everything known at build time,
/// names a missing deployment secret as not supplied, and the launch keeps refusing it.
class DeferredRuntimeInputsTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String BLUEPRINT = "[[slices]]\nartifact = \"test:config:1\"\n";

    @TempDir
    Path directory;

    @BeforeEach
    void environmentCarriesNoTerraSecret() {
        assumeTrue(System.getenv("TERRA_JWT__SECRET") == null, "the test supplies jwt.secret itself");
    }

    private static Result<LaunchHttp> deferred(Map<String, String> extra) {
        var values = new HashMap<>(Map.of("http.security_mode", "jwt", "jwt.algorithm", "HS256"));

        values.putAll(extra);

        return LaunchHttp.launchHttp(Path.of("."), ConfigurationProvider.builder().withDefaults(values).build(), RuntimeInputs.DEFERRED);
    }

    private static void assertRefused(Result<LaunchHttp> launch, String reason) {
        launch.onSuccess(_ -> fail("the check must be refused"))
              .onFailure(cause -> assertThat(cause.message()).contains(reason).doesNotContain(SECRET));
    }

    private void writeHost(String terraToml) throws Exception {
        Files.writeString(directory.resolve("blueprint.toml"), BLUEPRINT);
        Files.writeString(directory.resolve("terra.toml"), terraToml);
    }

    @Test
    void deferred_hs256WithoutSecret_passesAndNamesJwtSecret() {
        assertThat(deferred(Map.of()).unwrap().unsupplied()).containsExactly("jwt.secret");
    }

    @Test
    void deferred_hs256WithSecret_reportsNothing() {
        assertThat(deferred(Map.of("jwt.secret", SECRET)).unwrap().unsupplied()).isEmpty();
    }

    @Test
    void deferred_malformedSecretSuppliedAtBuildTime_stillFails() {
        assertRefused(deferred(Map.of("jwt.secret", "short-secret")), "too short");
        assertRefused(deferred(Map.of("jwt.secret", " ".repeat(40))), "blank");
    }

    @Test
    void deferred_everythingElseKnownAtBuildTime_stillFails() {
        assertRefused(deferred(Map.of("jwt.jwks_url", "https://auth.example.com/jwks.json")), "never reads a JWKS");
        assertRefused(deferred(Map.of("jwt.auth_schemes", "[]")), "auth_schemes is empty");
        assertRefused(deferred(Map.of("jwt.algorithm", "HS512")), "algorithm must be HS256");
    }

    @Test
    void deferred_jwksModeWithoutJwksUrl_stillFails() {
        assertRefused(LaunchHttp.launchHttp(Path.of("."),
                                            ConfigurationProvider.builder().withDefaults(Map.of("http.security_mode", "jwt")).build(),
                                            RuntimeInputs.DEFERRED),
                      "requires jwt.jwks_url");
    }

    @Test
    void deferred_jwksMode_reportsNothing() {
        var settings = ConfigurationProvider.builder()
                                            .withDefaults(Map.of("http.security_mode", "jwt", "jwt.jwks_url", "https://auth.example.com/jwks.json"))
                                            .build();

        assertThat(LaunchHttp.launchHttp(Path.of("."), settings, RuntimeInputs.DEFERRED).unwrap().unsupplied()).isEmpty();
    }

    @Test
    void required_hs256WithoutSecret_stillRefusesTheLaunch() {
        assertRefused(LaunchHttp.launchHttp(Path.of("."),
                                            ConfigurationProvider.builder()
                                                                 .withDefaults(Map.of("http.security_mode", "jwt", "jwt.algorithm", "HS256"))
                                                                 .build(),
                                            RuntimeInputs.REQUIRED),
                      "requires jwt.secret");
    }

    @Test
    void check_hs256WithoutSecret_succeedsAndTheReportNamesIt() throws Exception {
        writeHost("[http]\nsecurity_mode = \"jwt\"\n[jwt]\nalgorithm = \"HS256\"\n");

        var plan = TerraLaunchPlan.check(directory).await(timeSpan(5).seconds()).unwrap();

        assertThat(plan.unsuppliedInputs()).containsExactly("jwt.secret");
        assertThat(TerraMain.checkReport(plan)).isEqualTo("Terra check passed: 1 selected slices\n"
                                                         + "Runtime input not supplied (required at launch): jwt.secret");
    }

    @Test
    void check_withNothingMissing_reportsOnlyTheSelection() throws Exception {
        writeHost("[http]\nsecurity_mode = \"none\"\n");

        var plan = TerraLaunchPlan.check(directory).await(timeSpan(5).seconds()).unwrap();

        assertThat(plan.unsuppliedInputs()).isEmpty();
        assertThat(TerraMain.checkReport(plan)).isEqualTo("Terra check passed: 1 selected slices");
    }

    @Test
    void check_malformedSecretInTerraToml_stillFails() throws Exception {
        writeHost("[http]\nsecurity_mode = \"jwt\"\n[jwt]\nalgorithm = \"HS256\"\nsecret = \"short-secret\"\n");

        TerraLaunchPlan.check(directory)
                       .await(timeSpan(5).seconds())
                       .onSuccess(_ -> fail("a malformed secret must fail the check"))
                       .onFailure(cause -> assertThat(cause.message()).contains("too short"));
    }

    @Test
    void launch_hs256WithoutSecret_stillRefuses() throws Exception {
        writeHost("[http]\nsecurity_mode = \"jwt\"\n[jwt]\nalgorithm = \"HS256\"\n");

        TerraLaunchPlan.load(directory)
                       .await(timeSpan(5).seconds())
                       .onSuccess(_ -> fail("the launch must refuse a missing secret"))
                       .onFailure(cause -> assertThat(cause.message()).contains("requires jwt.secret"));
    }

    @Test
    void start_planCheckedWithAMissingSecret_refusesBeforeConstructingAnything() throws Exception {
        writeHost("[http]\nsecurity_mode = \"jwt\"\n[jwt]\nalgorithm = \"HS256\"\n");

        TerraLaunchPlan.check(directory)
                       .await(timeSpan(5).seconds())
                       .unwrap()
                       .start()
                       .await(timeSpan(5).seconds())
                       .onSuccess(_ -> fail("a check plan with a missing secret must not start"))
                       .onFailure(cause -> assertThat(cause.message()).contains("Runtime inputs not supplied: jwt.secret"));
    }

    @Test
    void loaded_checkFlag_defersTheSecret_noFlag_refusesIt() throws Exception {
        writeHost("[http]\nsecurity_mode = \"jwt\"\n[jwt]\nalgorithm = \"HS256\"\n");

        assertThat(TerraMain.loaded(new String[] {directory.toString(), "--check"}).await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(TerraMain.loaded(new String[] {directory.toString()}).await(timeSpan(5).seconds()).isFailure()).isTrue();
    }

    @Test
    void run_checkOnly_printsTheReportToTheCheckLog() throws Exception {
        writeHost("[http]\nsecurity_mode = \"jwt\"\n[jwt]\nalgorithm = \"HS256\"\n");

        var plan = TerraLaunchPlan.check(directory).await(timeSpan(5).seconds()).unwrap();
        var captured = new ByteArrayOutputStream();
        var original = System.out;

        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            TerraMain.run(plan, true);
        } finally {
            System.setOut(original);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Terra check passed: 1 selected slices")
                                                             .contains("Runtime input not supplied (required at launch): jwt.secret");
    }
}
