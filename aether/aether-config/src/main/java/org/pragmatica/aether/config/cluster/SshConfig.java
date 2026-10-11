// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record SshConfig(String user, String keyPath, int port) {
    public static SshConfig sshConfig(String user, String keyPath, int port) {
        return new SshConfig(user, keyPath, port);
    }

    public static SshConfig sshConfig(String user, String keyPath) {
        return sshConfig(user, keyPath, 22);
    }
}
