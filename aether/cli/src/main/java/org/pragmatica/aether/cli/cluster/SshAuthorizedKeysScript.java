// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;


public sealed interface SshAuthorizedKeysScript {
    record unused() implements SshAuthorizedKeysScript {}

    static String render(List<SshPublicKey> keys) {
        if (keys.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();

        sb.append("#!/bin/bash\n");
        sb.append("set -euo pipefail\n");
        sb.append("# --- Aether bootstrap: install operator SSH keys ---\n");
        sb.append("install -d -m 0700 /root/.ssh\n");
        sb.append("id -u aether >/dev/null 2>&1 || useradd -m -s /bin/bash aether || true\n");
        sb.append("install -d -m 0700 -o aether -g aether /home/aether/.ssh\n");
        sb.append("cat >> /root/.ssh/authorized_keys <<'AETHER_SSH_KEYS'\n");
        for (var key : keys) {
            sb.append(key.value()).append('\n');
        }

        sb.append("AETHER_SSH_KEYS\n");
        sb.append("chmod 0600 /root/.ssh/authorized_keys\n");
        sb.append("cat >> /home/aether/.ssh/authorized_keys <<'AETHER_SSH_KEYS'\n");
        for (var key : keys) {
            sb.append(key.value()).append('\n');
        }

        sb.append("AETHER_SSH_KEYS\n");
        sb.append("chown aether:aether /home/aether/.ssh/authorized_keys\n");
        sb.append("chmod 0600 /home/aether/.ssh/authorized_keys\n");

        return sb.toString();
    }
}
