package org.pragmatica.aether.config.cluster;

/// Change action in a cluster config diff. S9.3
public sealed interface DiffAction {
    String symbol();

    String description();

    record AddSource(String sourceName) implements DiffAction {
        @Override
        public String symbol() { return "+"; }

        @Override
        public String description() { return sourceName + "  source"; }
    }

    record RemoveSource(String sourceName) implements DiffAction {
        @Override
        public String symbol() { return "-"; }

        @Override
        public String description() { return sourceName + "  source (will be drained and destroyed)"; }
    }

    record AddRole(String sourceName, NodeRole role, int count) implements DiffAction {
        @Override
        public String symbol() { return "+"; }

        @Override
        public String description() { return sourceName + "." + role.value() + "  count=" + count; }
    }

    record RemoveRole(String sourceName, NodeRole role, int count) implements DiffAction {
        @Override
        public String symbol() { return "-"; }

        @Override
        public String description() { return sourceName + "." + role.value() + "  (" + count + " nodes will be drained)"; }
    }

    record ScaleUp(String sourceName, NodeRole role, int from, int to) implements DiffAction {
        @Override
        public String symbol() { return "~"; }

        @Override
        public String description() { return sourceName + "." + role.value() + "  count: " + from + " -> " + to; }
    }

    record ScaleDown(String sourceName, NodeRole role, int from, int to) implements DiffAction {
        @Override
        public String symbol() { return "~"; }

        @Override
        public String description() { return sourceName + "." + role.value() + "  count: " + from + " -> " + to; }
    }

    record RuntimeChange(String sourceName, NodeRole role, String fromRuntime, String toRuntime) implements DiffAction {
        @Override
        public String symbol() { return "~"; }

        @Override
        public String description() { return sourceName + "." + role.value() + "  runtime: " + fromRuntime + " -> " + toRuntime; }
    }

    record SourceFieldChange(String sourceName, String field) implements DiffAction {
        @Override
        public String symbol() { return "~"; }

        @Override
        public String description() { return sourceName + "  " + field + " changed"; }
    }

    record ClusterLevelChange(String field, String from, String to) implements DiffAction {
        @Override
        public String symbol() { return "~"; }

        @Override
        public String description() { return "cluster." + field + ": " + from + " -> " + to; }
    }

    record ImmutableFieldChange(String field) implements DiffAction {
        @Override
        public String symbol() { return "!"; }

        @Override
        public String description() { return field + " is immutable and cannot be changed"; }
    }
}
