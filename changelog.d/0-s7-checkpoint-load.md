### Added
- S7 (#2512, #2590): a generated stream-checkpoint-load slice fixture (`aether/tests/blueprints/test-stream-checkpoint-load`) and `M1StreamLoadTest` in `aether/ember` that count the consensus load of cursor checkpoints per (group, partition) with a producer at a recorded rate, on cores or workers, plus an idle control. Test-only; excluded from CI by the `Benchmark` tag.
