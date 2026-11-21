### Changed (2026-11-20 - #3413: the drain command has its own runtime key)
- **New runtime key `DrainCommandKey(NodeId target)`**, wire tag 2962 (`drain-command/<node>`): the key of the committed drain row. It is runtime
  state: no backup carries it, the worker replica excludes it and the worker projection gives it no scope.
- **No flag day**: the baseline gains two lines (the key's `SHAPE` and `TAG`) and removes none, so the wire generation stays 5.
