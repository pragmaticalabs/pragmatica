package org.pragmatica.aether.repository.domain;

import org.pragmatica.lang.Result;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.pragmatica.aether.repository.domain.RepositoryErrors.INVALID_ARTIFACT_ID;
import static org.pragmatica.aether.repository.domain.RepositoryErrors.INVALID_GROUP_ID;

/**
 * Represents an artifact in a repository.
 * An artifact is identified by its name and version.
 */
public interface Artifact {
    Name name();

    SemanticVersion version();

    static Artifact create(Name name, SemanticVersion version) {
        record artifact(Name name, SemanticVersion version) implements Artifact {
        }

        return new artifact(name, version);
    }

    /**
     * Represents the name of an artifact.
     * An artifact name consists of a group ID and an artifact ID.
     */
    interface Name {
        GroupId groupId();

        ArtifactId artifactId();

        static Name create(GroupId groupId, ArtifactId artifactId) {
            record name(GroupId groupId, ArtifactId artifactId) implements Name {
            }

            return new name(groupId, artifactId);
        }

        static Result<Name> create(String groupId, String artifactId) {
            return Result.all(GroupId.create(groupId),
                              ArtifactId.create(artifactId))
                         .map(Name::create);
        }
    }

    interface GroupId {
        String id();

        static Result<GroupId> create(String groupId) {
            return ID_VALIDATOR.test(groupId)
                    ? INVALID_GROUP_ID.result()
                    : Result.ok(() -> groupId);
        }
    }

    interface ArtifactId {
        String id();

        static Result<ArtifactId> create(String artifactId) {
            return ID_VALIDATOR.test(artifactId)
                    ? INVALID_ARTIFACT_ID.result()
                    : Result.ok(() -> artifactId);
        }
    }

    Pattern ID_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9_.-]+$");
    Predicate<String> NOT_NULL = Objects::nonNull;
    Predicate<String> ID_VALIDATOR = NOT_NULL.and(ID_PATTERN.asMatchPredicate());
}
