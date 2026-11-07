// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.Namespace;
import org.pragmatica.storage.Sha256;


/// The content-store namespace a slice's stored objects live in (U2, spec §B2.1 [G: the derivation]): its
/// `groupId.artifactId`, lower-cased, with every character outside `[a-z0-9._-]` replaced by `-`. A name that is not a
/// valid [Namespace] as it stands (longer than 63 characters) keeps its first 46 characters, then `-` and the first 16 hex
/// digits of the SHA-256 of the whole `groupId:artifactId`, so two slices whose long names share a prefix still differ.
///
/// The version is not part of it: every version of a slice reads and writes one namespace. The ingress that stores an
/// upload and the slice-side `ContentStore` binding both derive the namespace here, so they agree by construction.
public sealed interface SliceNamespace {
    Pattern OUTSIDE_ALPHABET = Pattern.compile("[^a-z0-9._-]");
    int PREFIX_LENGTH = 46;
    int DIGEST_HEX_LENGTH = 16;

    /// The namespace of the slice `base`.
    static Result<Namespace> sliceNamespace(ArtifactBase base) {
        var plain = plainName(base);

        return Namespace.namespace(plain).orElse(() -> hashedNamespace(plain, base));
    }

    private static String plainName(ArtifactBase base) {
        var joined = (base.groupId().id() + "." + base.artifactId().id()).toLowerCase(Locale.ROOT);

        return OUTSIDE_ALPHABET.matcher(joined).replaceAll("-");
    }

    private static Result<Namespace> hashedNamespace(String plain, ArtifactBase base) {
        return Sha256.sha256(base.asString().getBytes(StandardCharsets.UTF_8))
                     .map(digest -> plain.substring(0,
                                                    Math.min(PREFIX_LENGTH,
                                                             plain.length()))
                                   + "-" + digest.hexString()
                                                 .substring(0, DIGEST_HEX_LENGTH))
                     .flatMap(Namespace::namespace);
    }

    record unused() implements SliceNamespace {}
}
