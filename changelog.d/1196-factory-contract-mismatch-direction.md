### Fixed (2026-09-29 — #1196: factory arity/type mismatch messages asserted "compiled against an older runtime")
- **A factory signature that does not match the runtime's `(SliceCreationContext)` contract was reported as
  "slice was compiled against an older runtime"**, a direction the code cannot observe: a slice built by a
  newer version reaches the same state and was told the opposite.
- The message now states only the mismatch, notes that an older or a newer build reaches it, and says to
  build the slice with the same Aether version as the runtime.
  `[mechanism: SliceFactory.verifyParameters builds both messages from one constant; pinned by SliceFactoryTest]`
