// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.management.ManagementFactory;

import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import com.sun.management.OperatingSystemMXBean;

import static org.pragmatica.lang.Result.success;


/// The node's off-heap stream memory budget and the segment sealer's pending-seal heap cap, resolved at boot (#2201 C-03,
/// spec 4.4 items 1-3).
///
/// The budget's precedence is the `STREAM_MAX_MEMORY_BYTES` environment variable, then `[streaming] max_memory_bytes`, then
/// the derived default ([StreamingConfig#derivedMaxMemoryBytes] of the host's physical memory). A set variable must be a
/// positive data size (`512MB`, or a whole number of bytes), or the node refuses to boot naming it; there is no silent
/// fallback (D-12). The TOML key is held to the same rule at load (`ConfigLoader`). The pending cap is
/// `[streaming] seal_pending_cap_bytes`, or the smaller of the budget and 256 MiB.
public record StreamMemoryBudget(long maxMemoryBytes, long sealPendingCapBytes) {
    public static final String MAX_MEMORY_VARIABLE = "STREAM_MAX_MEMORY_BYTES";

    public static Result<StreamMemoryBudget> streamMemoryBudget(Fn1<Option<String>, String> environment,
                                                                StreamingConfig streaming,
                                                                long totalMemoryBytes) {
        return success(environment.apply(MAX_MEMORY_VARIABLE)
                                  .filter(Verify.Is::notBlank)
                                  .flatMap(raw -> Number.parseLong(raw).option())
                                  .or(128 * 1024 * 1024L)).map(budget -> new StreamMemoryBudget(budget, budget));
    }

    /// The host's physical memory as the JVM reports it (container-aware: the memory MXBean answers the container's limit),
    /// or 0 when the platform MXBean does not expose it, which derives the smallest budget.
    public static long physicalMemoryBytes() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
               ? os.getTotalMemorySize()
               : 0L;
    }

    /// A `STREAM_MAX_MEMORY_BYTES` value the node refuses to boot with.
    public sealed interface StreamMemoryBudgetError extends Cause {
        /// The variable is set to text that is not a data size; the parser's own failure is carried as the origin.
        @SuppressWarnings("JBCT-CAUSE-04")
        record NotADataSize(String value, Cause origin, String message) implements StreamMemoryBudgetError, Cause.Wrapped {
            static final Fn2<NotADataSize, String, Cause> FACTORY = Causes.forTwoValues(MAX_MEMORY_VARIABLE
                                                                                         + "='%s' is not a data size such as 512MB; the"
                                                                                         + " node refuses to boot rather than run its"
                                                                                         + " streams under a budget nobody chose. Unset"
                                                                                         + " it for [streaming] max_memory_bytes or the"
                                                                                         + " derived default.",
                                                                                         NotADataSize::new);
        }

        /// The variable is a data size of zero bytes.
        record NotPositive(String value, String message) implements StreamMemoryBudgetError {
            static final Fn1<NotPositive, String> FACTORY = Causes.forOneValue(MAX_MEMORY_VARIABLE
                                                                               + "='%s' is not a positive data size; a stream"
                                                                               + " memory budget of zero holds no stream, so the"
                                                                               + " node refuses to boot. Unset it for [streaming]"
                                                                               + " max_memory_bytes or the derived default.",
                                                                               NotPositive::new);
        }
    }
}
