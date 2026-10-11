// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// The one reader of the ambient credentials a node is launched with (#828): the cluster secret and the API keys, and any other
/// `${env:NAME}` reference. Two sources per credential: `NAME` (the value) and `NAME_FILE` (a path to a file holding it). The file form exists so launchers never put the value in a process
/// argv or a container's environment (`docker inspect`): the value travels as a file only the node's user can read.
///
/// Rules: the file wins when set; trailing line terminators are stripped; an unreadable or empty file is refused; and setting BOTH
/// variables to DIFFERENT values is refused rather than resolved, since a node cannot know which one the operator meant and a
/// wrong guess derives its certificates from the wrong secret. Causes name the variables and the path, never a secret value.
public interface ClusterSecretSource {
    String SECRET_ENV = "AETHER_CLUSTER_SECRET";
    String SECRET_FILE_ENV = "AETHER_CLUSTER_SECRET_FILE";
    String FILE_SUFFIX = "_FILE";
    String AETHER_API_KEY_ENV = "AETHER_API_KEY";
    /// The credential-class identity variables besides the cluster secret: the node's accepted keys (plural, read by the config
    /// loader) and the client credential it sends (singular, referenced as `${env:AETHER_API_KEY}` in the node config).
    List<String> CREDENTIAL_ENVS = List.of("AETHER_API_KEY", "AETHER_API_KEYS");
    /// Every variable a launcher delivers by file.
    List<String> FILE_DELIVERED_ENVS = List.of(SECRET_ENV, "AETHER_API_KEY", "AETHER_API_KEYS");

    static String fileVar(String plainVar) {
        return plainVar + FILE_SUFFIX;
    }

    /// `AETHER_CLUSTER_SECRET` -> `cluster-secret`; the stem of every path a credential's file gets.
    static String slug(String plainVar) {
        return plainVar.substring("AETHER_".length())
                       .toLowerCase()
                       .replace('_', '-');
    }

    /// Where a container node's credential file lives on its host (bind-mount source), and where it appears in the container.
    static String hostFile(String plainVar) {
        return "/opt/aether/config/" + slug(plainVar);
    }

    static String containerMount(String plainVar) {
        return "/run/secrets/aether-" + slug(plainVar);
    }

    /// Where a JVM node's credential file lives (the unit's env file names it).
    static String jvmFile(String plainVar) {
        return "/etc/aether/" + slug(plainVar);
    }

    static Cause conflict(String plainVar) {
        return Causes.cause(plainVar
                           + " and " + fileVar(plainVar)
                           + " are both set and differ; set only " + fileVar(plainVar));
    }

    static Result<Option<String>> resolve(Fn1<String, String> env) {
        return resolve(env, SECRET_ENV);
    }

    static Result<Option<String>> resolve(Fn1<String, String> env, String plainVar) {
        return resolve(env, plainVar, ClusterSecretSource::readFile);
    }

    static Result<Option<String>> resolve(Fn1<String, String> env, Fn1<Result<String>, Path> reader) {
        return resolve(env, SECRET_ENV, reader);
    }

    static Result<Option<String>> resolve(Fn1<String, String> env, String plainVar, Fn1<Result<String>, Path> reader) {
        var plain = nonBlank(env.apply(plainVar));

        return nonBlank(env.apply(fileVar(plainVar))).fold(() -> Result.success(plain),
                                                           path -> fromFile(plainVar, path, reader, plain));
    }

    /// Start-up gate: every file-delivered variable resolves (neither conflicting, unreadable nor empty).
    static Result<Unit> validate(Fn1<String, String> env) {
        return FILE_DELIVERED_ENVS.stream()
                                  .map(name -> resolve(env, name))
                                  .filter(Result::isFailure)
                                  .findFirst()
                                  .map(failure -> failure.map(_ -> Unit.unit()))
                                  .orElse(Result.unitResult());
    }

    private static Result<Option<String>> fromFile(String plainVar,
                                                   String path,
                                                   Fn1<Result<String>, Path> reader,
                                                   Option<String> plain) {
        return reader.apply(Path.of(path))
                     .mapError(_ -> Causes.cause(fileVar(plainVar) + " file '" + path + "' is not readable"))
                     .flatMap(value -> nonEmpty(plainVar, path, value))
                     .flatMap(value -> reconcile(plainVar, value, plain));
    }

    private static Result<String> nonEmpty(String plainVar, String path, String value) {
        var stripped = value.stripTrailing();

        return stripped.isEmpty()
               ? Causes.cause(fileVar(plainVar) + " file '" + path + "' is empty").result()
               : Result.success(stripped);
    }

    private static Result<Option<String>> reconcile(String plainVar, String fileValue, Option<String> plain) {
        return plain.filter(value -> !value.equals(fileValue))
                    .isPresent()
               ? conflict(plainVar).result()
               : Result.success(Option.some(fileValue));
    }

    private static Result<String> readFile(Path path) {
        return Result.lift(_ -> Causes.cause("file '" + path + "' is not readable"),
                           () -> Files.readString(path));
    }

    private static Option<String> nonBlank(String value) {
        return Option.option(value).filter(v -> !v.isBlank());
    }
}
