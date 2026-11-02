# test-stream-checkpoint-load (#2512 S7, #2590)

Generated slice fixture for the stream cursor-checkpoint load measurement (`M1StreamLoadTest` in `aether/ember`). A consumer group is
`artifact.base() + "-" + methodName`, so `gen.py` renders G subscriber methods onto one slice over a P-partition stream. Not in the default
reactor; build and install all six arms (G in {1,10,100} x P in {4,16}) from the repo root:

    python3 aether/tests/blueprints/test-stream-checkpoint-load/gen.py all aether/tests/blueprints/test-stream-checkpoint-load/target/gen
    mvn -f aether/tests/blueprints/test-stream-checkpoint-load/target/gen/pom.xml install

`target/gen/` is generated output and is not committed.
