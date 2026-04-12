package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;

import java.util.List;


/// Role sub-table within a source profile. S5.1.6 REQ-5.1.6.1
public record RoleSubTable(NodeRole role,
                           Option<Integer> count,
                           Option<List<String>> hosts,
                           Option<String> instanceType,
                           String runtimeRef) {
    public static RoleSubTable roleSubTable(NodeRole role,
                                            Option<Integer> count,
                                            Option<List<String>> hosts,
                                            Option<String> instanceType,
                                            String runtimeRef) {
        return new RoleSubTable(role, count, hosts, instanceType, runtimeRef);
    }
}
