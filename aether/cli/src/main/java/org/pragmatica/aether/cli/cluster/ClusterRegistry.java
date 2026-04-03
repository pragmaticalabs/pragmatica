package org.pragmatica.aether.cli.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Manages the cluster registry at `~/.aether/clusters.toml`.
///
/// Stores registered cluster endpoints and tracks the active context.
/// The registry file uses TOML format with `[current]` section for active context
/// and `[clusters.<name>]` sections for each registered cluster.
public record ClusterRegistry(Path registryPath, Option<String> currentContext, List<ClusterEntry> entries) {
    private static final String CLUSTERS_PREFIX = "clusters.";

    private static final Path DEFAULT_REGISTRY_PATH = Path.of(System.getProperty("user.home"),
                                                              ".aether",
                                                              "clusters.toml");

    public record ClusterEntry(String name, String endpoint, Option<String> apiKeyEnv){}

    public sealed interface RegistryError extends Cause {
        enum General implements RegistryError {
            CLUSTER_NOT_FOUND("Cluster not found in registry"),
            CANNOT_REMOVE_ONLY_CLUSTER("Cannot remove the only registered cluster");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        record WriteError(String detail) implements RegistryError {
            @Override public String message() {
                return "Failed to write registry: " + detail;
            }
        }

        record ReadError(String detail) implements RegistryError {
            @Override public String message() {
                return "Failed to read registry: " + detail;
            }
        }
    }

    @SuppressWarnings("JBCT-VO-02") static ClusterRegistry clusterRegistry(Path path,
                                                                           Option<String> context,
                                                                           List<ClusterEntry> entries) {
        return new ClusterRegistry(path, context, entries);
    }

    public static Result<ClusterRegistry> load() {
        return load(DEFAULT_REGISTRY_PATH);
    }

    public static Result<ClusterRegistry> load(Path path) {
        return Files.exists(path)
              ? loadExisting(path)
              : success(clusterRegistry(path, none(), List.of()));
    }

    public Option<ClusterEntry> current() {
        return currentContext.flatMap(this::findEntry);
    }

    public Result<ClusterRegistry> use(String name) {
        return findEntry(name).toResult(RegistryError.General.CLUSTER_NOT_FOUND)
                        .map(entry -> clusterRegistry(registryPath,
                                                      option(name),
                                                      entries));
    }

    public ClusterRegistry add(String name, String endpoint, Option<String> apiKeyEnv) {
        var filtered = removeByName(name);
        var updated = new ArrayList<>(filtered);
        updated.add(new ClusterEntry(name, endpoint, apiKeyEnv));
        var context = currentContext.or(name);
        return clusterRegistry(registryPath, option(context), List.copyOf(updated));
    }

    public Result<ClusterRegistry> remove(String name) {
        return findEntry(name).toResult(RegistryError.General.CLUSTER_NOT_FOUND)
                        .map(entry -> buildRegistryAfterRemoval(name));
    }

    public Result<Unit> save() {
        return Result.lift(e -> new RegistryError.WriteError(e.getMessage()),
                           this::writeToFile);
    }

    private Option<ClusterEntry> findEntry(String name) {
        return entries.stream().filter(e -> e.name().equals(name))
                             .findFirst()
                             .map(Option::some)
                             .orElse(none());
    }

    private List<ClusterEntry> removeByName(String name) {
        return entries.stream().filter(e -> !e.name().equals(name))
                             .toList();
    }

    private ClusterRegistry buildRegistryAfterRemoval(String name) {
        var filtered = removeByName(name);
        var updatedContext = currentContext.filter(c -> !c.equals(name));
        return clusterRegistry(registryPath, updatedContext, List.copyOf(filtered));
    }

    private static Result<ClusterRegistry> loadExisting(Path path) {
        return TomlParser.parseFile(path).mapError(c -> new RegistryError.ReadError(c.message()))
                                   .map(doc -> fromDocument(path, doc));
    }

    private static ClusterRegistry fromDocument(Path path, TomlDocument doc) {
        var context = doc.getString("current", "context");
        var entries = extractEntries(doc);
        return clusterRegistry(path, context, entries);
    }

    @SuppressWarnings("JBCT-PAT-01") private static List<ClusterEntry> extractEntries(TomlDocument doc) {
        var result = new ArrayList<ClusterEntry>();
        for (var section : doc.sectionNames()) {if (section.startsWith(CLUSTERS_PREFIX)) {
            var name = section.substring(CLUSTERS_PREFIX.length());
            var endpoint = doc.getString(section, "endpoint").or("");
            var apiKeyEnv = doc.getString(section, "api_key_env");
            result.add(new ClusterEntry(name, endpoint, apiKeyEnv));
        }}
        return List.copyOf(result);
    }

    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-EX-01"}) @Contract private void writeToFile() throws IOException {
        var content = buildFileContent();
        Files.createDirectories(registryPath.getParent());
        Files.writeString(registryPath, content);
    }

    private String buildFileContent() {
        var sb = new StringBuilder();
        appendCurrentSection(sb);
        appendClusterSections(sb);
        return sb.toString();
    }

    @Contract private void appendCurrentSection(StringBuilder sb) {
        currentContext.onPresent(ctx -> sb.append("[current]\ncontext = \"").append(ctx)
                                                 .append("\"\n\n"));
    }

    @Contract@SuppressWarnings("JBCT-PAT-01") private void appendClusterSections(StringBuilder sb) {
        for (var entry : entries) {
            sb.append("[clusters.").append(entry.name())
                     .append("]\n");
            sb.append("endpoint = \"").append(entry.endpoint())
                     .append("\"\n");
            entry.apiKeyEnv().onPresent(key -> sb.append("api_key_env = \"").append(key)
                                                        .append("\"\n"));
            sb.append('\n');
        }
    }
}
