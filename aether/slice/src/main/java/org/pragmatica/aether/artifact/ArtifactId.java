// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.artifact;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Verify.ensure;


@Codec
public record ArtifactId(String id) {
    public static Result<ArtifactId> artifactId(String id) {
        return Result.all(ensure(id, Verify.Is::matches, ARTIFACT_ID_PATTERN)).map(ArtifactId::new);
    }

    @Override
    public String toString() {
        return id;
    }

    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
}
