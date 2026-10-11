// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;


@Codec
public record BlueprintId(Artifact artifact) {
    private static final Fn1<Cause, String> INVALID_FORMAT = Causes.forOneValue("Invalid blueprint ID format: %s");

    public static Result<BlueprintId> blueprintId(String input) {
        return Artifact.artifact(input)
                       .mapError(_ -> INVALID_FORMAT.apply(input))
                       .map(BlueprintId::new);
    }

    public static BlueprintId blueprintId(Artifact artifact) {
        return new BlueprintId(artifact);
    }

    @Override
    public String toString() {
        return asString();
    }

    public String asString() {
        return artifact.asString();
    }

    public ArtifactBase base() {
        return artifact().base();
    }
}
