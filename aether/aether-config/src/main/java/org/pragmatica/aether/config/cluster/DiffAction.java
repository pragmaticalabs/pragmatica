// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.aether.environment.SourceName;


public sealed interface DiffAction {
    String symbol();
    String description();

    record AddSource(SourceName sourceName) implements DiffAction {
        @Override
        public String symbol() {
            return "+";
        }

        @Override
        public String description() {
            return sourceName + "  source";
        }
    }

    record RemoveSource(SourceName sourceName) implements DiffAction {
        @Override
        public String symbol() {
            return "-";
        }

        @Override
        public String description() {
            return sourceName + "  source (will be drained and destroyed)";
        }
    }

    record AddRole(SourceName sourceName, NodeRole role, int count) implements DiffAction {
        @Override
        public String symbol() {
            return "+";
        }

        @Override
        public String description() {
            return sourceName + "." + role.value() + "  count=" + count;
        }
    }

    record RemoveRole(SourceName sourceName, NodeRole role, int count) implements DiffAction {
        @Override
        public String symbol() {
            return "-";
        }

        @Override
        public String description() {
            return sourceName + "." + role.value() + "  (" + count + " nodes will be drained)";
        }
    }

    record ScaleUp(SourceName sourceName, NodeRole role, int from, int to) implements DiffAction {
        @Override
        public String symbol() {
            return "~";
        }

        @Override
        public String description() {
            return sourceName + "." + role.value() + "  count: " + from + " -> " + to;
        }
    }

    record ScaleDown(SourceName sourceName, NodeRole role, int from, int to) implements DiffAction {
        @Override
        public String symbol() {
            return "~";
        }

        @Override
        public String description() {
            return sourceName + "." + role.value() + "  count: " + from + " -> " + to;
        }
    }

    record RuntimeChange(SourceName sourceName, NodeRole role, String fromRuntime, String toRuntime) implements DiffAction {
        @Override
        public String symbol() {
            return "~";
        }

        @Override
        public String description() {
            return sourceName + "." + role.value() + "  runtime: " + fromRuntime + " -> " + toRuntime;
        }
    }

    record SourceFieldChange(SourceName sourceName, String field) implements DiffAction {
        @Override
        public String symbol() {
            return "~";
        }

        @Override
        public String description() {
            return sourceName + "  " + field + " changed";
        }
    }

    record ClusterLevelChange(String field, String from, String to) implements DiffAction {
        @Override
        public String symbol() {
            return "~";
        }

        @Override
        public String description() {
            return "cluster." + field + ": " + from + " -> " + to;
        }
    }

    record ImmutableFieldChange(String field) implements DiffAction {
        @Override
        public String symbol() {
            return "!";
        }

        @Override
        public String description() {
            return field + " is immutable and cannot be changed";
        }
    }
}
