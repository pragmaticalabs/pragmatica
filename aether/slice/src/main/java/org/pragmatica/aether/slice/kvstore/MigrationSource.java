// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.regex.Pattern;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.aether.slice.kvstore.MigrationSourceError.Absent.ABSENT;


/// Where a migration set came from: the artifact whose jar carries the scripts, or a registered
/// migration set. The raw form stays a plain string in `SchemaVersionValue.artifactCoords`
/// (artifact coordinates, or `set:<id>@<version>`); this type is the only way to read it, so no
/// reader parses the string by hand.
///
/// The set id grammar `^[a-z][a-z0-9-]{0,62}$` is the one `MigrationSetId` (resource-api) uses;
/// `aether/slice` does not depend on that module, so the agreement is pinned by a test, not by a type.
public sealed interface MigrationSource {
    String SET_PREFIX = "set:";
    Pattern SET_ID = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

    /// The scripts come from the jar of `artifact`.
    record FromArtifact(Artifact artifact) implements MigrationSource {
        public static FromArtifact fromArtifact(Artifact artifact) {
            return new FromArtifact(artifact);
        }

        @Override
        public String display() {
            return artifact.asString();
        }
    }

    /// The scripts come from the registered migration set `setId` at `version` (>= 1).
    record FromSet(String setId, int version) implements MigrationSource {
        public static Result<FromSet> fromSet(String setId, int version) {
            return Result.all(Verify.ensure(setId, Verify.Is::matches, SET_ID),
                              Verify.ensure(version, Verify.Is::greaterThanOrEqualTo, 1))
                         .map(FromSet::new);
        }

        @Override
        public String display() {
            return "migration set " + setId + " v" + version;
        }
    }

    /// Human-readable form for a rendering sink; artifact coordinates print byte-identical to the raw string.
    String display();

    /// Reads `raw`: blank is [MigrationSourceError.Absent], anything unreadable is [MigrationSourceError.Malformed].
    static Result<MigrationSource> migrationSource(String raw) {
        return Option.option(raw)
                     .filter(Verify.Is::notBlank)
                     .toResult(ABSENT)
                     .flatMap(MigrationSource::parseNonBlank);
    }

    /// Display of a raw string for a sink that must not fail: the parsed display, `raw` itself when it
    /// does not parse, `""` when it is absent.
    static String display(String raw) {
        return migrationSource(raw).fold(cause -> rawOfMalformed(cause, raw), MigrationSource::display);
    }

    private static String rawOfMalformed(Cause cause, String raw) {
        return cause instanceof MigrationSourceError.Malformed
               ? raw
               : "";
    }

    private static Result<MigrationSource> parseNonBlank(String raw) {
        return parseByForm(raw).mapError(_ -> MigrationSourceError.Malformed.malformed(raw));
    }

    private static Result<MigrationSource> parseByForm(String raw) {
        return raw.startsWith(SET_PREFIX)
               ? parseSet(raw.substring(SET_PREFIX.length()))
               : parseArtifact(raw);
    }

    private static Result<MigrationSource> parseArtifact(String raw) {
        return Artifact.artifact(raw)
                       .map(FromArtifact::fromArtifact)
                       .map(MigrationSource.class::cast);
    }

    private static Result<MigrationSource> parseSet(String body) {
        var at = body.indexOf('@');

        return at < 0
               ? MigrationSourceError.Malformed.malformed(body).result()
               : Number.parseInt(body.substring(at + 1))
                       .flatMap(version -> FromSet.fromSet(body.substring(0, at),
                                                           version))
                       .map(MigrationSource.class::cast);
    }
}
