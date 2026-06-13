// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topology;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.blueprint.BlueprintNamespace;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
public final class TopologyParser {
    private static final Logger log = LoggerFactory.getLogger(TopologyParser.class);

    private TopologyParser() {}

    public static Option<SliceTopology> parse(Slice slice, String artifact) {
        var classLoader = slice.getClass().getClassLoader();
        var interfaces = slice.getClass().getInterfaces();

        log.debug("TopologyParser: slice class={}, interfaces={}, artifact={}",
                  slice.getClass().getName(),
                  interfaces.length,
                  artifact);
        for (var iface : interfaces) {
            if (iface == Slice.class) {
                continue;
            }

            var manifestPath = "META-INF/slice/" + iface.getSimpleName() + ".manifest";

            log.debug("TopologyParser: checking manifest path={} via classLoader={}",
                      manifestPath,
                      classLoader.getClass().getName());
            var topology = parseFromManifest(classLoader, manifestPath, artifact);

            if (topology.isPresent()) {
                topology.onPresent(t -> log.debug("TopologyParser: parsed topology for {} — routes={}, deps={}, resources={}, pubs={}, subs={}",
                                                  artifact,
                                                  t.routes().size(),
                                                  t.dependencies().size(),
                                                  t.resources().size(),
                                                  t.publishes().size(),
                                                  t.subscribes().size()));

                return topology;
            }

            log.debug("TopologyParser: no manifest found at {}", manifestPath);
        }

        log.warn("TopologyParser: no topology found for artifact={}, slice class={}",
                 artifact,
                 slice.getClass().getName());

        return Option.none();
    }

    public static Result<List<SliceTopology>> parseFromJar(URL jarUrl, String artifact) {
        return Result.lift(Causes::fromThrowable, () -> readTopologiesFromJar(jarUrl, artifact));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static List<SliceTopology> readTopologiesFromJar(URL jarUrl, String artifact) throws Exception {
        var path = jarUrl.getPath();

        if (path.startsWith("file:")) {
            path = path.substring(5);
        }

        if (path.contains("!")) {
            path = path.substring(0, path.indexOf("!"));
        }

        var topologies = new ArrayList<SliceTopology>();

        try (var jarFile = new JarFile(path)) {
            var entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();

                if (entry.getName().startsWith("META-INF/slice/") && entry.getName().endsWith(".manifest")) {
                    var props = new Properties();

                    try (var is = jarFile.getInputStream(entry)) {
                        props.load(is);
                    }

                    topologies.add(buildTopology(props, artifact));
                }
            }
        }

        return topologies;
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
                                 parsePublishTopics(props, artifact),
                                 parseSubscriptions(props, artifact));
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
                routes.add(new SliceTopology.Route(method,
                                                   path,
                                                   handler != null
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

    private static List<SliceTopology.TopicPub> parsePublishTopics(Properties props, String artifact) {
        var count = intProp(props, "publish.topics.count");
        var topics = new ArrayList<SliceTopology.TopicPub>();

        for (int i = 0; i < count; i++) {
            var prefix = "publish.topic." + i + ".";
            var config = props.getProperty(prefix + "config", "");
            var messageType = props.getProperty(prefix + "messageType", "");

            topics.add(new SliceTopology.TopicPub(config, resolveTopicAddress(config, artifact), messageType));
        }

        return topics;
    }

    private static List<SliceTopology.TopicSub> parseSubscriptions(Properties props, String artifact) {
        var count = intProp(props, "reactive.count");
        var subs = new ArrayList<SliceTopology.TopicSub>();

        for (int i = 0; i < count; i++) {
            var prefix = "reactive." + i + ".";
            var category = props.getProperty(prefix + "category", "");

            if ("subscription".equals(category)) {
                var config = props.getProperty(prefix + "config", "");
                var method = props.getProperty(prefix + "method", "");
                var messageType = props.getProperty(prefix + "messageType", "");

                subs.add(new SliceTopology.TopicSub(config, resolveTopicAddress(config, artifact), method, messageType));
            }
        }

        return subs;
    }

    /// Resolve a declared topic config string to its canonical `namespace:topic:version` form for
    /// topology display and cross-slice matching.
    ///
    /// Mirrors the deploy-path rule in `NodeDeploymentState.resolveTopicAddress`:
    ///  - an already-namespaced declaration (`namespace:topic:version`) is kept verbatim;
    ///  - a bare/legacy name derives its namespace from the slice's blueprint Maven coordinates
    ///    via [BlueprintNamespace] and defaults the version to [ResourceVersion#defaultVersion].
    ///
    /// Resolution is best-effort: if the artifact coordinates or the declared name fail to parse
    /// (e.g. malformed manifest), the raw `config` string is returned unchanged so the topology
    /// stays renderable rather than dropping the node.
    private static String resolveTopicAddress(String config, String artifact) {
        if (config == null || config.isBlank()) {
            return config;
        }

        if (config.contains(":")) {
            return ResourceAddress.resourceAddress(config)
                                  .map(ResourceAddress::asString)
                                  .or(config);
        }

        return Artifact.artifact(artifact)
                       .flatMap(BlueprintNamespace::deriveNamespace)
                       .flatMap(namespace -> ResourceAddress.resourceAddress(namespace,
                                                                             config,
                                                                             ResourceVersion.defaultVersion()))
                       .map(ResourceAddress::asString)
                       .or(config);
    }

    private static int intProp(Properties props, String key) {
        try {
            return Integer.parseInt(props.getProperty(key, "0"));
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
