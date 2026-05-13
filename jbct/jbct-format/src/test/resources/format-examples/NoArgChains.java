package format.examples;

import java.util.List;
import java.util.stream.Stream;

public class NoArgChains {
    // Single method call — not a chain, no break.
    int singleCall(List<String> items) {
        return items.size();
    }

    // 3 chained no-arg calls — Sequencer-as-steps still applies; each break.
    String tripleNoArg(String value) {
        return value.trim()
                    .toLowerCase()
                    .intern();
    }

    // Mixed no-arg + arg chain — each method on own line.
    String mixedChain(List<String> items) {
        return items.stream()
                    .findFirst()
                    .orElse("");
    }

    // Long no-arg chain that wouldn't fit inline anyway.
    Stream<String> longNoArg(List<String> items) {
        return items.stream()
                    .distinct()
                    .sorted()
                    .parallel();
    }
}
