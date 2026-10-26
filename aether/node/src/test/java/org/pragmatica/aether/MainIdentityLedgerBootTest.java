// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.node.ClusterTestPorts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2429: `Main` refuses the boot when the artifacts `disk_path` the operator wrote cannot hold the identity ledger. The decision is
/// pinned in-process by `IdentityLedgerWiringTest`; this class pins that `Main` actually runs it, as real `Main` in a child JVM (the
/// refusal is a process exit). The path is written under a regular FILE, so the ledger directory cannot be created anywhere; the
/// stream WAL gate before it is made to pass (non-durable streams allowed), so the only gate that can stop the child with exit 1 and
/// this message is the ledger gate. A `Main` that does not run it boots the node and the child outlives the deadline.
class MainIdentityLedgerBootTest {
    private static final int REFUSED_EXIT = 1;
    private static final long DEADLINE_SECONDS = 90;

    private record Run(int exit, String out, String err) {}

    @Test
    void anExplicitDiskPathThatCannotHoldTheLedger_refusesTheBoot_namingTheLedger(@TempDir Path dir) throws Exception {
        var blocker = Files.writeString(dir.resolve("blocker"), "x");
        var toml = "[cluster]\nenvironment = \"docker\"\n\n[storage.artifacts]\ndisk_path = \"" + blocker.resolve("artifacts") + "\"\n";
        var run = boot(dir, toml);

        assertThat(run.exit()).as("exit code; stderr:\n%s\nstdout:\n%s", run.err(), run.out()).isEqualTo(REFUSED_EXIT);
        assertThat(run.err()).as("the operator-visible signal names the ledger and the path").contains("FATAL").contains("identity ledger").contains("blocker");
    }

    private static Run boot(Path dir, String nodeToml) throws Exception {
        var file = Files.writeString(dir.resolve("node.toml"), nodeToml);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<>(List.of(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"), Main.class.getName(),
                                              "--config=" + file, "--node-id=node-1", "--port=" + ClusterTestPorts.freeTcpAndUdpPort(),
                                              "--management-port=" + ClusterTestPorts.freeTcpAndUdpPort()));
        var out = dir.resolve("out.txt");
        var err = dir.resolve("err.txt");
        var builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());

        builder.environment().keySet().removeIf(name -> name.startsWith("AETHER_") || name.startsWith("CLUSTER_"));
        builder.environment().put("AETHER_CLUSTER_NAME", "test-cluster");
        builder.environment().put("AETHER_CLUSTER_SECRET", "test-cluster-secret-for-2429");
        builder.environment().put("AETHER_ALLOW_NON_DURABLE_STREAMS", "true");
        var child = builder.start();

        if (!child.waitFor(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            return new Run(-1, Files.readString(out), Files.readString(err) + "\n[TIMED OUT: the child kept running]");
        }

        return new Run(child.exitValue(), Files.readString(out), Files.readString(err));
    }
}
