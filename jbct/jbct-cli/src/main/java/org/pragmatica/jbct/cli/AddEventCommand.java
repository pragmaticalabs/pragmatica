package org.pragmatica.jbct.cli;

import org.pragmatica.jbct.init.EventAdder;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Add pub-sub event annotations to an existing Aether slice project.
@Command(
 name = "add-event",
 description = "Add pub-sub event annotations (Publisher + Subscription) to an existing Aether slice project",
 mixinStandardHelpOptions = true)
public class AddEventCommand implements Callable<Integer> {
    @Parameters(
    paramLabel = "<event-name>",
    description = "Event name in kebab-case (e.g., expensive-order)")
    String eventName;

    @Option(
    names = {"--package", "-p"},
    description = "Package for annotations. Starts with '.' = relative to basePackage. Default: .shared.resource")
    String packageOverride;

    @Option(
    names = {"--dir", "-d"},
    description = "Project directory (default: current directory)")
    Path projectDir;

    @Option(
    names = {"--config-key"},
    description = "Override messaging config key (default: messaging.<event-name>)")
    String configKey;

    @Override
    public Integer call() {
        var dir = org.pragmatica.lang.Option.option(projectDir)
                     .map(Path::toAbsolutePath)
                     .or(() -> Path.of(System.getProperty("user.dir")));
        return EventAdder.eventAdder(dir, eventName, packageOverride, configKey)
                         .flatMap(adder -> adder.addEvent()
                                                .map(files -> new EventResult(adder, files)))
                         .onFailure(cause -> System.err.println("Error: " + cause.message()))
                         .onSuccess(result -> printResult(dir, result))
                         .map(_ -> 0)
                         .or(1);
    }

    private record EventResult(EventAdder adder, List<Path> files) {}

    private void printResult(Path dir, EventResult result) {
        System.out.println("Added event: " + eventName);
        System.out.println();
        System.out.println("Created files:");
        for (var file : result.files()) {
            System.out.println("  " + dir.relativize(file));
        }
        System.out.println();
        System.out.println("Config key: " + result.adder().configKey());
        if (result.adder().hasAetherToml()) {
            System.out.println("Updated:    aether.toml (added [" + result.adder().configKey() + "] section)");
        }
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  Publisher:  @" + result.adder().eventCamelCase() + "Publisher Publisher<YourEvent> publisher");
        System.out.println("  Subscriber: @" + result.adder().eventCamelCase() + "Subscription on handler method returning Promise<Unit>");
    }
}
