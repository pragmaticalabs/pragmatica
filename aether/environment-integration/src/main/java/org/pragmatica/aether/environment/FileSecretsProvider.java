// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.nio.file.Path;

import org.pragmatica.lang.Promise;

import static org.pragmatica.lang.io.FileOps.readString;


public record FileSecretsProvider(Path baseDir) implements SecretsProvider {
    private static final Path DEFAULT_BASE_DIR = Path.of("/run/secrets");

    public static FileSecretsProvider fileSecretsProvider() {
        return new FileSecretsProvider(DEFAULT_BASE_DIR);
    }

    public static FileSecretsProvider fileSecretsProvider(Path baseDir) {
        return new FileSecretsProvider(baseDir);
    }

    @Override
    public Promise<String> resolveSecret(String secretPath) {
        return readString(toFilePath(secretPath)).map(String::trim)
                         .mapError(cause -> EnvironmentError.secretResolutionFailed(secretPath,
                                                                                    new RuntimeException(cause.message())))
                         .async();
    }

    private Path toFilePath(String secretPath) {
        return baseDir.resolve(secretPath.replace('/', '_'));
    }
}
