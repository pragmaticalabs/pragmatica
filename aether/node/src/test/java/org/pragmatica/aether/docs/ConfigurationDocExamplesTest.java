// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.docs;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #864 — every ```java block in `aether/docs/reference/configuration.md` compiles against the sources at this
/// commit. The page's peer examples called `NodeInfo.nodeInfo(NodeId, String, int)`, an overload that does not
/// exist, and nothing failed when it drifted. Each block is wrapped as a method body in a class that declares the
/// free variables the prose leaves implicit (`self`, `peers`, `quicTlsConfig`, ...), so a block can only fail on
/// the API it actually calls.
///
/// The count of blocks examined is asserted, not just printed: a fence parser that stopped matching would
/// otherwise turn this gate green over an empty corpus.
class ConfigurationDocExamplesTest {
    private static final int EXPECTED_JAVA_BLOCKS = 10;
    private static final String PAGE = "aether/docs/reference/configuration.md";
    private static final String HEADER = """
        import java.nio.file.Path;
        import java.util.List;
        import java.util.Map;
        import org.pragmatica.aether.config.AppHttpConfig;
        import org.pragmatica.aether.config.HttpProtocol;
        import org.pragmatica.aether.config.SliceConfig;
        import org.pragmatica.aether.config.StreamingConfig;
        import org.pragmatica.aether.controller.ControllerConfig;
        import org.pragmatica.aether.node.AetherNodeConfig;
        import org.pragmatica.aether.slice.SliceActionConfig;
        import org.pragmatica.consensus.NodeId;
        import org.pragmatica.consensus.net.NodeInfo;
        import org.pragmatica.consensus.rabia.ProtocolConfig;
        import org.pragmatica.dht.DHTConfig;
        import org.pragmatica.lang.Option;
        import org.pragmatica.net.tcp.NodeAddress;
        import org.pragmatica.net.tcp.TlsConfig;

        import static org.pragmatica.aether.slice.repository.maven.LocalRepository.localRepository;
        import static org.pragmatica.lang.io.TimeSpan.timeSpan;

        class DocExample%d {
            NodeId self; NodeId nodeId; List<NodeInfo> coreNodes; List<NodeInfo> peers;
            int managementPort; int port; int coreMax;
            SliceConfig sliceConfig; DHTConfig artifactRepoConfig; AppHttpConfig appHttpConfig;
            TlsConfig quicTlsConfig; Path certPath; Path keyPath; Path frameworkJarsPath;
            double cpuScaleUpThreshold; double cpuScaleDownThreshold; double callRateScaleUpThreshold;
            long evaluationIntervalMs;

            void example() throws Exception {
        """;

    @Test
    void javaBlocks_ofConfigurationPage_compile() throws IOException {
        var blocks = javaBlocks(Files.readString(pageFile()));

        System.out.println("ConfigurationDocExamplesTest examined " + blocks.size() + " java block(s)");
        assertEquals(EXPECTED_JAVA_BLOCKS, blocks.size(), "java block count in " + PAGE + " changed: update the expectation with the page");

        var failures = new ArrayList<String>();

        for (int i = 0; i < blocks.size(); i++) {
            failures.addAll(compile(i, blocks.get(i)));
        }
        assertTrue(failures.isEmpty(), () -> "doc examples do not compile:\n" + String.join("\n", failures));
    }

    private static List<String> compile(int index, String block) {
        var source = HEADER.formatted(index) + block + "\n    }\n}\n";
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var unit = new SimpleJavaFileObject(URI.create("string:///DocExample" + index + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var options = List.of("-proc:none", "-d", System.getProperty("java.io.tmpdir"), "-classpath", classpath());

        compiler.getTask(null, null, diagnostics, options, null, List.of(unit)).call();

        return diagnostics.getDiagnostics()
                          .stream()
                          .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                          .map(d -> "block " + index + ": " + d.getMessage(null).replace("\n", " ") + " [" + firstLine(block) + "]")
                          .collect(Collectors.toList());
    }

    private static String firstLine(String block) {
        return block.lines().findFirst().orElse("");
    }

    private static String classpath() {
        var surefire = System.getProperty("surefire.test.class.path");

        return surefire != null
               ? surefire
               : System.getProperty("java.class.path");
    }

    private static List<String> javaBlocks(String page) {
        var blocks = new ArrayList<String>();
        var current = new StringBuilder();
        var inJava = false;

        for (var line : page.split("\n", -1)) {
            if (!inJava && line.equals("```java")) {
                inJava = true;
                current = new StringBuilder();
            } else if (inJava && line.equals("```")) {
                blocks.add(current.toString());
                inJava = false;
            } else if (inJava) {
                current.append(line).append('\n');
            }
        }
        return blocks;
    }

    private static Path pageFile() {
        try {
            var dir = Path.of(ConfigurationDocExamplesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            while (dir != null && !Files.isRegularFile(dir.resolve(PAGE))) {
                dir = dir.getParent();
            }
            if (dir == null) {
                throw new IllegalStateException(PAGE + " not found above the test classes");
            }
            return dir.resolve(PAGE);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
