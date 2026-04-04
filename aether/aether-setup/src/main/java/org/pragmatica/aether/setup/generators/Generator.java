package org.pragmatica.aether.setup.generators;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.lang.Result;

import java.nio.file.Path;


/// Generates deployment artifacts for Aether clusters.
///
///
/// Each generator produces environment-specific artifacts:
///
///   - LocalGenerator - Shell scripts for single-machine deployment
///   - DockerGenerator - docker-compose.yml and supporting scripts
///   - KubernetesGenerator - K8s manifests (YAML or Helm chart)
///
public interface Generator {
    Result<GeneratorOutput> generate(AetherConfig config, Path outputDir);
    boolean supports(AetherConfig config);
}
