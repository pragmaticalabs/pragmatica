// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.pragmatica.lang.Cause;


/// A coordinate the app needs is not usable where the router looks for it (#3021). `reason` is the locate or jar cause, kept
/// so the refusal names the path that was checked. The remedy depends on `module`: a module coordinate is looked up under the
/// staged root ONLY and is cured by packaging that module; any other coordinate is looked up in the Maven local repository and
/// is cured by installing that artifact there, which `mvn package` of this module does not do.
///
/// The check behind this refusal does NOT detect staleness (see [StagedPreCheck]); the text says so, so it is never read as
/// "stale, therefore refused". `stagedSliceArtifacts` is the caller's report of the `Slice-Artifact`s it found; the check does
/// not read it.
public record EmberAppNotPackaged(String coordinate, boolean module, String reason, List<String> stagedSliceArtifacts) implements Cause {
    private static final String STALENESS_NOTE = " (this check refuses a missing, empty, non-regular or unreadable jar, or one"
                                               + " without a readable manifest; it does not check staleness)";

    public static EmberAppNotPackaged emberAppNotPackaged(String coordinate,
                                                          boolean module,
                                                          String reason,
                                                          List<String> stagedSliceArtifacts) {
        return new EmberAppNotPackaged(coordinate, module, reason, List.copyOf(stagedSliceArtifacts));
    }

    @Override
    public String message() {
        return module
               ? moduleMessage()
               : dependencyMessage();
    }

    private String moduleMessage() {
        return "Expected slice " + coordinate
             + " under the staged root: " + reason
             + "; the caller reports Slice-Artifact(s) " + stagedSliceArtifacts
             + "; run `mvn package -DskipTests` for this module" + STALENESS_NOTE;
    }

    private String dependencyMessage() {
        return "Expected dependency " + coordinate
             + " in the Maven local repository: " + reason
             + "; it is not a module of this app, so packaging this module does not provide it:"
             + " install that artifact into the Maven local repository (`mvn install` of its project)" + STALENESS_NOTE;
    }
}
