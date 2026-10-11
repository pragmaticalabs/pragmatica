// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.artifact;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;


@Codec
public record GroupId(String id) {
    public static Result<GroupId> groupId(String id) {
        return Result.all(ensure(id, Is::matches, GROUP_ID_PATTERN)).map(GroupId::new);
    }

    @Override
    public String toString() {
        return id;
    }

    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+$");
}
