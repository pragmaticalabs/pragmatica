# Embedded TLA+ models (pilot)

TLA+ models of critical concurrency paths live in the Java source, as fragments inside `///` doc comments.
`extract.py` assembles them into a module and its TLC configs; `check.sh` extracts and runs TLC. Model checking
only, no proofs.

## Convention

- A fragment is a Markdown code block inside a `///` doc comment, tagged `tla <Module>:<Part>`:

  ```java
  /// ```tla SharedPools:Acq_Admit
  /// Acq_Admit(t) ==
  ///     /\ ...
  /// ```
  private SharedEntry admit(SharedEntry current, String scope, String label, Admission admission) {
  ```

- **An action fragment sits on the atomic region that implements it**: the `compute` mapping function, the CAS,
  the method whose body is one step. Not on the whole public method when that method spans several steps. Its
  `Part` is the action name, it must define an operator of that name at column 0, and the same name is the trace
  event emitted for that step.
- **The skeleton** (`Part` = `head` and `tail`) carries what is module-wide: `EXTENDS`, `CONSTANTS`, `VARIABLES`,
  helpers shared by several actions, library semantics that are not our code (e.g. ConcurrentHashMap's count
  publication), `Init`, `Next`, fairness, invariants and properties. It sits in `package-info.java`, or in the
  protocol class header.
- **Configs** are blocks tagged `tlacfg <Module>:<Variant>`, written to `<Module>_<Variant>.cfg`. Variant `common`
  is prepended to every other variant. One variant per property keeps each counterexample attributable.
- Assembly order is deterministic: `head`, then the action fragments ordered by (source path, line), then `tail`.
  The extractor writes the `MODULE` line and the closing `====`.
- The skeleton may only reference actions that exist, and every action must be referenced by `tail` (in `Next` or
  fairness). An action that is defined but never reachable is an error, not a warning.

## Checks the extractor enforces (exit 2, `file:line: message`)

- a fence that is not closed before the doc comment or the file ends;
- a tag that is not `<Module>:<Part>`;
- a duplicate `Part` or config variant (both positions are named);
- a missing `head` or `tail`, or no config;
- an action fragment that does not define its own name, or that `tail` never references.

Syntax and semantic errors are SANY's and TLC's. `check.sh` annotates every `line N, col M ... of module <Module>`
position in TLC's output with the Java source line it came from (`<Module>.map.tsv`), so a counterexample reads as
a sequence of Java locations.

## Running

```
tools/tla/check.sh <Module> <Variant> <source roots...>
# e.g.
tools/tla/check.sh SharedPools leak aether/resource/api/src/main/java
```

Environment: `TLA_JAR` (default `~/tools/tla/tla2tools.jar`), `TLC_WORKERS`, `TLC_OPTS`, `TLA_OUT` (default
`target/tla/<Module>`). The deadlock check is off: bounded models end in terminal states by design. The full TLC log
is `<out>/<Module>_<Variant>.log`; the console gets the verdict, the counterexample as actions with their Java
positions, and the state counts.

Run TLC on a build host, not on a laptop: a model with two keys, two scopes and four acquires has tens of millions
of states.
