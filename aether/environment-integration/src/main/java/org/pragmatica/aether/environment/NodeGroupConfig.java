package org.pragmatica.aether.environment;

import java.util.Map;


/// Configuration for provisioning a group of nodes. Section 11.1
public record NodeGroupConfig(String sourceName,
                              String role,
                              int count,
                              String instanceType,
                              String zone,
                              Map<String, String> tags) {
    public static NodeGroupConfig nodeGroupConfig(String sourceName,
                                                  String role,
                                                  int count,
                                                  String instanceType,
                                                  String zone,
                                                  Map<String, String> tags) {
        return new NodeGroupConfig(sourceName, role, count, instanceType, zone, Map.copyOf(tags));
    }
}
