// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #311 — re-running `cluster init` against an existing config must CONVERGE, not abort and not
/// destroy. The existing file is the operator's: the merge rewrites ONLY the lines holding keys
/// init generates, and only where the operator agreed to the new value; every other line —
/// comments, blank lines, section order, hand-added sections and `[[…]]` tables — is preserved
/// byte-for-byte. A same-answers re-run is therefore byte-identical (idempotent). A key init owns
/// whose value differs is REPORTED as `section.key: <old> → <new>` and applied only on consent:
/// batch mode refuses (non-zero, file untouched) unless `--merge`; interactive mode asks, default
/// keep. Keys init owns that are absent are appended into their section. `--force` still overwrites
/// wholesale, and a file the merge cannot read is refused rather than clobbered.
class ClusterInitCommandRerunTest {
    private static final String TUNED_JVM_ARGS = "jvm_args = \"-XX:+UseZGC -XX:+ZGenerational -Xms2g -Xmx8g\"";
    private static final String TUNED_COMMENT = "# ops 2026-09-01: raised heap after OOM incident INC-42, do not lower";
    private static final String GENERATED_JVM_ARGS = "jvm_args = \"-XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx2g\"";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;
    private InputStream originalIn;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        originalIn = System.in;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        // A batch run must never prompt; if a regression makes it prompt, it reads EOF and takes
        // the default instead of hanging the suite on the real stdin (a mutation did exactly that).
        System.setIn(InputStream.nullInputStream());
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setIn(originalIn);
    }

    /// #1037 (#1019): the topology is asked for per tier — `--core-nodes` (5/7/9) and
    /// `--worker-nodes` — never inferred from a total. #1087 assumes that shape.
    private static int init(Path output, String workerNodes, String... extra) {
        var args = new ArrayList<>(List.of("init",
                                           "--non-interactive",
                                           "--name",
                                           "test-cluster",
                                           "--core-nodes",
                                           "5",
                                           "--worker-nodes",
                                           workerNodes,
                                           "--output",
                                           output.toString()));

        args.addAll(List.of(extra));

        return new CommandLine(new ClusterCommand()).execute(args.toArray(String[]::new));
    }

    private static int initCloud(Path output, String... extra) {
        var args = new ArrayList<>(List.of("init",
                                           "--name",
                                           "test-cluster",
                                           "--target",
                                           "cloud",
                                           "--provider",
                                           "hetzner",
                                           "--region",
                                           "hel1",
                                           "--instance-type",
                                           "cpx32",
                                           "--credential-env",
                                           "HCLOUD_TOKEN",
                                           "--ssh-public-key",
                                           "~/.ssh/id_ed25519.pub",
                                           "--admin-cidr",
                                           "203.0.113.0/24",
                                           "--core-nodes",
                                           "5",
                                           "--output",
                                           output.toString()));

        args.addAll(List.of(extra));

        return new CommandLine(new ClusterCommand()).execute(args.toArray(String[]::new));
    }

    /// The interactive wizard, answered for a 5-core docker cluster, followed by whatever the merge
    /// prompt is given. `System.in` is what both the wizard and the merge prompt read.
    private static int initInteractive(Path output, String mergeAnswer) {
        return initInteractive(output, "0", mergeAnswer);
    }

    private static int initInteractive(Path output, String workerNodes, String mergeAnswer) {
        var input = "test-cluster\n" +    // cluster name
                    "\n" +                // deployment target: default = DOCKER
                    "5\n" +               // core (consensus) node count
                    workerNodes + "\n" +  // worker node count
                    "n\n" +               // configure database? no
                    "\n" +                // generate config? default yes
                    mergeAnswer;

        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));

        return new CommandLine(new ClusterCommand()).execute("init", "--output", output.toString());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return fail("cannot read " + path + ": " + e.getMessage());
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            fail("cannot write " + path + ": " + e.getMessage());
        }
    }

    private static long commentLines(String text) {
        return text.lines().filter(line -> line.trim().startsWith("#")).count();
    }

    private static List<String> sectionHeaders(String text) {
        return text.lines().filter(line -> line.startsWith("[")).toList();
    }

    /// The lines of `before` that are not in `after` and vice versa, positionally — a one-line
    /// `diff`. Same line count is asserted by the caller, so a positional comparison is exact.
    private static List<String> lineDiff(String before, String after) {
        var b = before.lines().toList();
        var a = after.lines().toList();
        var diff = new ArrayList<String>();

        for (int i = 0; i < Math.max(b.size(), a.size()); i++) {
            var oldLine = i < b.size() ? b.get(i) : "<absent>";
            var newLine = i < a.size() ? a.get(i) : "<absent>";

            if (!oldLine.equals(newLine)) {
                diff.add("< " + oldLine);
                diff.add("> " + newLine);
            }
        }

        return diff;
    }

    private static String handTuned(Path output) {
        var edited = read(output).replace(GENERATED_JVM_ARGS, TUNED_COMMENT + "\n" + TUNED_JVM_ARGS);

        assertThat(edited).as("fixture control: the generated jvm_args line was found and replaced")
                  .isNotEqualTo(read(output));
        write(output, edited);

        return edited;
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    // ---- B3: idempotence ------------------------------------------------------------------------

    @Test
    void rerun_sameAnswers_isByteIdentical_keepingEveryCommentAndSectionInOrder(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var first = read(output);

        assertThat(commentLines(first)).as("positive control: the generated scaffold carries its commented templates")
                  .isGreaterThan(30);

        for (int run = 2; run <= 4; run++) {
            assertThat(init(output, "0")).as("re-run " + run + " with the same answers is not an error: " + stderr())
                      .isEqualTo(0);
            assertThat(read(output)).as("re-run " + run + ": same answers, same bytes — comments, order, formatting")
                      .isEqualTo(first);
        }

        assertThat(commentLines(read(output))).isEqualTo(commentLines(first));
        assertThat(sectionHeaders(read(output))).isEqualTo(sectionHeaders(first));
    }

    // ---- B1: a hand-tuned init-owned value is never reverted silently -------------------------

    @Test
    void rerun_batch_refusesToRevertAHandTunedValue_namingOldAndNew(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var edited = handTuned(output);

        assertThat(init(output, "0")).as("batch mode refuses to apply a differing answer without --merge")
                  .isNotEqualTo(0);
        assertThat(read(output)).as("the refused file is untouched, byte for byte").isEqualTo(edited);
        assertThat(stderr()).as("the operator is told WHICH key differs, old → new, and how to proceed")
                  .contains("runtime.default.jvm_args: \"-XX:+UseZGC -XX:+ZGenerational -Xms2g -Xmx8g\""
                            + " → \"-XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx2g\"")
                  .contains("--merge")
                  .contains("--force");
    }

    @Test
    void rerun_batch_withMerge_appliesTheNewAnswer_andRewritesOnlyThatLine(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var edited = handTuned(output);

        assertThat(init(output, "0", "--merge")).as(stderr()).isEqualTo(0);
        var merged = read(output);

        assertThat(merged.lines().count()).as("no line added or removed").isEqualTo(edited.lines().count());
        assertThat(lineDiff(edited, merged)).as("exactly one line differs: the init-owned value, rewritten in place")
                  .containsExactly("< " + TUNED_JVM_ARGS, "> " + GENERATED_JVM_ARGS);
        assertThat(merged).as("the operator's comment beside it survives").contains(TUNED_COMMENT);
        assertThat(stdout()).contains("runtime.default.jvm_args").contains("→");
    }

    @Test
    void rerun_withMerge_appliesAChangeAndAnAdditionTogether_eachAtItsOwnLine(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var edited = handTuned(output);

        assertThat(init(output, "2", "--merge")).as(stderr()).isEqualTo(0);
        var merged = read(output);
        var mergedLines = merged.lines().toList();
        var expected = new ArrayList<>(edited.lines().toList());
        var afterCore = expected.indexOf("[source.primary.core]") + 2;

        expected.set(expected.indexOf(TUNED_JVM_ARGS), GENERATED_JVM_ARGS);
        expected.addAll(afterCore, List.of("", "[source.primary.worker]", "count = 2"));
        assertThat(mergedLines).as("the rewrite lands on the jvm_args line and the insertion after [source.primary.core], "
                                   + "whatever order the edits are applied in")
                  .isEqualTo(expected);
    }

    // ---- new answers: absent init-owned keys are appended in place, nothing else moves ----------

    @Test
    void rerun_newAnswers_appendMissingSectionInPlace_keepingHandAddedSection(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var edited = read(output) + "\n[app-http.api-keys.ops]\nrole = \"admin\"\nkey = \"${env:OPS_KEY}\"\n";

        write(output, edited);
        assertThat(init(output, "2")).as("an addition is a diff too: batch mode refuses it without --merge")
                  .isNotEqualTo(0);
        assertThat(read(output)).as("refused: byte-identical").isEqualTo(edited);
        assertThat(stderr()).contains("+ source.primary.worker.count").contains("--merge");
        assertThat(init(output, "2", "--merge")).as(stderr()).isEqualTo(0);
        var merged = read(output);
        var mergedLines = merged.lines().toList();

        assertThat(mergedLines).as("insert-only: every original line survives, in order")
                  .containsSubsequence(edited.lines().toList());
        assertThat(mergedLines).as("--worker-nodes 2 adds a worker tier the core-only file did not have")
                  .containsSubsequence("[source.primary.core]",
                                       "count = 5",
                                       "[source.primary.worker]",
                                       "count = 2",
                                       "[runtime.default]");
        assertThat(merged).contains("key = \"${env:OPS_KEY}\"");
        assertThat(commentLines(merged)).isEqualTo(commentLines(edited));
        assertThat(stdout()).as("the operator is told which keys were kept, so a key init used to "
                                + "generate and no longer does is visible rather than silently stale")
                  .contains("app-http.api-keys.ops.key")
                  .contains("source.primary.worker.count");
    }

    // ---- B2: an operator-added [[allow_ingress]] rule survives a cloud re-run -------------------

    @Test
    void rerun_cloud_keepsAnOperatorAddedIngressRule_andListsIt(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(initCloud(output)).as(stderr()).isEqualTo(0);
        var generated = read(output);
        var generatedRules = generated.lines().filter(line -> line.equals("[[source.primary.firewall.allow_ingress]]")).count();

        assertThat(generatedRules).as("positive control: the standard preset emits ingress rules").isGreaterThan(0);
        var edited = generated + "\n[[source.primary.firewall.allow_ingress]]\nport = 9100\nprotocol = \"tcp\"\n"
                     + "source_cidr = \"10.0.0.0/8\"\ndescription = \"prometheus node-exporter, added by ops\"\n";

        write(output, edited);
        assertThat(initCloud(output)).as(stderr()).isEqualTo(0);
        var merged = read(output);

        assertThat(merged).as("nothing init owns differs and nothing is missing, so the file is untouched")
                  .isEqualTo(edited);
        assertThat(merged.lines().filter(line -> line.equals("[[source.primary.firewall.allow_ingress]]")).count()).isEqualTo(generatedRules + 1);
        assertThat(stdout()).as("the kept rule is listed").contains("allow_ingress").contains("9100");
    }

    /// B4 (verify-1087 r2): a generated `0.0.0.0/0` rule the operator narrowed to their CIDR is, to
    /// the merge, a missing generated rule. Appending it re-opens the port — so it needs consent
    /// like any other edit, and batch mode refuses naming the rule by CIDR.
    @Test
    void rerun_cloud_refusesToReopenANarrowedIngressRule_withoutMerge_andNamesItByCidr(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(initCloud(output)).as(stderr()).isEqualTo(0);
        var generated = read(output);
        var publicRule = "port = 8070\nprotocol = \"tcp\"\nsource_cidr = \"0.0.0.0/0\"";

        assertThat(generated).as("positive control: the standard preset opens app HTTP to the world").contains(publicRule);
        var narrowed = generated.replace(publicRule, "port = 8070\nprotocol = \"tcp\"\nsource_cidr = \"198.51.100.0/24\"");

        write(output, narrowed);
        assertThat(initCloud(output)).as("same answers, one rule narrowed: refused without --merge").isNotEqualTo(0);
        assertThat(read(output)).as("the narrowed file is untouched").isEqualTo(narrowed);
        assertThat(stderr()).contains("+ source.primary.firewall.allow_ingress[port=8070, protocol=\"tcp\", source_cidr=\"0.0.0.0/0\"]")
                  .contains("--merge");
        assertThat(read(output).lines().filter(line -> line.contains("0.0.0.0/0")).count()).as("no world-open 8070 rule came back")
                  .isEqualTo(generated.lines().filter(line -> line.contains("0.0.0.0/0")).count() - 1);

        assertThat(initCloud(output, "--merge")).as(stderr()).isEqualTo(0);
        assertThat(stdout()).as("with consent the appended rule is reported by CIDR")
                  .contains("added 1 key(s) — source.primary.firewall.allow_ingress[port=8070, protocol=\"tcp\", source_cidr=\"0.0.0.0/0\"]");
        assertThat(read(output)).contains(publicRule).contains("source_cidr = \"198.51.100.0/24\"");
    }

    @Test
    void rerun_interactive_listsAdditions_andEnterLeavesTheFileUntouched(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var before = read(output);

        assertThat(initInteractive(output, "2", "\n")).as(stderr()).isEqualTo(0);
        assertThat(read(output)).as("Enter declines: no worker section appended").isEqualTo(before);
        assertThat(stdout()).contains("+ source.primary.worker.count").contains("[y/N]").contains("left untouched");
    }

    @Test
    void rerun_unchanged_doesNotRewriteTheFile(@TempDir Path tmp) throws IOException {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var stamp = java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L);

        Files.setLastModifiedTime(output, stamp);
        out.reset();
        assertThat(init(output, "0")).isEqualTo(0);
        assertThat(Files.getLastModifiedTime(output)).as("nothing to change, nothing written").isEqualTo(stamp);
        assertThat(stdout()).contains("Unchanged " + output).doesNotContain("Wrote ");
    }

    @Test
    void rerun_crlfFile_getsCrlfInsertedLines(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var crlf = read(output).replace("\n", "\r\n");

        write(output, crlf);
        assertThat(init(output, "2", "--merge")).as(stderr()).isEqualTo(0);
        var merged = read(output);

        assertThat(merged).contains("[source.primary.worker]\r\ncount = 2\r\n");
        assertThat(merged.chars().filter(c -> c == '\n').count()).as("every line ends in CRLF")
                  .isEqualTo(merged.split("\r\n", -1).length - 1);
    }

    // ---- interactive: the prompt lists the diff and defaults to keeping the operator's value ----

    @Test
    void rerun_interactive_defaultKeepsTheHandTunedValue(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var edited = handTuned(output);

        assertThat(initInteractive(output, "\n")).as(stderr()).isEqualTo(0);
        assertThat(read(output)).as("Enter keeps the existing value: the file is untouched").isEqualTo(edited);
        assertThat(stdout()).contains("runtime.default.jvm_args").contains("→").contains("[y/N]");
    }

    @Test
    void rerun_interactive_yesAppliesTheNewAnswer(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var edited = handTuned(output);

        assertThat(initInteractive(output, "y\n")).as(stderr()).isEqualTo(0);
        assertThat(lineDiff(edited, read(output))).containsExactly("< " + TUNED_JVM_ARGS, "> " + GENERATED_JVM_ARGS);
    }

    // ---- --force and refusals -------------------------------------------------------------------

    @Test
    void rerun_withForce_overwrites_droppingHandAddedSection(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        write(output, read(output) + "\n[app-http.api-keys.ops]\nrole = \"admin\"\n");
        assertThat(init(output, "0", "--force")).isEqualTo(0);
        assertThat(read(output)).as("--force keeps its meaning: the file is replaced wholesale")
                  .doesNotContain("api-keys");
    }

    @Test
    void rerun_refusesAnUnparseableExistingFile_ratherThanClobberingIt(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        write(output, "[cluster\nname = broken\n");
        assertThat(init(output, "0")).isNotEqualTo(0);
        assertThat(read(output)).as("the operator's file is untouched").isEqualTo("[cluster\nname = broken\n");
        assertThat(stderr()).contains(output.toString()).contains("--force");
    }

    @Test
    void rerun_refusesADuplicateKey_namingTheLine(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");
        var content = "[cluster]\nname = \"a\"\nname = \"b\"\n";

        write(output, content);
        assertThat(init(output, "0")).isNotEqualTo(0);
        assertThat(read(output)).isEqualTo(content);
        assertThat(stderr()).contains("line 3");
    }

    @Test
    void rerun_refusesAFeatureTheMergeCannotRead_namingTheReason(@TempDir Path tmp) {
        var output = tmp.resolve("cluster-config.toml");

        assertThat(init(output, "0")).isEqualTo(0);
        var edited = read(output) + "\n[ops]\nrotated_at = 2026-09-01\n";

        write(output, edited);
        assertThat(init(output, "0")).isNotEqualTo(0);
        assertThat(read(output)).isEqualTo(edited);
        assertThat(stderr()).as("the reason is the reader's limit, not the file's validity")
                  .contains("dates and times")
                  .contains("--force")
                  .doesNotContain("cannot be parsed");
    }
}
