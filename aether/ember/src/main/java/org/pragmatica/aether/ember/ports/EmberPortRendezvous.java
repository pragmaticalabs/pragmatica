// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The probe-to-bind rendezvous hook (#3021 probe 7, M12): installed with [EmberPortAllocator#afterProbe], it runs after
/// the claim's registry, file locks and bind probe, and before the cluster binds. Each claimant writes
/// `probed-<base>-<pid>-<label>` into `dir` and waits, bounded, until `parties` markers exist. A timeout FAILS as
/// inconclusive (never passes), so an arm that returns success has met a real partner. `dir` must be fresh per run.
public record EmberPortRendezvous(Path dir, int parties, long boundMs, String label) {
    static final String MARKER_PREFIX = "probed-";
    static final long POLL_MS = 20L;

    public static EmberPortRendezvous emberPortRendezvous(Path dir, int parties, long boundMs, String label) {
        return new EmberPortRendezvous(dir, parties, boundMs, label);
    }

    /// The hook for [EmberPortAllocator#afterProbe].
    public Function<PortBlock, Result<Unit>> hook() {
        return this::meet;
    }

    public Result<Unit> meet(PortBlock block) {
        var deadline = System.nanoTime() + boundMs * 1_000_000L;

        return writeMarker(block).flatMap(_ -> awaitParties(deadline, marker(block)));
    }

    private String marker(PortBlock block) {
        return MARKER_PREFIX + block.base() + "-" + ProcessHandle.current().pid() + "-" + label;
    }

    private Result<Unit> writeMarker(PortBlock block) {
        return Result.lift(this::failed,
                           () -> Files.createDirectories(dir))
                     .flatMap(created -> Result.lift(this::failed,
                                                     () -> Files.writeString(created.resolve(marker(block)),
                                                                             label)))
                     .mapToUnit();
    }

    private Result<Unit> awaitParties(long deadline, String marker) {
        return PortWait.poll(deadline,
                             POLL_MS,
                             () -> markerCount().map(this::partiesMet),
                             () -> PortError.RendezvousTimedOut.FACTORY.apply(marker, boundMs));
    }

    private Option<Unit> partiesMet(long count) {
        return count >= parties
               ? some(unit())
               : none();
    }

    private Result<Long> markerCount() {
        return Result.lift(this::failed,
                           () -> Files.list(dir))
                     .map(EmberPortRendezvous::countMarkers);
    }

    private static Long countMarkers(Stream<Path> listed) {
        try (var files = listed) {
            return files.filter(file -> file.getFileName()
                                            .toString()
                                            .startsWith(MARKER_PREFIX))
                        .count();
        }
    }

    private PortError.RendezvousFailed failed(Throwable t) {
        return PortError.RendezvousFailed.FACTORY.apply(dir.toString(), Causes.fromThrowable(t));
    }
}
