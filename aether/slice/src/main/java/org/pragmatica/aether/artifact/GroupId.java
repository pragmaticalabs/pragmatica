// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.artifact;

import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import java.util.regex.Pattern;

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
