package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;


/// Core topology policy. S3.3 REQ-3.3.1 through REQ-3.3.7
public record CoreTopology(Option<Integer> min, Option<Integer> max, int maxUnavailable) {
    public static CoreTopology coreTopology(Option<Integer> min, Option<Integer> max, int maxUnavailable) {
        return new CoreTopology(min, max, maxUnavailable);
    }

    public static CoreTopology defaultCoreTopology() {
        return new CoreTopology(Option.none(), Option.none(), 1);
    }
}
