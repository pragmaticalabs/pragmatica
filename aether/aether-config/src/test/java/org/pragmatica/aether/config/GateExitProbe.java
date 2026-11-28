// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.config.JvmVulnerabilityGate.Sources;

/// The child process of `JvmVulnerabilityGateTest.TheExitInAForkedJvm`: it runs the production enforcement wiring over fake sources
/// (mode `enforce`, so the verdict never depends on the host's CPU) or reads the live intrinsic flag (mode `intrinsic`).
///
/// `enforce <runtime> <os.arch> <cpuinfo file> <vm option value | throw> <version seam | ->`: prints [#SURVIVED] when `enforce` returned.
public final class GateExitProbe {
    static final String SURVIVED = "GATE-PROBE-SURVIVED";

    private GateExitProbe() {}

    public static void main(String[] args) {
        if ("intrinsic".equals(args[0])) {
            System.out.println("intrinsicDisabled=" + JvmVulnerabilityGate.intrinsicDisabled(JvmVulnerabilityGate.liveSources().vmOption()));

            return;
        }

        JvmVulnerabilityGate.enforce(fakeSources(args));
        System.out.println(SURVIVED);
    }

    private static Sources fakeSources(String[] args) {
        var properties = args[5].equals("-")
                         ? Map.of("os.arch", args[2])
                         : Map.of("os.arch", args[2], JvmVulnerabilityGate.RUNTIME_VERSION_PROPERTY, args[5]);
        UnaryOperator<String> vmOption = name -> vmOptionValue(args[4]);

        return Sources.sources(properties::get, () -> Runtime.Version.parse(args[1]), Path.of(args[3]), vmOption);
    }

    private static String vmOptionValue(String configured) {
        if ("throw".equals(configured)) {
            throw new IllegalStateException("not a HotSpot VM");
        }

        return configured;
    }
}
