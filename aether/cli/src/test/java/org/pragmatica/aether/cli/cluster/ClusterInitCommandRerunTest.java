// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.config.toml.TomlParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;


/// #311 — re-running `cluster init` against an existing config must CONVERGE, not abort and not
/// destroy. Before: an existing output file aborted a batch run (`OutputExists`) and `--force`
/// replaced the file wholesale, so a hand-added section (an `[app-http.api-keys]` block, a tuned
/// timeout) was either a blocker or a casualty. Now: without `--force` the generated document is
/// merged INTO the existing one — every key init generates follows the new answers, every key it does
/// not generate survives, and the survivors are listed so a key init USED to generate but no longer
/// does is visible rather than silently stale. `--force` still overwrites. An existing file that does
/// not parse is refused, never clobbered.
class ClusterInitCommandRerunTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private static int init(Path output, String nodes, String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of("init",
                                                              "--non-interactive",
                                                              "--name", "test-cluster",
                                                              "--nodes", nodes,
                                                              "--output", output.toString()));
        args.addAll(java.util.List.of(extra));

        return new CommandLine(new ClusterCommand()).execute(args.toArray(String[]::new));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return fail("cannot read " + path + ": " + e.getMessage());
        }
    }

    @Test
    void rerun_sameAnswers_convergesWithoutForce_andWritesTheSameKeys(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "3")).isEqualTo(0);
        var first = TomlParser.parse(read(output)).unwrap();

        assertThat(init(output, "3")).as("a re-run with the same answers is not an error: " + err)
                                     .isEqualTo(0);
        var second = TomlParser.parse(read(output)).unwrap();

        assertThat(second.sections()).as("same answers, same keys and values — the run converged")
                                     .isEqualTo(first.sections());
        assertThat(second.tableArrays()).isEqualTo(first.tableArrays());
    }

    @Test
    void rerun_keepsHandAddedSection_andAppliesNewAnswers(@TempDir Path tmp) throws IOException {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "3")).isEqualTo(0);
        Files.writeString(output,
                          read(output) + "\n[app-http.api-keys.ops]\nrole = \"admin\"\nkey = \"${env:OPS_KEY}\"\n");

        assertThat(init(output, "5")).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(0);
        var merged = TomlParser.parse(read(output)).unwrap();

        assertThat(merged.getString("app-http.api-keys.ops", "key")).as("a section init does not generate survives the re-run")
                                                                    .isEqualTo(some("${env:OPS_KEY}"));
        assertThat(merged.sections().keySet()).as("the keys init generates follow the NEW answers: 5 nodes derive a worker "
                                                    + "tier (3 core + 2 worker) the 3-node file did not have")
                                              .anyMatch(section -> section.endsWith(".worker"));
        assertThat(read(output)).contains("count = 2");
        assertThat(out.toString(StandardCharsets.UTF_8)).as("the operator is told which keys were kept, so a key init used to "
                                                             + "generate and no longer does is visible rather than silently stale")
                                                        .contains("Merged into")
                                                        .contains("app-http.api-keys.ops.key");
    }

    @Test
    void rerun_withForce_overwrites_droppingHandAddedSection(@TempDir Path tmp) throws IOException {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "3")).isEqualTo(0);
        Files.writeString(output, read(output) + "\n[app-http.api-keys.ops]\nrole = \"admin\"\n");

        assertThat(init(output, "3", "--force")).isEqualTo(0);

        assertThat(read(output)).as("--force keeps its meaning: the file is replaced wholesale")
                                .doesNotContain("api-keys");
    }

    @Test
    void rerun_refusesAnUnparseableExistingFile_ratherThanClobberingIt(@TempDir Path tmp) throws IOException {
        var output = tmp.resolve("cluster-config.toml");
        Files.writeString(output, "[cluster\nname = broken\n");

        assertThat(init(output, "3")).isNotEqualTo(0);
        assertThat(read(output)).as("the operator's file is untouched")
                                .isEqualTo("[cluster\nname = broken\n");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(output.toString())
                                                        .contains("--force");
    }
}
