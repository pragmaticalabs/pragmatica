// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.lang.Cause;


/// Why an ownership or lineage epoch was not minted (#2654).
public sealed interface EpochMintError extends Cause {
    enum General implements EpochMintError {
        /// The life's cluster incarnation is not committed yet (the mirror reads `ClusterIncarnation.NONE`): at genesis
        /// before the registrar commits incarnation 1, after a whole-cluster restore before the restore commits the new
        /// one. An epoch minted then ranks below every epoch the restored life committed. The mint succeeds
        /// once the commit is observed.
        INCARNATION_NOT_COMMITTED("No epoch is minted before the life's cluster incarnation commits");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
