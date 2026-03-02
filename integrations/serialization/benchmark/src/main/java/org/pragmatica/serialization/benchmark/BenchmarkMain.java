package org.pragmatica.serialization.benchmark;

import org.openjdk.jmh.Main;

public sealed interface BenchmarkMain {
    record unused() implements BenchmarkMain {}

    static void main(String[] args) throws Exception {
        Main.main(args);
    }
}
