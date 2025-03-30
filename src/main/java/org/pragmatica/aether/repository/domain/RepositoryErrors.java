package org.pragmatica.aether.repository.domain;

import org.pragmatica.lang.Cause;

public interface RepositoryErrors extends Cause {
    RepositoryErrors INVALID_VERSION = () -> "Invalid version format";
    RepositoryErrors INVALID_ARTIFACT_ID = () -> "Invalid artifact ID format";
    RepositoryErrors INVALID_GROUP_ID = () -> "Invalid group ID format";
}
