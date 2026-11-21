// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.pragmatica.lang.Cause;


/// A coordinate the staged root should serve is not there (#3021): the module was not packaged since its last change.
/// Names the expected coordinate and the `Slice-Artifact`s the staged jars do declare, so the mismatch is readable.
public record EmberAppNotPackaged(String coordinate, List<String> stagedSliceArtifacts) implements Cause {
    public static EmberAppNotPackaged emberAppNotPackaged(String coordinate, List<String> stagedSliceArtifacts) {
        return new EmberAppNotPackaged(coordinate, List.copyOf(stagedSliceArtifacts));
    }

    @Override
    public String message() {
        return "Expected slice " + coordinate
             + " under the staged root, found Slice-Artifact(s) " + stagedSliceArtifacts
             + "; run `mvn package -DskipTests` for this module";
    }
}
