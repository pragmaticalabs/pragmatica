package org.pragmatica.aether.setup.generators;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.lang.Result;

import java.nio.file.Path;

/**
 * Generates deployment artifacts for Aether clusters.
 *
 * <p>Each generator produces environment-specific artifacts:
 * <ul>
 *   <li>LocalGenerator - Shell scripts for single-machine deployment</li>
 *   <li>DockerGenerator - docker-compose.yml and supporting scripts</li>
 *   <li>KubernetesGenerator - K8s manifests (YAML or Helm chart)</li>
 * </ul>
 */
public interface Generator {
    /**
     * Generate deployment artifacts.
     *
     * @param config    Validated configuration
     * @param outputDir Output directory for generated files
     * @return Result indicating success or failure
     */
    Result<GeneratorOutput> generate(AetherConfig config, Path outputDir);

    /**
     * Check if this generator supports the given configuration.
     */
    boolean supports(AetherConfig config);
}
