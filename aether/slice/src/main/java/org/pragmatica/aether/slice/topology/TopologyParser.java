package org.pragmatica.aether.slice.topology;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Parses topology data from slice .manifest properties files.
///
/// Reads `META-INF/slice/{SliceName}.manifest` from the slice's ClassLoader
/// and extracts route, resource, dependency, and topic information.
@SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
public final class TopologyParser {
    private static final Logger log = LoggerFactory.getLogger(TopologyParser.class);

    private TopologyParser() {}

    /// Parse topology from a loaded slice instance.
    public static Option<SliceTopology> parse(Slice slice, String artifact) {
        var classLoader = slice.getClass()
                               .getClassLoader();
        var interfaces = slice.getClass()
                              .getInterfaces();
        log.debug("TopologyParser: slice class={}, interfaces={}, artifact={}",
                  slice.getClass()
                       .getName(),
                  interfaces.length,
                  artifact);
        for (var iface : interfaces) {
            if (iface == Slice.class) {
                continue;
            }
            var manifestPath = "META-INF/slice/" + iface.getSimpleName() + ".manifest";
            log.debug("TopologyParser: checking manifest path={} via classLoader={}",
                      manifestPath,
                      classLoader.getClass()
                                 .getName());
            var topology = parseFromManifest(classLoader, manifestPath, artifact);
            if (topology.isPresent()) {
                topology.onPresent(t -> log.debug("TopologyParser: parsed topology for {} — routes={}, deps={}, resources={}, pubs={}, subs={}",
                                                  artifact,
                                                  t.routes()
                                                   .size(),
                                                  t.dependencies()
                                                   .size(),
                                                  t.resources()
                                                   .size(),
                                                  t.publishes()
                                                   .size(),
                                                  t.subscribes()
                                                   .size()));
                return topology;
            }
            log.debug("TopologyParser: no manifest found at {}", manifestPath);
        }
        log.warn("TopologyParser: no topology found for artifact={}, slice class={}",
                 artifact,
                 slice.getClass()
                      .getName());
        return Option.none();
    }

    private static Option<SliceTopology> parseFromManifest(ClassLoader classLoader,
                                                           String manifestPath,
                                                           String artifact) {
        try (var is = classLoader.getResourceAsStream(manifestPath)) {
            if (is == null) {
                return Option.none();
            }
            var props = new Properties();
            props.load(is);
            return Option.some(buildTopology(props, artifact));
        } catch (Exception e) {
            log.debug("Could not read topology from manifest {}: {}", manifestPath, e.getMessage());
            return Option.none();
        }
    }

    private static SliceTopology buildTopology(Properties props, String artifact) {
        var sliceName = props.getProperty("slice.name", "");
        return new SliceTopology(sliceName,
                                 artifact,
                                 parseRoutes(props),
                                 parseDependencies(props),
                                 parseResources(props),
                                 parsePublishTopics(props),
                                 parseSubscriptions(props));
    }

    private static List<SliceTopology.Route> parseRoutes(Properties props) {
        var count = intProp(props, "routes.count");
        var routes = new ArrayList<SliceTopology.Route>();
        for (int i = 0; i < count; i++) {
            var prefix = "route." + i + ".";
            var method = props.getProperty(prefix + "method");
            var path = props.getProperty(prefix + "path");
            var handler = props.getProperty(prefix + "handler");
            if (method != null && path != null) {
                routes.add(new SliceTopology.Route(method, path, handler != null
                                                                ? handler
                                                                : ""));
            }
        }
        return routes;
    }

    private static List<SliceTopology.SliceDep> parseDependencies(Properties props) {
        var count = intProp(props, "dependencies.count");
        var deps = new ArrayList<SliceTopology.SliceDep>();
        for (int i = 0; i < count; i++) {
            var prefix = "dependency." + i + ".";
            var iface = props.getProperty(prefix + "interface", "");
            var depArtifact = props.getProperty(prefix + "artifact", "");
            deps.add(new SliceTopology.SliceDep(iface, depArtifact));
        }
        return deps;
    }

    private static List<SliceTopology.ResourceDep> parseResources(Properties props) {
        var count = intProp(props, "resources.count");
        var resources = new ArrayList<SliceTopology.ResourceDep>();
        for (int i = 0; i < count; i++) {
            var prefix = "resource." + i + ".";
            var type = props.getProperty(prefix + "type");
            var config = props.getProperty(prefix + "config");
            if (type != null && config != null) {
                resources.add(new SliceTopology.ResourceDep(type, config));
            }
        }
        return resources;
    }

    private static List<SliceTopology.TopicPub> parsePublishTopics(Properties props) {
        var count = intProp(props, "publish.topics.count");
        var topics = new ArrayList<SliceTopology.TopicPub>();
        for (int i = 0; i < count; i++) {
            var prefix = "publish.topic." + i + ".";
            var config = props.getProperty(prefix + "config", "");
            var messageType = props.getProperty(prefix + "messageType", "");
            topics.add(new SliceTopology.TopicPub(config, messageType));
        }
        return topics;
    }

    private static List<SliceTopology.TopicSub> parseSubscriptions(Properties props) {
        var count = intProp(props, "topic.subscriptions.count");
        var subs = new ArrayList<SliceTopology.TopicSub>();
        for (int i = 0; i < count; i++) {
            var prefix = "topic.subscription." + i + ".";
            var config = props.getProperty(prefix + "config", "");
            var method = props.getProperty(prefix + "method", "");
            var messageType = props.getProperty(prefix + "messageType", "");
            subs.add(new SliceTopology.TopicSub(config, method, messageType));
        }
        return subs;
    }

    private static int intProp(Properties props, String key) {
        try{
            return Integer.parseInt(props.getProperty(key, "0"));
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
