# JBCT Parser Benchmark

JMH benchmark for `org.pragmatica.jbct.parser.Java25Parser`.

The benchmark exercises all three public entry points of the parser against a
fixed source fixture (`FactoryClassGenerator.java`, ~1.9k LOC). The fixture is
copied into `src/main/resources/FactoryClassGenerator.java.txt` at module
creation time and is **not** re-synced from the live source tree, so results
remain comparable across commits even if `FactoryClassGenerator.java` evolves.

## Benchmarks

| Benchmark               | Parser method                | Output                              |
|-------------------------|------------------------------|-------------------------------------|
| `parseCst`              | `parse(String)`              | Concrete Syntax Tree (with trivia)  |
| `parseAst`              | `parseAst(String)`           | Abstract Syntax Tree (no trivia)    |
| `parseWithDiagnostics`  | `parseWithDiagnostics(...)`  | CST + recovery + diagnostics list   |

## Configuration

- Modes: `Throughput` + `AverageTime`
- Output unit: milliseconds
- Warmup: 3 iterations × 2 s
- Measurement: 5 iterations × 2 s
- Forks: 2
- JMH version: 1.37

A fresh `Java25Parser` instance is allocated inside each `@Benchmark`
invocation, matching typical real-world usage where a parser instance is not
reused across parses.

## Build & run

The easiest path — builds the shaded `benchmarks.jar` and runs it:

```bash
./run-benchmark.sh
```

Any arguments are forwarded to the JMH runner:

```bash
./run-benchmark.sh parseAst                       # run a single benchmark
./run-benchmark.sh -prof gc                       # attach the GC profiler
./run-benchmark.sh -rf json -rff results.json     # JSON results
./run-benchmark.sh -h                             # list all JMH options
```

### Manual invocation

```bash
# from repo root
mvn -pl jbct/jbct-parser-benchmark -am clean package -DskipTests
java --enable-preview -jar jbct/jbct-parser-benchmark/target/benchmarks.jar
```

## Notes

- `--enable-preview` is required because the parser module is compiled with
  Java preview features enabled; the run script passes the flag automatically.
- The module is excluded from `deploy`/`install`/`gpg` — it is not published.
- To refresh the fixture from the current source tree, copy the file manually
  and note the reason in the commit message; do not automate it (comparability
  across versions is the whole point).
