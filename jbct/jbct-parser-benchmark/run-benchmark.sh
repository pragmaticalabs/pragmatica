#!/usr/bin/env bash
# Build and run the Java25Parser JMH benchmark.
# Any arguments are forwarded to the JMH runner, e.g.:
#   ./run-benchmark.sh                 # run all benchmarks with defaults
#   ./run-benchmark.sh parseAst        # run only the parseAst benchmark
#   ./run-benchmark.sh -prof gc        # attach the GC profiler
#   ./run-benchmark.sh -rf json -rff results.json
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
cd "${SCRIPT_DIR}/.."

mvn -pl jbct-parser-benchmark -am -q clean package -DskipTests

BENCH_JAR="${SCRIPT_DIR}/target/benchmarks.jar"
if [[ ! -f "${BENCH_JAR}" ]]; then
    echo "Benchmark jar not found at ${BENCH_JAR}" >&2
    exit 1
fi

exec java --enable-preview -jar "${BENCH_JAR}" "$@"
