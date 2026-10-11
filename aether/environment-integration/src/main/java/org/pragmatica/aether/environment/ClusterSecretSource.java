// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/// The one reader of the ambient cluster secret (#828). Two sources: `AETHER_CLUSTER_SECRET` (the value) and
/// `AETHER_CLUSTER_SECRET_FILE` (a path to a file holding it). The file form exists so launchers never put the value in a process
/// argv or a container's environment (`docker inspect`): the value travels as a file only the node's user can read.
///
/// Rules: the file wins when set; trailing line terminators are stripped; an unreadable or empty file is refused; and setting BOTH
/// variables to DIFFERENT values is refused rather than resolved, since a node cannot know which one the operator meant and a
/// wrong guess derives its certificates from the wrong secret. Causes name the variables and the path, never a secret value.
public interface ClusterSecretSource {
    String SECRET_ENV = "AETHER_CLUSTER_SECRET";
    String SECRET_FILE_ENV = "AETHER_CLUSTER_SECRET_FILE";

    Cause CONFLICT = Causes.cause(SECRET_ENV + " and " + SECRET_FILE_ENV + " are both set and differ; set only " + SECRET_FILE_ENV);

    static Result<Option<String>> resolve(Fn1<String, String> env) {
        return resolve(env, ClusterSecretSource::readFile);
    }

    static Result<Option<String>> resolve(Fn1<String, String> env, Fn1<Result<String>, Path> reader) {
        var plain = nonBlank(env.apply(SECRET_ENV));

        return nonBlank(env.apply(SECRET_FILE_ENV)).fold(() -> Result.success(plain),
                                                          path -> fromFile(path, reader, plain));
    }

    private static Result<Option<String>> fromFile(String path, Fn1<Result<String>, Path> reader, Option<String> plain) {
        return reader.apply(Path.of(path))
                     .mapError(_ -> Causes.cause(SECRET_FILE_ENV + " file '" + path + "' is not readable"))
                     .flatMap(value -> nonEmpty(path, value))
                     .flatMap(value -> reconcile(value, plain));
    }

    private static Result<String> nonEmpty(String path, String value) {
        var stripped = value.stripTrailing();

        return stripped.isEmpty()
               ? Causes.cause(SECRET_FILE_ENV + " file '" + path + "' is empty").result()
               : Result.success(stripped);
    }

    private static Result<Option<String>> reconcile(String fileValue, Option<String> plain) {
        return plain.filter(value -> !value.equals(fileValue))
                    .isPresent()
               ? CONFLICT.result()
               : Result.success(Option.some(fileValue));
    }

    private static Result<String> readFile(Path path) {
        return Result.lift(_ -> Causes.cause(SECRET_FILE_ENV + " file '" + path + "' is not readable"), () -> Files.readString(path));
    }

    private static Option<String> nonBlank(String value) {
        return Option.option(value).filter(v -> !v.isBlank());
    }
}
