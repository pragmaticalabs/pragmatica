package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;

import java.nio.file.Files;
import java.nio.file.Path;

/// SecretsProvider that reads secrets from files in a base directory.
/// Path conversion: `database/password` becomes file `{baseDir}/database_password`.
public record FileSecretsProvider( Path baseDir) implements SecretsProvider {
    private static final Path DEFAULT_BASE_DIR = Path.of("/run/secrets");

    public static FileSecretsProvider fileSecretsProvider() {
        return new FileSecretsProvider(DEFAULT_BASE_DIR);
    }

    public static FileSecretsProvider fileSecretsProvider(Path baseDir) {
        return new FileSecretsProvider(baseDir);
    }

    @Override public Promise<String> resolveSecret(String secretPath) {
        return Promise.lift(cause -> EnvironmentError.secretResolutionFailed(secretPath, cause),
                            () -> Files.readString(toFilePath(secretPath)).trim());
    }

    private Path toFilePath(String secretPath) {
        return baseDir.resolve(secretPath.replace('/', '_'));
    }
}
