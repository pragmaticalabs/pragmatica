// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record TlsConfig(boolean autoGenerate, String certPath, String keyPath, String caPath, String clusterSecret) {
    public static Result<TlsConfig> tlsConfig(boolean autoGenerate,
                                              String certPath,
                                              String keyPath,
                                              String caPath,
                                              String clusterSecret) {
        return success(new TlsConfig(autoGenerate, certPath, keyPath, caPath, clusterSecret));
    }

    public static Result<TlsConfig> tlsConfig(boolean autoGenerate, String certPath, String keyPath, String caPath) {
        return tlsConfig(autoGenerate, certPath, keyPath, caPath, "");
    }

    public static TlsConfig tlsConfig() {
        return tlsConfig(true, "", "", "").unwrap();
    }

    public static TlsConfig tlsConfig(String certPath, String keyPath, String caPath) {
        return tlsConfig(false, certPath, keyPath, caPath).unwrap();
    }

    public static TlsConfig tlsConfig(String clusterSecret) {
        return tlsConfig(true, "", "", "", clusterSecret).unwrap();
    }

    public boolean hasClusterSecret() {
        return ! clusterSecret.isBlank();
    }

    public boolean hasProvidedCertificates() {
        return ! autoGenerate
               && !certPath.isBlank()
               && !keyPath.isBlank();
    }

    public Option<Path> certFile() {
        return certPath.isBlank()
               ? none()
               : option(Path.of(certPath));
    }

    public Option<Path> keyFile() {
        return keyPath.isBlank()
               ? none()
               : option(Path.of(keyPath));
    }

    public Option<Path> caFile() {
        return caPath.isBlank()
               ? none()
               : option(Path.of(caPath));
    }
}
